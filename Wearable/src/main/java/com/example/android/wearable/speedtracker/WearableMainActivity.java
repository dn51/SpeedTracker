/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.wearable.speedtracker;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.wear.ambient.AmbientModeSupport;

import com.example.android.wearable.speedtracker.common.Constants;
import com.example.android.wearable.speedtracker.common.LocationEntry;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * The main activity for the wearable app. User can pick a speed limit, and after this activity
 * obtains a fix on the GPS, it starts reporting the speed. In addition to showing the current
 * speed, if user's speed gets close to the selected speed limit, the color of speed turns yellow
 * and if the user exceeds the speed limit, it will turn red. In order to show the user that GPS
 * location data is coming in, a small green dot keeps on blinking while GPS data is available.
 */
public class WearableMainActivity extends FragmentActivity implements
        AmbientModeSupport.AmbientCallbackProvider,
        ActivityCompat.OnRequestPermissionsResultCallback {

    private static final String TAG = "WearableActivity";

    private static final long UPDATE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long FASTEST_INTERVAL_MS = TimeUnit.SECONDS.toMillis(5);

    private static final float MPH_IN_METERS_PER_SECOND = 2.23694f;

    private static final int SPEED_LIMIT_DEFAULT_MPH = 45;

    private static final long INDICATOR_DOT_FADE_AWAY_MS = 500L;

    // Request codes for changing speed limit and location permissions.
    private static final int REQUEST_PICK_SPEED_LIMIT = 0;

    // Id to identify Location permission request.
    private static final int REQUEST_GPS_PERMISSION = 1;

    // Shared Preferences for saving speed limit and location permission between app launches.
    private static final String PREFS_SPEED_LIMIT_KEY = "SpeedLimit";

    private Calendar mCalendar;

    private TextView mSpeedLimitTextView;
    private TextView mSpeedTextView;
    private ImageView mGpsPermissionImageView;
    private TextView mCurrentSpeedMphTextView;
    private TextView mGpsIssueTextView;
    private View mBlinkingGpsStatusDotView;

    private String mGpsPermissionNeededMessage;
    private String mAcquiringGpsMessage;

    private int mSpeedLimit;
    private float mSpeed;

    private boolean mGpsPermissionApproved;

    private boolean mWaitingForGpsSignal;

    /**
     * Ambient mode controller attached to this display. Used by the Activity to see if it is in
     * ambient mode.
     */
    private AmbientModeSupport.AmbientController mAmbientController;

    private FusedLocationProviderClient fusedLocationProviderClient;
    private LocationCallback locationCallback;

    private Handler mHandler = new Handler();

    private enum SpeedState {
        BELOW(R.color.speed_below), CLOSE(R.color.speed_close), ABOVE(R.color.speed_above);

        private int mColor;

        SpeedState(int color) {
            mColor = color;
        }

        int getColor() {
            return mColor;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(TAG, "onCreate()");


        setContentView(R.layout.main_activity);

        /*
         * Enables Always-on, so our app doesn't shut down when the watch goes into ambient mode.
         * Best practice is to override onEnterAmbient(), onUpdateAmbient(), and onExitAmbient() to
         * optimize the display for ambient mode. However, for brevity, we aren't doing that here
         * to focus on learning location and permissions. For more information on best practices
         * in ambient mode, check this page:
         * https://developer.android.com/training/wearables/apps/always-on.html
         */
        // Enables Ambient mode.
        mAmbientController = AmbientModeSupport.attach(this);

        mCalendar = Calendar.getInstance();

        // Enables app to handle 23+ (M+) style permissions.
        mGpsPermissionApproved =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;

        mGpsPermissionNeededMessage = getString(R.string.permission_rationale);
        mAcquiringGpsMessage = getString(R.string.acquiring_gps);


        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mSpeedLimit = sharedPreferences.getInt(PREFS_SPEED_LIMIT_KEY, SPEED_LIMIT_DEFAULT_MPH);

        mSpeed = 0;

        mWaitingForGpsSignal = true;


        /*
         * If this hardware doesn't support GPS, we warn the user. Note that when such device is
         * connected to a phone with GPS capabilities, the framework automatically routes the
         * location requests from the phone. However, if the phone becomes disconnected and the
         * wearable doesn't support GPS, no location is recorded until the phone is reconnected.
         */
        if (!hasGps()) {
            Log.w(TAG, "This hardware doesn't have GPS, so we warn user.");
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.gps_not_available))
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int id) {
                            dialog.cancel();
                        }
                    })
                    .setOnDismissListener(new DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(DialogInterface dialog) {
                            dialog.cancel();
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
        }


        setupViews();

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    return;
                }


                for (Location location : locationResult.getLocations()) {
                    Log.d(TAG, "onLocationChanged() : " + location);


                    if (mWaitingForGpsSignal) {
                        mWaitingForGpsSignal = false;
                        updateActivityViewsBasedOnLocationPermissions();
                    }

                    mSpeed = location.getSpeed() * MPH_IN_METERS_PER_SECOND;
                    updateSpeedInViews();
                    addLocationEntry(location.getLatitude(), location.getLongitude());
                }
            };
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        fusedLocationProviderClient.removeLocationUpdates(locationCallback);

    }

    private void setupViews() {
        mSpeedLimitTextView = findViewById(R.id.max_speed_text);
        mSpeedTextView = findViewById(R.id.current_speed_text);
        mCurrentSpeedMphTextView = findViewById(R.id.current_speed_mph);

        mGpsPermissionImageView = findViewById(R.id.gps_permission);
        mGpsIssueTextView = findViewById(R.id.gps_issue_text);
        mBlinkingGpsStatusDotView = findViewById(R.id.dot);

        updateActivityViewsBasedOnLocationPermissions();
    }

    public void onSpeedLimitClick(View view) {
        Intent speedIntent = new Intent(WearableMainActivity.this,
                SpeedPickerActivity.class);
        startActivityForResult(speedIntent, REQUEST_PICK_SPEED_LIMIT);
    }

    public void onGpsPermissionClick(View view) {

        if (!mGpsPermissionApproved) {

            Log.i(TAG, "Location permission has NOT been granted. Requesting permission.");

            // On 23+ (M+) devices, GPS permission not granted. Request permission.
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_GPS_PERMISSION);
        }
    }

    /**
     * Adjusts the visibility of views based on location permissions.
     */
    private void updateActivityViewsBasedOnLocationPermissions() {

        /*
         * If the user has approved location but we don't have a signal yet, we let the user know
         * we are waiting on the GPS signal (this sometimes takes a little while). Otherwise, the
         * user might think something is wrong.
         */
        if (mGpsPermissionApproved && mWaitingForGpsSignal) {

            // We are getting a GPS signal w/ user permission.
            mGpsIssueTextView.setText(mAcquiringGpsMessage);
            mGpsIssueTextView.setVisibility(View.VISIBLE);
            mGpsPermissionImageView.setImageResource(R.drawable.ic_gps_saving_grey600_96dp);

            mSpeedTextView.setVisibility(View.GONE);
            mSpeedLimitTextView.setVisibility(View.GONE);
            mCurrentSpeedMphTextView.setVisibility(View.GONE);

        } else if (mGpsPermissionApproved) {

            mGpsIssueTextView.setVisibility(View.GONE);

            mSpeedTextView.setVisibility(View.VISIBLE);
            mSpeedLimitTextView.setVisibility(View.VISIBLE);
            mCurrentSpeedMphTextView.setVisibility(View.VISIBLE);
            mGpsPermissionImageView.setImageResource(R.drawable.ic_gps_saving_grey600_96dp);

        } else {

            // User needs to enable location for the app to work.
            mGpsIssueTextView.setVisibility(View.VISIBLE);
            mGpsIssueTextView.setText(mGpsPermissionNeededMessage);
            mGpsPermissionImageView.setImageResource(R.drawable.ic_gps_not_saving_grey600_96dp);

            mSpeedTextView.setVisibility(View.GONE);
            mSpeedLimitTextView.setVisibility(View.GONE);
            mCurrentSpeedMphTextView.setVisibility(View.GONE);
        }
    }

    private void updateSpeedInViews() {

        if (mGpsPermissionApproved) {

            mSpeedLimitTextView.setText(getString(R.string.speed_limit, mSpeedLimit));
            mSpeedTextView.setText(String.format(getString(R.string.speed_format), mSpeed));

            // Adjusts the color of the speed based on its value relative to the speed limit.
            SpeedState state = SpeedState.ABOVE;
            if (mSpeed <= mSpeedLimit - 5) {
                state = SpeedState.BELOW;
            } else if (mSpeed <= mSpeedLimit) {
                state = SpeedState.CLOSE;
            }

            mSpeedTextView.setTextColor(getResources().getColor(state.getColor()));

            // Causes the (green) dot blinks when new GPS location data is acquired.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mBlinkingGpsStatusDotView.setVisibility(View.VISIBLE);
                }
            });
            mBlinkingGpsStatusDotView.setVisibility(View.VISIBLE);
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mBlinkingGpsStatusDotView.setVisibility(View.INVISIBLE);
                }
            }, INDICATOR_DOT_FADE_AWAY_MS);
        }
    }


    private void requestLocation() {
        Log.d(TAG, "requestLocation()");

        /*
         * mGpsPermissionApproved covers 23+ (M+) style permissions. If that is already approved or
         * the device is pre-23, the app uses mSaveGpsLocation to save the user's location
         * preference.
         */
        if (mGpsPermissionApproved) {

            LocationRequest locationRequest = LocationRequest.create()
                    .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                    .setInterval(UPDATE_INTERVAL_MS)
                    .setFastestInterval(FASTEST_INTERVAL_MS);


            try {
                fusedLocationProviderClient.requestLocationUpdates(
                        locationRequest, locationCallback, Looper.getMainLooper());

            } catch (SecurityException unlikely) {
                Log.e(TAG, "Lost location permissions. Couldn't remove updates. " + unlikely);
            }
        }
    }

    /*
     * Adds a data item to the data Layer storage.
     */
    private void addLocationEntry(double latitude, double longitude) {
        if (!mGpsPermissionApproved) {
            return;
        }
        mCalendar.setTimeInMillis(System.currentTimeMillis());
        LocationEntry entry = new LocationEntry(mCalendar, latitude, longitude);
        String path = Constants.PATH + "/" + mCalendar.getTimeInMillis();
        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(path);
        putDataMapRequest.getDataMap().putDouble(Constants.KEY_LATITUDE, entry.latitude);
        putDataMapRequest.getDataMap().putDouble(Constants.KEY_LONGITUDE, entry.longitude);
        putDataMapRequest.getDataMap()
                .putLong(Constants.KEY_TIME, entry.calendar.getTimeInMillis());
        PutDataRequest request = putDataMapRequest.asPutDataRequest();
        request.setUrgent();

        Task<DataItem> dataItemTask =
                Wearable.getDataClient(getApplicationContext()).putDataItem(request);

        dataItemTask.addOnSuccessListener(dataItem -> {
            Log.d(TAG, "Data successfully sent: " + dataItem.toString());
        });
        dataItemTask.addOnFailureListener(exception -> {
            Log.e(TAG, "AddPoint:onClick(): Failed to set the data, "
                    + "exception: " + exception);
        });
    }

    /**
     * Handles user choices for both speed limit and location permissions (GPS tracking).
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {

        if (requestCode == REQUEST_PICK_SPEED_LIMIT) {
            if (resultCode == RESULT_OK) {
                // The user updated the speed limit.
                int newSpeedLimit =
                        data.getIntExtra(SpeedPickerActivity.EXTRA_NEW_SPEED_LIMIT, mSpeedLimit);

                SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt(WearableMainActivity.PREFS_SPEED_LIMIT_KEY, newSpeedLimit);
                editor.apply();

                mSpeedLimit = newSpeedLimit;

                updateSpeedInViews();
            }
        }
    }

    /**
     * Callback received when a permissions request has been completed.
     */
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        Log.d(TAG, "onRequestPermissionsResult(): " + permissions);


        if (requestCode == REQUEST_GPS_PERMISSION) {
            Log.i(TAG, "Received response for GPS permission request.");

            if ((grantResults.length == 1)
                    && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Log.i(TAG, "GPS permission granted.");
                mGpsPermissionApproved = true;

                requestLocation();

            } else {
                Log.i(TAG, "GPS permission NOT granted.");
                mGpsPermissionApproved = false;
            }

            updateActivityViewsBasedOnLocationPermissions();

        }
    }

    /**
     * Returns {@code true} if this device has the GPS capabilities.
     */
    private boolean hasGps() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new MyAmbientCallback();
    }

    private class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        /** Prepares the UI for ambient mode. */
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            super.onEnterAmbient(ambientDetails);

            Log.d(TAG, "onEnterAmbient() " + ambientDetails);

            // Changes views to grey scale.
            mSpeedTextView.setTextColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.white));
        }

        /** Restores the UI to active (non-ambient) mode. */
        @Override
        public void onExitAmbient() {
            super.onExitAmbient();

            Log.d(TAG, "onExitAmbient()");

            // Changes views to color.
            mSpeedTextView.setTextColor(
                    ContextCompat.getColor(getApplicationContext(), R.color.green));
        }
    }
}
