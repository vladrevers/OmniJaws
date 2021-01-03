// Author this class - vladrevers

package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class WeatherbitProvider extends AbstractWeatherProvider {
    private static final String TAG = "WeatherbitProvider";

    private static final String URL_CURRENT =
            "https://api.weatherbit.io/v2.0/current?units=%s&lang=%s&key=%s&";
    private static final String URL_FORECAST =
            "https://api.weatherbit.io/v2.0/forecast/daily?&days=6&units=%s&lang=%s&key=%s&";
    private static final String PART_COORDINATES =
            "lat=%f&lon=%f";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&isNameRequired=true";

    public WeatherbitProvider(Context context) {
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

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return getAllWeather(id, metric);
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return getAllWeather(coordinates, metric);
    }

    private WeatherInfo getAllWeather(String coordinates, boolean metric) {
        String units = metric ? "M" : "I";
        String lang = Locale.getDefault().getLanguage();

        String currentUrl = String.format(URL_CURRENT, units, lang, getAPIKey()) + coordinates;
        String currentResponse = retrieve(currentUrl);
        if (currentResponse == null) {
            return null;
        }
        log(TAG, "Current URL = " + currentUrl + " returning a response of " + currentResponse);

        String forecastUrl = String.format(URL_FORECAST, units, lang, getAPIKey()) + coordinates;
        String forecastResponse = retrieve(forecastUrl);
        if (forecastResponse == null) {
            return null;
        }
        log(TAG, "Forecasts URL = " + forecastUrl + " returning a response of " + forecastResponse);

        try {
            JSONObject current = new JSONObject(currentResponse).getJSONArray("data").getJSONObject(0);
            JSONObject weather = current.getJSONObject("weather");
            JSONArray forecasts = new JSONObject(forecastResponse).getJSONArray("data");
            String weatherIcon = weather.getString("icon");
            int weatherCode = mapIconToCode(weatherIcon);

            // Check Available Night Icon
            if(weatherIcon.endsWith("n") && (weatherCode == 30 || weatherCode == 32 || weatherCode == 34)) {
                weatherCode -= 1;
            }

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ coordinates,
                    /* city */ current.getString("city_name"),
                    /* condition */ weather.optString("description"),
                    /* conditionCode */ weatherCode,
                    /* temperature */ current.getInt("temp"),
                    /* humidity */ current.getInt("rh"),
                    /* wind */ convertWind(current.getDouble("wind_spd"), metric),
                    /* windDir */ current.optInt("wind_dir"),
                    metric,
                    parseForecasts(forecasts, metric),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (" + coordinates + ")", e);
        }

        return null;
    }

    private ArrayList<WeatherInfo.DayForecast> parseForecasts(JSONArray forecasts, boolean metric) throws JSONException {
        ArrayList<WeatherInfo.DayForecast> result = new ArrayList<>(5);
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }

        for (int i = 0; i < count && result.size() < 5; i++) {
            WeatherInfo.DayForecast item;
            try {
                JSONObject forecast = forecasts.getJSONObject(i);

                if (i == 0 && !checkYesterday(forecast.getString("valid_date"))) {
                    // skip if yesterday
                    continue;
                }

                JSONObject weather = forecast.getJSONObject("weather");
                item = new WeatherInfo.DayForecast(
                        /* low */ (int)Math.round(forecast.getDouble("min_temp")),
                        /* high */ (int)Math.round(forecast.getDouble("max_temp")),
                        /* condition */ weather.optString("description"),
                        /* conditionCode */ mapIconToCode(weather.getString("icon")),
                        forecast.getString("datetime"),
                        metric);
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast for day " + i + " creating dummy", e);
                item = new WeatherInfo.DayForecast(
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
                WeatherInfo.DayForecast item = new WeatherInfo.DayForecast(
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

    private static final HashMap<String, Integer> ICON_MAPPING = new HashMap<>();
    static {
        ICON_MAPPING.put("t01", 4);
        ICON_MAPPING.put("t02", 4);
        ICON_MAPPING.put("t03", 4);
        ICON_MAPPING.put("t04", 3);
        ICON_MAPPING.put("t05", 3);
        ICON_MAPPING.put("d01", 9);
        ICON_MAPPING.put("d02", 9);
        ICON_MAPPING.put("d03", 9);
        ICON_MAPPING.put("r01", 11);
        ICON_MAPPING.put("r02", 12);
        ICON_MAPPING.put("r03", 12);
        ICON_MAPPING.put("f01", 10);
        ICON_MAPPING.put("r04", 40);
        ICON_MAPPING.put("r05", 40);
        ICON_MAPPING.put("r06", 40);
        ICON_MAPPING.put("s01", 14);
        ICON_MAPPING.put("s02", 16);
        ICON_MAPPING.put("s03", 43);
        ICON_MAPPING.put("s04", 5);
        ICON_MAPPING.put("s05", 18);
        ICON_MAPPING.put("s06", 13);
        ICON_MAPPING.put("a01", 20);
        ICON_MAPPING.put("a02", 22);
        ICON_MAPPING.put("a03", 21);
        ICON_MAPPING.put("a04", 19);
        ICON_MAPPING.put("a05", 20);
        ICON_MAPPING.put("a06", 20);
        ICON_MAPPING.put("c01", 32);
        ICON_MAPPING.put("c02", 30);
        ICON_MAPPING.put("c03", 28);
        ICON_MAPPING.put("c04", 26);
        ICON_MAPPING.put("u00", -1);
    }

    private static int mapIconToCode(String weatherIcon) {
        String withoutDayNight = weatherIcon.substring(0, weatherIcon.length() - 1);
        return ICON_MAPPING.getOrDefault(withoutDayNight, -1);
    }

    // if yesterday return false
    private static boolean checkYesterday(String valueDate)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
        return !yesterday.equals(valueDate);
    }

    // if metric covert m/s to km/h
    private static float convertWind(double value, boolean metric) {
        return (float) (metric ? value * 3.6 : value);
    }

    private String getAPIKey() {
        return mContext.getResources().getString(R.string.wbit_api_key);
    }

    public boolean shouldRetry() {
        return false;
    }
}
