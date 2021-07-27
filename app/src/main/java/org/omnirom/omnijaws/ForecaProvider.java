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
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class ForecaProvider extends AbstractWeatherProvider {
    private static final String TAG = "ForecaProvider";

    private static final String URL_API_SERVER = "https://api.foreca.net";
    private static final String URL_PART_SEARCH = "/locations/search/%s.json?lang=%s";
    private static final String URL_PART_COORDINATES = "/locations/%f,%f.json"; // lon, lat
    private static final String URL_PART_CURRENT = "/data/recent/%s.json";
    private static final String URL_PART_FORECAST = "/data/daily/%s.json";

    public ForecaProvider(Context context) {
        super(context);
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {
        String url = String.format(URL_API_SERVER + URL_PART_SEARCH,
                Uri.encode(input), getLanguage());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray jsonResults = new JSONObject(response).getJSONArray("results");
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>(jsonResults.length());

            for (int i = 0; i < jsonResults.length(); i++) {
                JSONObject result = jsonResults.getJSONObject(i);
                String city = result.getString("name");
                String country = result.getString("countryName");

                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();
                location.id = result.getString("id");
                location.city = city;
                location.countryId = formatCountry(city, country);

                results.add(location);
            }

            return results;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String urlPlaces = String.format(Locale.US, URL_API_SERVER + URL_PART_COORDINATES,
                location.getLongitude(), location.getLatitude());

        String response = retrieve(urlPlaces);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + urlPlaces + " returning a response of " + response);

        try {
            String id = new JSONObject(response).getString("id");
            return getCustomWeather(id, metric);
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location id", e);
        }

        return null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        String currentUrl = String.format(URL_API_SERVER + URL_PART_CURRENT, id);
        String currentResponse = retrieve(currentUrl);
        if (currentResponse == null) {
            return null;
        }
        log(TAG, "Current URL = " + currentUrl + " returning a response of " + currentResponse);

        String forecastUrl = String.format(URL_API_SERVER + URL_PART_FORECAST, id);
        String forecastResponse = retrieve(forecastUrl);
        if (forecastResponse == null) {
            return null;
        }
        log(TAG, "Forecasts URL = " + forecastUrl + " returning a response of " + forecastResponse);

        try {
            JSONObject current = new JSONObject(currentResponse).getJSONObject(id);
            JSONArray forecasts = new JSONObject(forecastResponse).getJSONArray("data");

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ id,
                    /* city */ getLocationName(id),
                    /* condition */ "none",
                    /* conditionCode */ mapIconToCode(current.getString("symb")),
                    /* temperature */ convertTemperature(current.getDouble("temp"), metric),
                    /* humidity */ current.getInt("rhum"),
                    /* wind */ convertWindSpeed(current.getInt("winds"), metric),
                    /* windDir */ current.optInt("windd"),
                    metric,
                    parseForecasts(forecasts, metric),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (JSONException e) {
             Log.w(TAG, "Received malformed weather data (" + id + ")", e);
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

                if (i == 0 && !checkYesterday(forecast.getString("date"))) {
                    // skip if yesterday
                    continue;
                }

                item = new WeatherInfo.DayForecast(
                        /* low */ convertTemperature(forecast.getInt("tmin"), metric),
                        /* high */ convertTemperature(forecast.getInt("tmax"), metric),
                        /* condition */ forecast.optString("symbtxt"),
                        /* conditionCode */ mapIconToCode(forecast.getString("symb")),
                        forecast.getString("date"),
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

    private static String getLanguage() {
        String currentLang = Locale.getDefault().getLanguage();
        String[] availableLang = {
                "en", "bg", "cs", "da", "de", "et", "el", "es", "fa", "fr", "hr",
                "it", "lv", "hu", "nl", "pl", "pt", "ro", "ru", "sk", "sv", "tr", "uk"
        };
        return Arrays.asList(availableLang).contains(currentLang) ? currentLang : "en";
    }

    private static String formatCountry(String city, String country) {
        if (country.contains(city) && country.contains(", ")) {
            int indexFirstComma = country.indexOf(',');
            String firstPartCountry = country.substring(0, indexFirstComma);
            if (firstPartCountry.contains(city))
                return country.substring(indexFirstComma + 2);
        }
        return country;
    }

    private static final HashMap<String, Integer> ICON_MAPPING = new HashMap<>();
    static {
        ICON_MAPPING.put("000", 32); // 31
        ICON_MAPPING.put("100", 34); // 33
        ICON_MAPPING.put("200", 30); // 29
        ICON_MAPPING.put("300", 28); // 27
        ICON_MAPPING.put("400", 26);
        ICON_MAPPING.put("500", 21);
        ICON_MAPPING.put("600", 20);
        ICON_MAPPING.put("210", 40); // 47
        ICON_MAPPING.put("310", 40); // 47
        ICON_MAPPING.put("410", 9);
        ICON_MAPPING.put("220", 40);
        ICON_MAPPING.put("320", 40);
        ICON_MAPPING.put("420", 11);
        ICON_MAPPING.put("430", 12);
        ICON_MAPPING.put("240", 38);
        ICON_MAPPING.put("340", 38);
        ICON_MAPPING.put("440", 4);
        ICON_MAPPING.put("211", 18);
        ICON_MAPPING.put("311", 18);
        ICON_MAPPING.put("411", 18);
        ICON_MAPPING.put("221", 18);
        ICON_MAPPING.put("321", 18);
        ICON_MAPPING.put("421", 18);
        ICON_MAPPING.put("431", 18);
        ICON_MAPPING.put("212", 14); // 46
        ICON_MAPPING.put("312", 14); // 46
        ICON_MAPPING.put("412", 14);
        ICON_MAPPING.put("222", 41);
        ICON_MAPPING.put("322", 41);
        ICON_MAPPING.put("422", 41);
        ICON_MAPPING.put("432", 41);
    }

    private static int mapIconToCode(String weatherSymbol) {
        boolean isNight = weatherSymbol.startsWith("n");
        int code = ICON_MAPPING.getOrDefault(weatherSymbol.substring(1), -1);

        if (isNight) {
            switch (code) {
                case 28:
                case 30:
                case 32:
                case 34:
                    code -= 1;
                    break;
                case 40:
                    code = 47;
                    break;
                case 14:
                    code = 46;
                    break;
            }
        }

        return code;
    }

    private static float convertTemperature(double value, boolean metric) {
        if (!metric) {
            value = (value * 1.8) + 32;
        }
        return (float) value;
    }

    private static float convertWindSpeed(int valueMs, boolean metric) {
        return valueMs * (metric ? 3.6f : 2.2369362920544f);
    }

    private String getLocationName(String id) {
        String unknown = mContext.getResources().getString(R.string.omnijaws_city_unknown);
        String url = URL_API_SERVER + "/locations/" + id + ".json?lang=" + getLanguage();
        String response = retrieve(url);
        if (response == null) {
            return unknown;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response);
            return jsonResults.isNull("name")? unknown : jsonResults.getString("name");
        } catch (JSONException e) {
            return unknown;
        }
    }

    // if yesterday return false
    private static boolean checkYesterday(String valueDate)
    {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
        return !yesterday.equals(valueDate);
    }

    public boolean shouldRetry() {
        return false;
    }
}