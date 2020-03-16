// Author this class - vladrevers

package org.omnirom.omnijaws;

import android.content.Context;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class DarkSkyProvider extends AbstractWeatherProvider {
    private static final String TAG = "DarkSkyProvider";

    private static final String URL_WEATHER =
            "https://api.darksky.net/forecast/%s/";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&isNameRequired=true";
    private static final String PART_COORDINATES =
            "%f,%f";
    private static final String PART_PARAMETERS =
            "?exclude=hourly,minutely,flags&units=%s&lang=%s";


    public DarkSkyProvider(Context context) {
        super(context);
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
                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();

                String city = result.getString("name");
                String area = result.getString("adminName1");

                location.id = String.format(Locale.US, PART_COORDINATES, result.getDouble("lat"), result.getDouble("lng"));
                location.city = city;
                location.countryId = city.equals(area) ? result.getString("countryName") : result.getString("countryName") + ", " + area;
                results.add(location);
            }

            return results;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return getAllWeather(coordinates, metric);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return getAllWeather(id, metric);
    }

    private WeatherInfo getAllWeather(String coordinates, boolean metric) {
        String units = metric ? "ca" : "us";
        String url = String.format(URL_WEATHER + coordinates + PART_PARAMETERS, getAPIKey(), units, getLanguage());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject weather = new JSONObject(response);
            JSONObject conditions = weather.optJSONObject("currently");
            ArrayList<DayForecast> forecasts = parseForecasts(weather.getJSONObject("daily").getJSONArray("data"), metric);

            String city = getNamePlace(coordinates);
            if (TextUtils.isEmpty(city)) {
                city = mContext.getResources().getString(R.string.omnijaws_city_unknown);
            }

            float wind = (float) conditions.getDouble("windSpeed");

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ coordinates,
                    /* cityId */ city,
                    /* condition */ conditions.getString("summary"),
                    /* conditionCode */ ICON_MAPPING.getOrDefault(conditions.getString("icon"), -1),
                    /* temperature */ (float) conditions.getDouble("temperature"),
                    /* humidity */ ((float) conditions.getDouble("humidity")) * 100,
                    /* wind */ wind,
                    /* windDir */ wind != 0 ? conditions.getInt("windBearing") : 0,
                    metric,
                    forecasts,
                    conditions.getLong("time") * 1000);

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (coordinates = " + coordinates + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>(5);
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }

        for (int i = 0; i < count && result.size() < 5; i++) {
            DayForecast item;
            try {
                JSONObject forecast = forecasts.getJSONObject(i);
                item = new DayForecast(
                        /* low */ (float) forecast.getDouble("temperatureMin"),
                        /* high */ (float) forecast.getDouble("temperatureMax"),
                        /* condition */ forecast.getString("summary"),
                        /* conditionCode */ ICON_MAPPING.getOrDefault(forecast.getString("icon"), -1),
                        String.valueOf(forecast.getLong("time")),
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

    private static String getLanguage() {
        String[] availableLang = {"ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "eo", "es", "et", "fi", "fr", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "ka", "kn", "ko", "kw", "lv", "ml", "mr", "nb", "nl", "no", "pa", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv", "ta", "te", "tet", "tr", "uk", "ur", "zh"};
        String sysLang = Locale.getDefault().getLanguage();

        return Arrays.asList(availableLang).contains(sysLang) ? sysLang : "en";
    }

    private static final HashMap<String, Integer> ICON_MAPPING = new HashMap<>();
    static {
        ICON_MAPPING.put("clear-day", 32);
        ICON_MAPPING.put("clear-night", 31);
        ICON_MAPPING.put("rain", 11);
        ICON_MAPPING.put("snow", 16);
        ICON_MAPPING.put("sleet", 18);
        ICON_MAPPING.put("wind", 24);
        ICON_MAPPING.put("fog", 20);
        ICON_MAPPING.put("cloudy", 26);
        ICON_MAPPING.put("partly-cloudy-day", 30);
        ICON_MAPPING.put("partly-cloudy-night", 29);

        ICON_MAPPING.put("hail", 17);
        ICON_MAPPING.put("thunderstorm", 4);
        ICON_MAPPING.put("tornado", 0);
    }

    private String getNamePlace(String coordinate) {
        int indexDivider = coordinate.indexOf(",");
        double latitude = Double.valueOf(coordinate.substring(0, indexDivider));
        double longitude = Double.valueOf(coordinate.substring(indexDivider + 1));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());

        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0) {
                return listAddresses.get(0).getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    private String getAPIKey() {
        return mContext.getResources().getString(R.string.darksky_api_key);
    }

    public boolean shouldRetry() {
        return false;
    }
}
