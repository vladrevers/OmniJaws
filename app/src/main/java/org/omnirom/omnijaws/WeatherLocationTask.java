/*
 * Copyright (C) 2012 The CyanogenMod Project (DvTonder)
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

package org.omnirom.omnijaws;

import java.util.HashSet;
import java.util.List;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.widget.Toast;

public class WeatherLocationTask extends AsyncTask<Void, Void, List<WeatherInfo.WeatherLocation>> {
    private ProgressDialog mProgressDialog;
    private String mLocation;
    private Callback mCallback;
    private Context mContext;
    private AlertDialog mChoiceDialog;

    public interface Callback {
         void applyLocation(WeatherInfo.WeatherLocation result);
    };

    public WeatherLocationTask(Context context, String location, Callback callback) {
        mLocation = location;
        mCallback = callback;
        mContext = context;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();

        mProgressDialog = new ProgressDialog(mContext);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        mProgressDialog.setMessage(mContext.getString(R.string.weather_progress_title));
        mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                cancel(true);
            }
        });
        mProgressDialog.show();
    }

    @Override
    protected List<WeatherInfo.WeatherLocation> doInBackground(Void... input) {
        return Config.getProvider(mContext).getLocations(mLocation);
    }

    @Override
    protected void onPostExecute(List<WeatherInfo.WeatherLocation> results) {
        super.onPostExecute(results);

        if (results == null || results.isEmpty()) {
            Toast.makeText(mContext,
                    mContext.getString(R.string.weather_retrieve_location_dialog_title),
                    Toast.LENGTH_SHORT)
                    .show();
        } else if (results.size() > 1) {
            handleResultDisambiguation(results);
        } else {
            mCallback.applyLocation(results.get(0));
        }
        mProgressDialog.dismiss();
    }

    private void handleResultDisambiguation(final List<WeatherInfo.WeatherLocation> results) {
        CharSequence[] items = buildItemList(results);
        mChoiceDialog = new AlertDialog.Builder(mContext)
        .setSingleChoiceItems(items, -1, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mCallback.applyLocation(results.get(which));
                mChoiceDialog.dismiss();
            }
        })
        .setNegativeButton(android.R.string.cancel, null)
        .setTitle(R.string.weather_select_location)
        .show();
    }

    private CharSequence[] buildItemList(List<WeatherInfo.WeatherLocation> results) {
        boolean needCountry = false, needPostal = false;
        String countryId = results.get(0).countryId;
        HashSet<String> postalIds = new HashSet<String>();

        for (WeatherInfo.WeatherLocation result : results) {
            if (!TextUtils.equals(result.countryId, countryId)) {
                needCountry = true;
            }
            String postalId = result.countryId + "##" + result.city;
            if (postalIds.contains(postalId)) {
                needPostal = true;
            }
            postalIds.add(postalId);
            if (needPostal && needCountry) {
                break;
            }
        }

        int count = results.size();
        CharSequence[] items = new CharSequence[count];
        for (int i = 0; i < count; i++) {
            WeatherInfo.WeatherLocation result = results.get(i);
            StringBuilder builder = new StringBuilder();
            if (needPostal && result.postal != null) {
                builder.append(result.postal).append(" ");
            }
            builder.append(result.city);
            if (needCountry) {
                String country = result.country != null
                        ? result.country : result.countryId;
                builder.append(" (").append(country).append(")");
            }
            items[i] = builder.toString();
        }
        return items;
    }
}

