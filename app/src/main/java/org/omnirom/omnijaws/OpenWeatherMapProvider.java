/*
 * Copyright (C) 2013 The CyanogenMod Project
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

import android.content.Context;
import android.content.res.Resources;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OpenWeatherMapProvider extends AbstractWeatherProvider {
    private static final String TAG = "OpenWeatherMapProvider";

    private static final int FORECAST_DAYS = 5;
    private static final String PART_COORDINATES = "lat=%f&lon=%f";
    private static final String URL_WEATHER =
            "https://api.openweathermap.org/data/2.5/onecall?%s&mode=json&units=%s&lang=%s&cnt=" + FORECAST_DAYS + "&appid=%s";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&maxRows=20";
    private static final String URL_LOCALITY =
            "http://api.geonames.org/extendedFindNearbyJSON?lat=%f&lng=%f&lang=%s&username=omnijaws";
    private static final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private final List<String> mKeys = new ArrayList<>();
    private final boolean mHasAPIKey;
    private int mRequestNumber;

    public OpenWeatherMapProvider(Context context) {
        super(context);
        loadKeys();
        mHasAPIKey = getAPIKey() != null;
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {
        String lang = Locale.getDefault().getLanguage().replaceFirst("_", "-");
        String url = String.format(URL_PLACES, Uri.encode(input), lang);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray jsonResults = new JSONObject(response).getJSONArray("geonames");
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>(jsonResults.length());
            int count = jsonResults.length();

            for (int i = 0; i < count; i++) {
                JSONObject result = jsonResults.getJSONObject(i);
                String city = result.getString("name");

                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();
                String area = result.getString("adminName1");
                location.id = String.format(Locale.US, PART_COORDINATES, result.getDouble("lat"), result.getDouble("lng"));
                location.city = city;
                if (!TextUtils.isEmpty(area)) {
                    location.countryId = city.equals(area) ? result.getString("countryName") : result.getString("countryName") + ", " + area;
                } else {
                    location.countryId = result.getString("countryName");
                }
                results.add(location);
            }

            return results;
        } catch (Exception e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return handleWeatherRequest(id, metric);
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return handleWeatherRequest(coordinates, metric);
    }

    private WeatherInfo handleWeatherRequest(String selection, boolean metric) {
        if (!mHasAPIKey) {
            return null;
        }
        mRequestNumber++;
        String units = metric ? "metric" : "imperial";
        String locale = getLanguageCode();
        String conditionUrl = String.format(Locale.US, URL_WEATHER, selection, units, locale, getAPIKey());
        String conditionResponse = retrieve(conditionUrl);
        if (conditionResponse == null) {
            return null;
        }
        log(TAG, "Condition URL = " + conditionUrl + " returning a response of " + conditionResponse);

        try {
            JSONObject conditions = new JSONObject(conditionResponse);
            JSONObject conditionData = conditions.getJSONObject("current");
            JSONObject weather = conditionData.getJSONArray("weather").getJSONObject(0);
            ArrayList<DayForecast> forecasts =
                    parseForecasts(conditions.getJSONArray("daily"), metric);
            float windSpeed = (float) conditionData.getDouble("wind_speed");
            if (metric) {
                // speeds are in m/s so convert to our common metric unit km/h
                windSpeed *= 3.6f;
            }

            String city = getWeatherDataLocality(selection);

            WeatherInfo w = new WeatherInfo(mContext, selection, city,
                    /* condition */ weather.getString("main"),
                    /* conditionCode */ mapConditionIconToCode(weather.getInt("id")),
                    /* temperature */ sanitizeTemperature(conditionData.getDouble("temp"), metric),
                    /* humidity */ (float) conditionData.getDouble("humidity"),
                    /* wind */ windSpeed,
                    /* windDir */ conditionData.has("WIND_deg") ? conditionData.getInt("wind_deg") : 0,
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (selection = " + selection
                    + ", lang = " + locale + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>();
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }
        for (int i = 0; i < count; i++) {
            String day = getDay(i);
            DayForecast item = null;
            try {
                JSONObject forecast = forecasts.getJSONObject(i);
                JSONObject conditionData = forecast.getJSONObject("temp");
                JSONObject data = forecast.getJSONArray("weather").getJSONObject(0);
                item = new DayForecast(
                        /* low */ sanitizeTemperature(conditionData.getDouble("min"), metric),
                        /* high */ sanitizeTemperature(conditionData.getDouble("max"), metric),
                        /* condition */ data.getString("main"),
                        /* conditionCode */ mapConditionIconToCode(data.getInt("id")),
                        day,
                        metric);
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast for day " + i + " creating dummy", e);
                item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
            }
            result.add(item);
        }
        // clients assume there are 5  entries - so fill with dummy if needed
        if (result.size() < 5) {
            for (int i = result.size(); i < 5; i++) {
                Log.w(TAG, "Missing forecast for day " + i + " creating dummy");
                DayForecast item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
                result.add(item);
            }
        }
        return result;
    }

    // OpenWeatherMap sometimes returns temperatures in Kelvin even if we ask it
    // for deg C or deg F. Detect this and convert accordingly.
    private static float sanitizeTemperature(double value, boolean metric) {
        // threshold chosen to work for both C and F. 170 deg F is hotter
        // than the hottest place on earth.
        if (value > 170) {
            // K -> deg C
            value -= 273.15;
            if (!metric) {
                // deg C -> deg F
                value = (value * 1.8) + 32;
            }
        }
        return (float) value;
    }

    private static final HashMap<String, String> LANGUAGE_CODE_MAPPING = new HashMap<>();
    static {
        LANGUAGE_CODE_MAPPING.put("bg-", "bg");
        LANGUAGE_CODE_MAPPING.put("de-", "de");
        LANGUAGE_CODE_MAPPING.put("es-", "sp");
        LANGUAGE_CODE_MAPPING.put("fi-", "fi");
        LANGUAGE_CODE_MAPPING.put("fr-", "fr");
        LANGUAGE_CODE_MAPPING.put("it-", "it");
        LANGUAGE_CODE_MAPPING.put("nl-", "nl");
        LANGUAGE_CODE_MAPPING.put("pl-", "pl");
        LANGUAGE_CODE_MAPPING.put("pt-", "pt");
        LANGUAGE_CODE_MAPPING.put("ro-", "ro");
        LANGUAGE_CODE_MAPPING.put("ru-", "ru");
        LANGUAGE_CODE_MAPPING.put("se-", "se");
        LANGUAGE_CODE_MAPPING.put("tr-", "tr");
        LANGUAGE_CODE_MAPPING.put("uk-", "ua");
        LANGUAGE_CODE_MAPPING.put("zh-CN", "zh_cn");
        LANGUAGE_CODE_MAPPING.put("zh-TW", "zh_tw");
    }
    private String getLanguageCode() {
        Locale locale = mContext.getResources().getConfiguration().locale;
        String selector = locale.getLanguage() + "-" + locale.getCountry();

        for (Map.Entry<String, String> entry : LANGUAGE_CODE_MAPPING.entrySet()) {
            if (selector.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "en";
    }

    private int mapConditionIconToCode(int conditionId) {

        // First, use condition ID for specific cases
        switch (conditionId) {
            // Thunderstorms
            case 202:   // thunderstorm with heavy rain
            case 232:   // thunderstorm with heavy drizzle
            case 211:   // thunderstorm
                return 4;
            case 212:   // heavy thunderstorm
                return 3;
            case 221:   // ragged thunderstorm
            case 231:   // thunderstorm with drizzle
            case 201:   // thunderstorm with rain
                return 38;
            case 230:   // thunderstorm with light drizzle
            case 200:   // thunderstorm with light rain
            case 210:   // light thunderstorm
                return 37;

            // Drizzle
            case 300:    // light intensity drizzle
            case 301:    // drizzle
            case 302:    // heavy intensity drizzle
            case 310:    // light intensity drizzle rain
            case 311:    // drizzle rain
            case 312:    // heavy intensity drizzle rain
            case 313:    // shower rain and drizzle
            case 314:    // heavy shower rain and drizzle
            case 321:    // shower drizzle
                return 9;

            // Rain
            case 500:    // light rain
            case 501:    // moderate rain
            case 520:    // light intensity shower rain
            case 521:    // shower rain
            case 531:    // ragged shower rain
                return 11;
            case 502:    // heavy intensity rain
            case 503:    // very heavy rain
            case 504:    // extreme rain
            case 522:    // heavy intensity shower rain
                return 12;
            case 511:    // freezing rain
                return 10;

            // Snow
            case 600: case 620: return 14; // light snow
            case 601: case 621: return 16; // snow
            case 602: case 622: return 41; // heavy snow
            case 611: case 612: return 18; // sleet
            case 615: case 616: return 5;  // rain and snow

            // Atmosphere
            case 741:    // fog
                return 20;
            case 711:    // smoke
            case 762:    // volcanic ash
                return 22;
            case 701:    // mist
            case 721:    // haze
                return 21;
            case 731:    // sand/dust whirls
            case 751:    // sand
            case 761:    // dust
                return 19;
            case 771:    // squalls
                return 23;
            case 781:    // tornado
                return 0;

            // clouds
            case 800:     // clear sky
                return 32;
            case 801:     // few clouds
                return 34;
            case 802:     // scattered clouds
                return 28;
            case 803:     // broken clouds
            case 804:     // overcast clouds
                return 30;

            // Extreme
            case 900: return 0;  // tornado
            case 901: return 1;  // tropical storm
            case 902: return 2;  // hurricane
            case 903: return 25; // cold
            case 904: return 36; // hot
            case 905: return 24; // windy
            case 906: return 17; // hail
        }

        return -1;
    }

    protected String getWeatherDataLocality(String coordinates) {
        String city;
        if (Config.isCustomLocation(mContext)) {
            city = Config.getLocationName(mContext);
            if (TextUtils.isEmpty(city)) {
                city = getCoordinatesLocality(coordinates);
            }
        } else {
            city = getCoordinatesLocality(coordinates);
        }
        if (TextUtils.isEmpty(city)) {
            city = mContext.getResources().getString(R.string.omnijaws_city_unknown);
        }
        log(TAG, "getWeatherDataLocality = " + city);
        return city;
    }

    private String getCoordinatesLocality(String coordinate) {
        String cityGoogle = getCoordinatesLocalityWithGoogle(coordinate);

        if (!TextUtils.isEmpty(cityGoogle)) {
            return cityGoogle;
        }

        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Log.d(TAG, "getCoordinatesLocality " + latitude + " " + longitude);

        String lang = Locale.getDefault().getLanguage().replaceFirst("_", "-");
        String url = String.format(Locale.US, URL_LOCALITY, latitude, longitude, lang);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response);
            if (jsonResults.has("address")) {
                JSONObject address = jsonResults.getJSONObject("address");
                String city = address.getString("placename");
                String area = address.getString("adminName2");
                if (!TextUtils.isEmpty(city)) {
                    return city;
                }
                if (!TextUtils.isEmpty(area)) {
                    return area;
                }
            } else if (jsonResults.has("geonames")) {
                JSONArray jsonResultsArray = jsonResults.getJSONArray("geonames");
                int count = jsonResultsArray.length();

                for (int i = count - 1; i >= 0; i--) {
                    JSONObject geoname = jsonResultsArray.getJSONObject(i);
                    String name = geoname.getString("name");

                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Received malformed location data (coordinate=" + coordinate + ")", e);
        }
        return null;
    }

    private String getCoordinatesLocalityWithGoogle(String coordinate) {
        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());
        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0){
                Address a = listAddresses.get(0);
                return TextUtils.isEmpty(a.getLocality()) ? a.getAdminArea() : a.getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getDay(int i) {
        Calendar calendar = Calendar.getInstance();
        if (i > 0) {
            calendar.add(Calendar.DATE, i);
        }
        return dayFormat.format(calendar.getTime());
    }

    private void loadKeys() {
        try {
            String key = mContext.getResources().getString(R.string.owm_api_key);
            if (!TextUtils.isEmpty(key)) {
                mKeys.add(key);
            }
        } catch (Resources.NotFoundException e) {
        }
    }

    private String getAPIKey() {
        if (mKeys.size() > 0) {
            int key = mRequestNumber % mKeys.size();
            log(TAG, "use API key = " + key);
            return mKeys.get(key);
        }
        try {
            return mContext.getResources().getString(R.string.owm_api_key);
        } catch (Resources.NotFoundException e) {
        }
        return null;
    }

    public boolean shouldRetry() {
        return false;
    }
}