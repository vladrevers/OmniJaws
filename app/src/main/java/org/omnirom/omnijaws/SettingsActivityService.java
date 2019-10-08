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

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.location.LocationManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;

import org.omnirom.omnijaws.client.OmniJawsClient;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivityService extends PreferenceActivity implements OnPreferenceChangeListener, WeatherLocationTask.Callback  {

    private static final String CHRONUS_ICON_PACK_INTENT = "com.dvtonder.chronus.ICON_PACK";
    private static final String DEFAULT_WEATHER_ICON_PACKAGE = "org.omnirom.omnijaws";

    private SharedPreferences mPrefs;
    private ListPreference mProvider;
    private CheckBoxPreference mCustomLocation;
    private ListPreference mUnits;
    private SwitchPreference mEnable;
    private boolean mTriggerUpdate;
    private boolean mTriggerPermissionCheck;
    private ListPreference mUpdateInterval;
    private CustomLocationPreference mLocation;
    private ListPreference mWeatherIconPack;
    private Preference mUpdateStatus;
    private Handler mHandler = new Handler();
    protected boolean mShowIconPack;

    private static final String PREF_KEY_CUSTOM_LOCATION_CITY = "weather_custom_location_city";
    private static final int PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 0;
    private static final String WEATHER_ICON_PACK = "weather_icon_pack";
    private static final String PREF_KEY_UPDATE_STATUS = "update_status";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        doLoadPreferences();
    }

    private void doLoadPreferences() {
        addPreferencesFromResource(R.xml.settings);
        final PreferenceScreen prefScreen = getPreferenceScreen();
        mEnable = (SwitchPreference) findPreference(Config.PREF_KEY_ENABLE);
        mEnable.setChecked(Config.isEnabled(this));
        mEnable.setOnPreferenceChangeListener(this);

        mCustomLocation = (CheckBoxPreference) findPreference(Config.PREF_KEY_CUSTOM_LOCATION);

        mProvider = (ListPreference) findPreference(Config.PREF_KEY_PROVIDER);
        mProvider.setOnPreferenceChangeListener(this);
        int idx = mProvider.findIndexOfValue(mPrefs.getString(Config.PREF_KEY_PROVIDER, "0"));
        if (idx == -1) {
            idx = 0;
        }
        mProvider.setValueIndex(idx);
        mProvider.setSummary(mProvider.getEntries()[idx]);

        mUnits = (ListPreference) findPreference(Config.PREF_KEY_UNITS);
        mUnits.setOnPreferenceChangeListener(this);
        idx = mUnits.findIndexOfValue(mPrefs.getString(Config.PREF_KEY_UNITS, "0"));
        if (idx == -1) {
            idx = 0;
        }
        mUnits.setValueIndex(idx);
        mUnits.setSummary(mUnits.getEntries()[idx]);

        mUpdateInterval = (ListPreference) findPreference(Config.PREF_KEY_UPDATE_INTERVAL);
        mUpdateInterval.setOnPreferenceChangeListener(this);
        idx = mUpdateInterval.findIndexOfValue(mPrefs.getString(Config.PREF_KEY_UPDATE_INTERVAL, "2"));
        if (idx == -1) {
            idx = 0;
        }
        mUpdateInterval.setValueIndex(idx);
        mUpdateInterval.setSummary(mUpdateInterval.getEntries()[idx]);

        mLocation = (CustomLocationPreference) findPreference(PREF_KEY_CUSTOM_LOCATION_CITY);
        if (mPrefs.getBoolean(Config.PREF_KEY_ENABLE, false)
                && !mPrefs.getBoolean(Config.PREF_KEY_CUSTOM_LOCATION, false)) {
            mTriggerUpdate = false;
            checkLocationEnabled();
        }
        mWeatherIconPack = (ListPreference) findPreference(WEATHER_ICON_PACK);

        if (mShowIconPack) {
            String settingHeaderPackage = Config.getIconPack(this);
            List<String> entries = new ArrayList<String>();
            List<String> values = new ArrayList<String>();
            getAvailableWeatherIconPacks(entries, values);
            mWeatherIconPack.setEntries(entries.toArray(new String[entries.size()]));
            mWeatherIconPack.setEntryValues(values.toArray(new String[values.size()]));

            int valueIndex = mWeatherIconPack.findIndexOfValue(settingHeaderPackage);
            if (valueIndex == -1) {
                // no longer found
                settingHeaderPackage = DEFAULT_WEATHER_ICON_PACKAGE;
                Config.setIconPack(this, settingHeaderPackage);
                valueIndex = mWeatherIconPack.findIndexOfValue(settingHeaderPackage);
            }
            mWeatherIconPack.setValueIndex(valueIndex >= 0 ? valueIndex : 0);
            mWeatherIconPack.setSummary(mWeatherIconPack.getEntry());
            mWeatherIconPack.setOnPreferenceChangeListener(this);
        } else {
            prefScreen.removePreference(mWeatherIconPack);
        }
        mUpdateStatus = findPreference(PREF_KEY_UPDATE_STATUS);
        queryLastUpdateTime();
    }

    @Override
    public void onResume() {
        super.onResume();
        // values can be changed from outside
        getPreferenceScreen().removeAll();
        doLoadPreferences();
        if (mTriggerPermissionCheck) {
            checkLocationPermissions();
            mTriggerPermissionCheck = false;
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            Preference preference) {
        if (preference == mCustomLocation) {
            if (!mCustomLocation.isChecked()) {
                mTriggerUpdate = true;
                checkLocationEnabled();
            } else {
                if (Config.getLocationName(this) != null) {
                    // city ids are provider specific - so we need to recheck
                    // cause provider migth be changed while unchecked
                    new WeatherLocationTask(this, Config.getLocationName(this), this).execute();
                } else {
                    disableService();
                }
            }
            return true;
        } else if (preference == mUpdateStatus) {
            WeatherService.startUpdate(this);
            queryLastUpdateTime();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mProvider) {
            String value = (String) newValue;
            int idx = mProvider.findIndexOfValue(value);
            mProvider.setSummary(mProvider.getEntries()[idx]);
            mProvider.setValueIndex(idx);
            if (mCustomLocation.isChecked() && Config.getLocationName(this) != null) {
                // city ids are provider specific - so we need to recheck
                new WeatherLocationTask(this, Config.getLocationName(this), this).execute();
            } else {
                WeatherService.startUpdate(this);
            }
            return true;
        } else if (preference == mUnits) {
            String value = (String) newValue;
            int idx = mUnits.findIndexOfValue(value);
            mUnits.setSummary(mUnits.getEntries()[idx]);
            mUnits.setValueIndex(idx);
            WeatherService.startUpdate(this);
            return true;
        } else if (preference == mUpdateInterval) {
            String value = (String) newValue;
            int idx = mUpdateInterval.findIndexOfValue(value);
            mUpdateInterval.setSummary(mUpdateInterval.getEntries()[idx]);
            mUpdateInterval.setValueIndex(idx);
            WeatherService.scheduleUpdate(this);
            queryLastUpdateTime();
            return true;
        } else if (preference == mWeatherIconPack) {
            String value = (String) newValue;
            Config.setIconPack(this, value);
            int valueIndex = mWeatherIconPack.findIndexOfValue(value);
            mWeatherIconPack.setSummary(mWeatherIconPack.getEntries()[valueIndex]);
            return true;
        } else if (preference == mEnable) {
            boolean value = (Boolean) newValue;
            Config.setEnabled(this, value);
            if (value) {
                if (!mCustomLocation.isChecked()) {
                    mTriggerUpdate = true;
                    checkLocationEnabled();
                } else {
                    WeatherService.scheduleUpdate(this);
                }
            } else {
                disableService();
            }
            queryLastUpdateTime();
            return true;
        }
        return false;
    }

    private void showDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        final Dialog dialog;

        // Build and show the dialog
        builder.setTitle(R.string.weather_retrieve_location_dialog_title);
        builder.setMessage(R.string.weather_retrieve_location_dialog_message);
        builder.setCancelable(false);
        builder.setPositiveButton(R.string.weather_retrieve_location_dialog_enable_button,
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        mTriggerPermissionCheck = true;
                        mTriggerUpdate = true;
                        Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(intent);
                    }
                });
        builder.setNegativeButton(android.R.string.cancel, null);
        dialog = builder.create();
        dialog.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.playstore:
                launchPlaystore();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void launchPlaystore() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("market://search?q=Chronus+icons&c=apps"));
        startActivity(intent);
    }

    private void checkLocationPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                    PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
        } else {
            if (mTriggerUpdate) {
                WeatherService.scheduleUpdate(this);
                mTriggerUpdate = false;
            }
        }
    }

    private boolean doCheckLocationEnabled() {
        return Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE, -1) != Settings.Secure.LOCATION_MODE_OFF;
    }

    private void checkLocationEnabled() {
        if (!doCheckLocationEnabled()) {
            showDialog();
        } else {
            checkLocationPermissions();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (mTriggerUpdate) {
                        WeatherService.scheduleUpdate(this);
                        mTriggerUpdate = false;
                    }
                }
                break;
            }
        }
    }

    private void disableService() {
        // stop any pending
        WeatherService.cancelUpdate(this);
        WeatherService.stop(this);
    }

    @Override
    public void applyLocation(WeatherInfo.WeatherLocation result) {
        Config.setLocationId(this, result.id);
        Config.setLocationName(this, result.city);
        mLocation.setText(result.city);
        mLocation.setSummary(result.city);
        WeatherService.startUpdate(this);
    }

    private void getAvailableWeatherIconPacks(List<String> entries, List<String> values) {
        Intent i = new Intent();
        PackageManager packageManager = getPackageManager();
        i.setAction("org.omnirom.WeatherIconPack");
        for (ResolveInfo r : packageManager.queryIntentActivities(i, 0)) {
            String packageName = r.activityInfo.packageName;
            if (packageName.equals(DEFAULT_WEATHER_ICON_PACKAGE)) {
                values.add(0, r.activityInfo.name);
            } else {
                values.add(r.activityInfo.name);
            }
            String label = r.activityInfo.loadLabel(getPackageManager()).toString();
            if (label == null) {
                label = r.activityInfo.packageName;
            }
            if (packageName.equals(DEFAULT_WEATHER_ICON_PACKAGE)) {
                entries.add(0, label);
            } else {
                entries.add(label);
            }
        }
        i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(CHRONUS_ICON_PACK_INTENT);
        for (ResolveInfo r : packageManager.queryIntentActivities(i, 0)) {
            String packageName = r.activityInfo.packageName;
            values.add(packageName + ".weather");
            String label = r.activityInfo.loadLabel(getPackageManager()).toString();
            if (label == null) {
                label = r.activityInfo.packageName;
            }
            entries.add(label);
        }
    }

    private void queryLastUpdateTime() {
        final AsyncTask<Void, Void, Void> t = new AsyncTask<Void, Void, Void>() {
            @Override
            protected void onProgressUpdate(Void... values) {
            }
            @Override
            protected Void doInBackground(Void... params) {
                final String updateTime = getLastUpdateTime();
                SettingsActivityService.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mUpdateStatus.setSummary(updateTime);
                    }
                });
                return null;
            }
        };
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                t.execute();
            }
        }, 2000);
    }

    private String getLastUpdateTime() {
        OmniJawsClient mWeatherClient = new OmniJawsClient(this);
        if (mWeatherClient.isOmniJawsEnabled()) {
            OmniJawsClient.WeatherInfo mWeatherData = null;
            try {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData != null) {
                    return mWeatherData.getLastUpdateTime();
                }
            } catch(Exception ignored) {
            }
        }
        return getResources().getString(R.string.service_disabled);
    }
}
