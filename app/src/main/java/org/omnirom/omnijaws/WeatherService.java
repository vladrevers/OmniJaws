/*
 *  Copyright (C) 2017 The OmniROM Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.omnirom.omnijaws;

import java.util.Date;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

public class WeatherService extends Service {
    private static final String TAG = "org.omnirom.omnijaws:WeatherService";
    private static final boolean DEBUG = false;
    private static final String ACTION_UPDATE = "org.omnirom.omnijaws.ACTION_UPDATE";
    private static final String ACTION_ALARM = "org.omnirom.omnijaws.ACTION_ALARM";
    private static final String ACTION_ENABLE = "org.omnirom.omnijaws.ACTION_ENABLE";
    private static final String ACTION_BROADCAST = "org.omnirom.omnijaws.WEATHER_UPDATE";
    private static final String ACTION_ERROR = "org.omnirom.omnijaws.WEATHER_ERROR";

    private static final String EXTRA_ENABLE = "enable";
    private static final String EXTRA_ERROR = "error";

    private static final int EXTRA_ERROR_NETWORK = 0;
    private static final int EXTRA_ERROR_LOCATION = 1;
    private static final int EXTRA_ERROR_DISABLED = 2;

    static final String ACTION_CANCEL_LOCATION_UPDATE =
            "org.omnirom.omnijaws.CANCEL_LOCATION_UPDATE";

    private static final float LOCATION_ACCURACY_THRESHOLD_METERS = 50000;
    public static final long LOCATION_REQUEST_TIMEOUT = 5L * 60L * 1000L; // request for at most 5 minutes
    private static final long OUTDATED_LOCATION_THRESHOLD_MILLIS = 10L * 60L * 1000L; // 10 minutes
    private static final long ALARM_INTERVAL_BASE = AlarmManager.INTERVAL_HOUR;
    private static final int RETRY_DELAY_MS = 5000;
    private static final int RETRY_MAX_NUM = 5;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private PowerManager.WakeLock mWakeLock;
    private boolean mRunning;
    private static PendingIntent mAlarm;

    private static final Criteria sLocationCriteria;
    static {
        sLocationCriteria = new Criteria();
        sLocationCriteria.setPowerRequirement(Criteria.POWER_LOW);
        sLocationCriteria.setAccuracy(Criteria.ACCURACY_COARSE);
        sLocationCriteria.setCostAllowed(false);
    }

    private BroadcastReceiver mScreenStateListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "screenStateListener:onReceive");
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                if (Config.isEnabled(context) && Config.isUpdateError(context)) {
                    Log.i(TAG, "screenStateListener trigger update after update error");
                    WeatherService.startUpdate(context);
                }
            }
        }
    };

    public WeatherService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.d(TAG, "onCreate");
        mHandlerThread = new HandlerThread("WeatherService Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);
        registerScreenStateListener();
    }

    public static void startUpdate(Context context) {
        start(context, ACTION_UPDATE);
    }

    private static void start(Context context, String action) {
        Intent i = new Intent(context, WeatherService.class);
        i.setAction(action);
        context.startService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, WeatherService.class);
        i.setAction(ACTION_ENABLE);
        i.putExtra(EXTRA_ENABLE, false);
        context.startService(i);
    }

    private static PendingIntent alarmPending(Context context) {
        Intent intent = new Intent(context, WeatherService.class);
        intent.setAction(ACTION_ALARM);
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Config.setUpdateError(this, false);
        if (intent == null) {
            Log.w(TAG, "intent == null");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (mRunning) {
            Log.w(TAG, "Service running ... do nothing");
            return START_STICKY;
        }

        mWakeLock.acquire();
        try {
            if (ACTION_ENABLE.equals(intent.getAction())) {
                boolean enable = intent.getBooleanExtra(EXTRA_ENABLE, false);
                if (DEBUG) Log.d(TAG, "Set enablement " + enable);
                Config.setEnabled(this, enable);
                if (!enable) {
                    cancelUpdate(this);
                }
            }

            if (!Config.isEnabled(this)) {
                Log.w(TAG, "Service started, but not enabled ... stopping");
                Intent errorIntent = new Intent(ACTION_ERROR);
                errorIntent.putExtra(EXTRA_ERROR, EXTRA_ERROR_DISABLED);
                sendBroadcast(errorIntent);
                stopSelf();
                return START_NOT_STICKY;
            }

            if (ACTION_CANCEL_LOCATION_UPDATE.equals(intent.getAction())) {
                Log.w(TAG, "Service started, but location timeout ... stopping");
                WeatherLocationListener.cancel(this);
                Intent errorIntent = new Intent(ACTION_ERROR);
                errorIntent.putExtra(EXTRA_ERROR, EXTRA_ERROR_LOCATION);
                sendBroadcast(errorIntent);
                Config.setUpdateError(this, true);
                return START_STICKY;
            }

            if (!isNetworkAvailable()) {
                if (DEBUG) Log.d(TAG, "Service started, but no network ... stopping");
                Intent errorIntent = new Intent(ACTION_ERROR);
                errorIntent.putExtra(EXTRA_ERROR, EXTRA_ERROR_NETWORK);
                sendBroadcast(errorIntent);
                Config.setUpdateError(this, true);
                return START_STICKY;
            }

            if (ACTION_ALARM.equals(intent.getAction())) {
                Config.setLastAlarmTime(this);
            }
            if (DEBUG) Log.d(TAG, "updateWeather");
            updateWeather();
        } finally {
            mWakeLock.release();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Log.d(TAG, "onDestroy");
        unregisterScreenStateListener();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager)this.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private boolean doCheckLocationEnabled() {
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, -1) != Settings.Secure.LOCATION_MODE_OFF;
    }

    private Location getCurrentLocation() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (!doCheckLocationEnabled()) {
            Log.w(TAG, "locations disabled");
            return null;
        }
        Location location = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        if (DEBUG) Log.d(TAG, "Current location is " + location);

        if (location != null && location.getAccuracy() > LOCATION_ACCURACY_THRESHOLD_METERS) {
            Log.w(TAG, "Ignoring inaccurate location");
            location = null;
        }

        // If lastKnownLocation is not present (because none of the apps in the
        // device has requested the current location to the system yet) or outdated,
        // then try to get the current location use the provider that best matches the criteria.
        boolean needsUpdate = location == null;
        if (location != null) {
            long delta = System.currentTimeMillis() - location.getTime();
            needsUpdate = delta > OUTDATED_LOCATION_THRESHOLD_MILLIS;
        }
        if (needsUpdate) {
            if (DEBUG) Log.d(TAG, "Getting best location provider");
            String locationProvider = lm.getBestProvider(sLocationCriteria, true);
            if (TextUtils.isEmpty(locationProvider)) {
                Log.e(TAG, "No available location providers matching criteria.");
            } else {
                WeatherLocationListener.registerIfNeeded(this, locationProvider);
            }
        }

        return location;
    }

    public static void scheduleUpdate(Context context) {
        cancelUpdate(context);

        final long interval = ALARM_INTERVAL_BASE * Config.getUpdateInterval(context);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        final long due = System.currentTimeMillis() + interval;
        Config.setLastAlarmTime(context);

        if (DEBUG) Log.d(TAG, "Scheduling next update at " + new Date(due));

        mAlarm = alarmPending(context);
        am.setInexactRepeating(AlarmManager.RTC, due, interval, mAlarm);
        startUpdate(context);
    }

    public static void cancelUpdate(Context context) {
        if (mAlarm != null) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (DEBUG) Log.d(TAG, "Cancel pending update");

            am.cancel(mAlarm);
            mAlarm = null;
        }
    }

    private void updateWeather() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                WeatherInfo w = null;
                try {
                    mRunning = true;
                    mWakeLock.acquire();
                    AbstractWeatherProvider provider = Config.getProvider(WeatherService.this);
                    int i = 0;
                    // retry max 3 times
                    while(i < RETRY_MAX_NUM) {
                        if (!Config.isCustomLocation(WeatherService.this)) {
                            if (checkPermissions()) {
                                Location location = getCurrentLocation();
                                if (location != null) {
                                    w = provider.getLocationWeather(location, Config.isMetric(WeatherService.this));
                                } else {
                                    Log.w(TAG, "no location");
                                    // we are outa here
                                    break;
                                }
                            } else {
                                Log.w(TAG, "no location permissions");
                                // we are outa here
                                break;
                            }
                        } else if (Config.getLocationId(WeatherService.this) != null){
                            w = provider.getCustomWeather(Config.getLocationId(WeatherService.this), Config.isMetric(WeatherService.this));
                        } else {
                            Log.w(TAG, "no valid custom location");
                            // we are outa here
                            break;
                        }
                        if (w != null) {
                            Config.setWeatherData(WeatherService.this, w);
                            WeatherContentProvider.updateCachedWeatherInfo(WeatherService.this);
                            // we are outa here
                            break;
                        } else {
                            if (!provider.shouldRetry()) {
                                // some other error
                                break;
                            } else {
                                Log.w(TAG, "retry count =" + i);
                                try {
                                    Thread.sleep(RETRY_DELAY_MS);
                                } catch (InterruptedException e) {
                                }
                            }
                        }
                        i++;
                    }
                } finally {
                    if (w == null) {
                        // error
                        Config.setUpdateError(WeatherService.this, true);
                    }
                    // send broadcast that something has changed
                    Intent updateIntent = new Intent(ACTION_BROADCAST);
                    sendBroadcast(updateIntent);
                    mWakeLock.release();
                    mRunning = false;
                }
            }
         });
    }

    private boolean checkPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerScreenStateListener() {
        if (DEBUG) Log.d(TAG, "registerScreenStateListener");
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        this.registerReceiver(mScreenStateListener, filter);
    }

    private void unregisterScreenStateListener() {
        if (DEBUG) Log.d(TAG, "unregisterScreenStateListener");
        try {
            this.unregisterReceiver(mScreenStateListener);
        } catch (Exception e) {
        }
    }
}
