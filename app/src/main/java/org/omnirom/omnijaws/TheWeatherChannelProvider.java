// Author this class - vladrevers

package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class TheWeatherChannelProvider extends AbstractWeatherProvider {
    private static final String TAG = "TheWeatherChannelProvider";

    private static final String URL_PLACES =
            "https://api.weather.com/v3/location/search?query=%s&locationType=city&language=%s&format=json&apiKey=%s";
    private static final String URL_INFO_PLACES =
            "https://api.weather.com/v3/location/point?%s&language=%s&format=json&apiKey=%s";
    private static final String URL_CURRENT =
            "http://api.weather.com/v3/wx/observations/current?%s&language=%s&format=json&units=%s&apiKey=%s";
    private static final String URL_FORECAST =
            "http://api.weather.com/v3/wx/forecast/daily/7day?%s&language=%s&format=json&units=%s&apiKey=%s";

    public TheWeatherChannelProvider(Context context) {
        super(context);
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {
        String url = String.format(URL_PLACES, Uri.encode(input), getLanguage(), getAPIKey());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response).getJSONObject("location");
            JSONArray resultDistrict = jsonResults.getJSONArray("adminDistrict");
            JSONArray resultName = jsonResults.getJSONArray("displayName");
            JSONArray resultCountry = jsonResults.getJSONArray("country");
            JSONArray resultID = jsonResults.getJSONArray("placeId");
            int count = resultID.length();
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();

                String city = resultName.getString(i);
                String area = resultDistrict.getString(i);

                location.id = resultID.getString(i);
                location.city = city;
                location.countryId = resultCountry.getString(i) + (city.equals(area) ? "" : ", " + area);
                results.add(location);
            }

            return results;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String partLocation = String.format(Locale.US, "geocode=%f,%f", location.getLatitude(), location.getLongitude());
        return getAnyWeather(partLocation, metric);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        String partLocation = "placeid=" + id;
        return getAnyWeather(partLocation, metric);
    }

    private WeatherInfo getAnyWeather(String partLocation, boolean metric) {
        String units = metric ? "m" : "e";
        String url = String.format(URL_CURRENT, partLocation, getLanguage(), units, getAPIKey());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject current = new JSONObject(response);
            String[] infoLocation = getInfoLocation(partLocation);
            int temperature = current.getInt("temperature");

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ infoLocation[0],
                    /* cityId */ infoLocation[1],
                    /* condition */ current.optString("cloudCoverPhrase"),
                    /* conditionCode */ convertWeatherCode(current.getInt("iconCode")),
                    /* temperature */ temperature,
                    /* humidity */ current.getInt("relativeHumidity"),
                    /* wind */ (float) current.getDouble("windSpeed"),
                    /* windDir */ current.optInt("windDirection"),
                    metric,
                    getForecasts(partLocation, metric, temperature),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (" + partLocation + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> getForecasts(String partLocation, boolean metric, int defTemp) throws JSONException {
        String units = metric ? "m" : "e";
        String url = String.format(URL_FORECAST, partLocation, getLanguage(), units, getAPIKey());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        ArrayList<DayForecast> result = new ArrayList<>(5);
        JSONObject forecasts = new JSONObject(response);
        JSONArray resultMaxTemp = forecasts.getJSONArray("temperatureMax");
        JSONArray resultMinTemp = forecasts.getJSONArray("temperatureMin");
        JSONArray resultIcon = forecasts.getJSONArray("daypart").getJSONObject(0).getJSONArray("iconCode");
        JSONArray resultCondition = forecasts.getJSONArray("daypart").getJSONObject(0).getJSONArray("wxPhraseLong");

        boolean todayOnlyEveningInfo = forecasts.getJSONArray("daypart").getJSONObject(0).getJSONArray("dayOrNight").isNull(0);
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());
        int startIndex = forecasts.getJSONArray("validTimeLocal").getString(0).contains(yesterday) ? 1 : 0;

        for (int i = startIndex; i < resultMaxTemp.length() && result.size() < 5; i++) {
            DayForecast item;

            int dayIndex = i * 2;
            if (dayIndex == 0 && todayOnlyEveningInfo) {
                dayIndex = 1;
            }

            try {
                item = new DayForecast(
                        /* low */ i == 0 ? resultMinTemp.optInt(i, defTemp) : resultMinTemp.getInt(i),
                        /* high */ i == 0 ? resultMaxTemp.optInt(i, defTemp) : resultMaxTemp.getInt(i),
                        /* condition */ resultCondition.optString(dayIndex),
                        /* conditionCode */ convertWeatherCode(resultIcon.getInt(dayIndex)),
                        forecasts.getJSONArray("dayOfWeek").getString(i),
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
                DayForecast item = new WeatherInfo.DayForecast(
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

    private String[] getInfoLocation(String partLocation) {
        String defaultCityName = mContext.getResources().getString(R.string.omnijaws_city_unknown);
        String defaultCityId = partLocation.contains("placeid") ? partLocation.substring(8) : "-1";
        String[] keyAndName = {defaultCityId, defaultCityName};
        String url = String.format(URL_INFO_PLACES, partLocation, getLanguage(), getAPIKey());
        String response = retrieve(url);

        if (response == null) {
            return keyAndName;
        }
        log(TAG, "Location Information URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response).getJSONObject("location");
            keyAndName[0] = jsonResults.isNull("placeId") ? defaultCityId : jsonResults.getString("placeId");
            keyAndName[1] = jsonResults.isNull("city") ? defaultCityName : jsonResults.getString("city");
        } catch (JSONException e) {
            Log.e(TAG, "Received malformed location info ("
                    + partLocation + ", lang=" + getLanguage() + ")", e);
        }

        return keyAndName;
    }

    private static String getLanguage() {
        Locale locale = Locale.getDefault();
        if (locale != null) {
            return locale.getLanguage() + "-" + locale.getCountry();
        }
        return "en-US";
    }

    /* Thanks Chronus(app) */
    private static int convertWeatherCode(int iconCode)
    {
        switch (iconCode) {
            case 39:
                return 40;
            case 40:
                return 12;
            case 41:
                return 42;
            case 42:
                return 41;
            case 44:
                return -1;
            default:
                return iconCode;
        }
    }

    private String getAPIKey() {
        return mContext.getResources().getString(R.string.twc_api_key);
    }

    public boolean shouldRetry() {
        return false;
    }
}
