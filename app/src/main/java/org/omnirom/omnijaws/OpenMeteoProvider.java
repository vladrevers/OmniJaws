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

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class OpenMeteoProvider extends AbstractWeatherProvider {
    private static final String TAG = "OpenMeteoProvider";
    private static final String URL_WEATHER =
            "https://api.open-meteo.com/v1/forecast?";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&isNameRequired=true&username=omnijaws";
    private static final String URL_LOCALITY =
            "http://api.geonames.org/extendedFindNearbyJSON?lat=%f&lng=%f&lang=%s&username=omnijaws";
    private static final String PART_COORDINATES =
            "latitude=%f&longitude=%f";
    private static final String PART_PARAMETERS =
            "%s&hourly=relativehumidity_2m&daily=weathercode,temperature_2m_max,temperature_2m_min&current_weather=true&temperature_unit=%s&windspeed_unit=%s&timezone=%s&past_days=1&models=best_match,gfs_seamless";

    public OpenMeteoProvider(Context context) {
        super(context);
    }

    @Override
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
        return getWeather(coordinates, metric);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return getWeather(id, metric);
    }

    private WeatherInfo getWeather(String coordinates, boolean metric) {
        String tempUnit = metric ? "celsius" : "fahrenheit";
        String speedUnit = metric ? "kmh" : "mph";
        String timeZone = java.util.TimeZone.getDefault().getID();
        String url = String.format(Locale.US,URL_WEATHER + PART_PARAMETERS, coordinates, tempUnit, speedUnit, timeZone);
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONObject weather = new JSONObject(response).getJSONObject("current_weather");

            int weathercode = weather.getInt("weathercode");
            boolean isDay = weather.getInt("is_day") == 1;

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ coordinates,
                    /* cityId */ getNameLocality(coordinates),
                    /* condition */ getWeatherDescription(weathercode),
                    /* conditionCode */ getWeatherIcon(weathercode, isDay),
                    /* temperature */ (float) weather.getDouble("temperature"),
                    // Api: Possibly future inclusion humidity in current weather; may eliminate need for hourly forecast request.
                    /* humidity */ getCurrentHumidity(new JSONObject(response).getJSONObject("hourly")),
                    /* wind */ (float) weather.getDouble("windspeed"),
                    /* windDir */ weather.getInt("winddirection"),
                    metric,
                    parseForecasts(new JSONObject(response).getJSONObject("daily"), metric),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (coordinates = " + coordinates + ")", e);
        }

        return null;
    }

    private ArrayList<WeatherInfo.DayForecast> parseForecasts(JSONObject dailyForecasts, boolean metric) throws JSONException {
        ArrayList<WeatherInfo.DayForecast> result = new ArrayList<>(5);

        JSONArray timeJson = dailyForecasts.getJSONArray("time");
        JSONArray temperatureMinJson = dailyForecasts.getJSONArray("temperature_2m_min_best_match");
        JSONArray temperatureMaxJson = dailyForecasts.getJSONArray("temperature_2m_max_best_match");
        JSONArray weatherCodeJson = dailyForecasts.getJSONArray("weathercode_best_match");
        JSONArray altWeatherCodeJson = dailyForecasts.getJSONArray("weathercode_gfs_seamless");
        String currentDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());

        int startIndex = 1;
        if (currentDay.equals(timeJson.getString(0)))
            startIndex = 0;
        else if (currentDay.equals(timeJson.getString(2)))
            startIndex = 2;

        for (int i = startIndex; i < timeJson.length() && result.size() < 5; i++) {
            WeatherInfo.DayForecast item;
            int weatherCode = weatherCodeJson.getInt(i);
            if(weatherCode == 45 || weatherCode == 48)
                weatherCode = altWeatherCodeJson.getInt(i);

            try {
                item = new WeatherInfo.DayForecast(
                        /* low */ (float) temperatureMinJson.getDouble(i),
                        /* high */ (float) temperatureMaxJson.getDouble(i),
                        /* condition */ getWeatherDescription(weatherCode),
                        /* conditionCode */ getWeatherIcon(weatherCode, true),
                        timeJson.getString(i),
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

    private static float getCurrentHumidity(JSONObject hourlyJson) throws JSONException {
        String currentHour = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US).format(Calendar.getInstance().getTime());
        JSONArray hourlyTimes = hourlyJson.getJSONArray("time");
        JSONArray hourlyHumidity = hourlyJson.getJSONArray("relativehumidity_2m_best_match");

        int currentIndex = 36;
        for (int i = 0; i < hourlyTimes.length(); i++)
            if (hourlyTimes.getString(i).startsWith(currentHour)) {
                currentIndex = i;
                break;
            }

        return (float) hourlyHumidity.getDouble(currentIndex);
    }

    private static String getWeatherDescription(int code) {
        switch (code) {
            case 0:
                return "Clear sky";
            case 1:
                return "Mainly clear";
            case 2:
                return "Partly cloudy";
            case 3:
                return "Overcast";
            case 45:
                return "Fog";
            case 48:
                return "Depositing rime fog";
            case 51:
                return "Light intensity drizzle";
            case 53:
                return "Moderate intensity drizzle";
            case 55:
                return "Dense intensity drizzle";
            case 56:
                return "Light intensity freezing drizzle";
            case 57:
                return "Dense intensity freezing drizzle";
            case 61:
                return "Slight intensity rain";
            case 63:
                return "Moderate intensity rain";
            case 65:
                return "Heavy intensity rain";
            case 66:
                return "Light intensity freezing rain";
            case 67:
                return "Heavy intensity freezing rain";
            case 71:
                return "Slight intensity snowfall";
            case 73:
                return "Moderate intensity snowfall";
            case 75:
                return "Heavy intensity snowfall";
            case 77:
                return "Snow grains";
            case 80:
                return "Slight intensity rain showers";
            case 81:
                return "Moderate intensity rain showers";
            case 82:
                return "Violent intensity rain showers";
            case 85:
                return "Slight intensity snow showers";
            case 86:
                return "Heavy intensity snow showers";
            case 95:
                return "Slight or moderate thunderstorm";
            case 96:
                return "Thunderstorm with slight hail";
            case 99:
                return "Thunderstorm with heavy hail";
            default:
                return "Unknown";
        }
    }

    private static int getWeatherIcon(int code, boolean isDay) {
        switch (code) {
            case 0: // Clear sky
                return isDay ? 32 : 31;
            case 1: // Mainly clear
                return isDay ? 34 : 33;
            case 2: // Partly cloudy
                return isDay ? 30 : 29;
            case 3: // Overcast
                return 26;
            case 45: // Fog
            case 48: // Depositing rime fog
                return 20;
            case 51: // Light intensity drizzle
                return 9;
            case 53: // Moderate intensity drizzle
                return 9;
            case 55: // Dense intensity drizzle
                return 12;
            case 56: // Light intensity freezing drizzle
                return 8;
            case 57: // Dense intensity freezing drizzle
                return 8;
            case 61: // Slight intensity rain
                return 9;
            case 63: // Moderate intensity rain
                return 11;
            case 65: // Heavy intensity rain
                return 12;
            case 66: // Light intensity freezing rain
                return 10;
            case 67: // Heavy intensity freezing rain
                return 10;
            case 71: // Slight intensity snowfall
                return 14;
            case 73: // Moderate intensity snowfall
                return 16;
            case 75: // Heavy intensity snowfall
                return 43;
            case 77: // Snow grains
                return 16;
            case 80: // Slight intensity rain showers
                return 11;
            case 81: // Moderate intensity rain showers
                return 40;
            case 82: // Violent intensity rain showers
                return 40;
            case 85: // Slight intensity snow showers
                return 14;
            case 86: // Heavy intensity snow showers
                return 43;
            case 95: // Slight or moderate thunderstorm
                return 4;
            case 96: // Thunderstorm with slight hail
            case 99: // Thunderstorm with heavy hail
                return 38;
            default: // Unknown
                return -1;
        }
    }

    private String getNameLocality(String coordinates) {
        String city = null;

        if (Config.isCustomLocation(mContext))
            city = Config.getLocationName(mContext);

        if (TextUtils.isEmpty(city))
            city = getNameCoordinatesLocality(coordinates);

        if (TextUtils.isEmpty(city))
            city = mContext.getResources().getString(R.string.omnijaws_city_unknown);

        log(TAG, "getWeatherDataLocality = " + city);
        return city;
    }

    private String getNameCoordinatesLocality(String coordinate) {
        String city = null;
        double latitude = Double.valueOf(coordinate.substring(coordinate.indexOf("=") + 1, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.lastIndexOf("=") + 1));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());

        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0){
                Address a = listAddresses.get(0);
                city = TextUtils.isEmpty(a.getLocality()) ? a.getAdminArea() : a.getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!TextUtils.isEmpty(city)) {
            return city; // from google
        }

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
                city = address.getString("placename");
                String area = address.getString("adminName2");
                if (!TextUtils.isEmpty(city) || !TextUtils.isEmpty(area)) {
                    return !TextUtils.isEmpty(city) ? city : area;
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

    @Override
    public boolean shouldRetry() {
        return false;
    }
}
