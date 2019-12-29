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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class AccuWeatherProvider extends AbstractWeatherProvider {
    private static final String TAG = "AccuWeatherProvider";

    private static final String URL_LOCATION =
            "http://api.accuweather.com/locations/v1/cities/translate.json?q=%s&apikey=%s&language=%s";
    private static final String URL_PLACES =
            "http://api.accuweather.com/locations/v1/cities/geoposition/search.json?q=%f,%f&apikey=%s&language=%s";
    private static final String URL_WEATHER =
            "http://api.accuweather.com/currentconditions/v1/%s?apikey=%s&language=%s&details=true";
    private static final String URL_FORECAST =
            "http://api.accuweather.com/forecasts/v1/daily/5day/%s?apikey=%s&language=%s";
    private static final String URL_LOCATION_INFO =
            "http://api.accuweather.com/locations/v1/%s?apikey=%s&language=%s";


    public AccuWeatherProvider(Context context) {
        super(context);
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {
        String url = String.format(URL_LOCATION, Uri.encode(input), getAPIKey(), getLanguage());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray jsonResults = new JSONArray(response);
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>(jsonResults.length());
            int count = jsonResults.length();

            for (int i = 0; i < count; i++) {
                JSONObject result = jsonResults.getJSONObject(i);
                WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();

                String city = result.getString("LocalizedName");
                String area = result.getJSONObject("AdministrativeArea").getString("LocalizedName");

                location.id = result.getString("Key");
                location.city = city;
                location.countryId = city.equals(area) ? result.getJSONObject("Country").getString("LocalizedName") : result.getJSONObject("Country").getString("LocalizedName") + ", " + area;
                results.add(location);
            }

            return results;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String url = String.format(URL_PLACES, location.getLatitude(), location.getLongitude(), getAPIKey(), getLanguage());
        String response = retrieve(url);
        if (response == null) {
            return null;
        }

        try {
            JSONObject jsonResults = new JSONObject(response);
            String key = jsonResults.getString("Key");
            return getCustomWeather(key, metric);
        } catch (JSONException e) {
            Log.e(TAG, "Received malformed placefinder data (location="
                    + location + ", lang=" + getLanguage() + ")", e);
        }

        return null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        String units = metric ? "Metric" : "Imperial";
        String[] infoLocation = getInfoLocation(id);
        String conditionUrl = String.format(URL_WEATHER, id, getAPIKey(), getLanguage());
        String conditionResponse = retrieve(conditionUrl);
        if (conditionResponse == null) {
            return null;
        }
        log(TAG, "Condition URL = " + conditionUrl + " returning a response of " + conditionResponse);

        String forecastUrl = String.format(URL_FORECAST, id, getAPIKey(), getLanguage());
        String forecastResponse = retrieve(forecastUrl);
        if (forecastResponse == null) {
            return null;
        }
        log(TAG, "Forcast URL = " + forecastUrl + " returning a response of " + forecastResponse);

        try {
            JSONObject weather = new JSONArray(conditionResponse).getJSONObject(0);
            JSONObject windData = weather.getJSONObject("Wind");
            ArrayList<DayForecast> forecasts =
                    parseForecasts(new JSONObject(forecastResponse).getJSONArray("DailyForecasts"), metric);

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ infoLocation[0],
                    /* cityId */ infoLocation[1],
                    /* condition */ weather.getString("WeatherText"),
                    /* conditionCode */ mapWeatherIconToCode(weather.getInt("WeatherIcon")),
                    /* temperature */ (float) weather.getJSONObject("Temperature").getJSONObject(units).getDouble("Value"),
                    /* humidity */ (float) weather.getDouble("RelativeHumidity"),
                    /* wind */ (float) windData.getJSONObject("Speed").getJSONObject(units).getDouble("Value"),
                    /* windDir */ windData.getJSONObject("Direction").getInt("Degrees"),
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (id = " + id
                    + ", lang = " + getLanguage() + ")", e);
        }

        return null;
    }

    private String[] getInfoLocation(String id) {
        String[] keyAndName = {id, "Unknown"};
        String url = String.format(URL_LOCATION_INFO, id, getAPIKey(), getLanguage());
        String response = retrieve(url);

        if (response == null) {
            return keyAndName;
        }
        log(TAG, "Location Information URL = " + url + " returning a response of " + response);

        try {
            JSONObject jsonResults = new JSONObject(response);
            keyAndName[0] = jsonResults.getString("Key");
            keyAndName[1] = jsonResults.getString("LocalizedName");
        } catch (JSONException e) {
            Log.e(TAG, "Received malformed location info (id="
                    + id + ", lang=" + getLanguage() + ")", e);
        }

        return keyAndName;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray forecasts, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>(forecasts.length());
        int count = forecasts.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }
        for (int i = 0; i < count; i++) {
            DayForecast item;
            try {
                JSONObject forecast = forecasts.getJSONObject(i);
                item = new DayForecast(
                        /* low */ sanitizeTemperature(forecast.getJSONObject("Temperature").getJSONObject("Minimum").getDouble("Value"), metric),
                        /* high */ sanitizeTemperature(forecast.getJSONObject("Temperature").getJSONObject("Maximum").getDouble("Value"), metric),
                        /* condition */ forecast.getJSONObject("Day").getString("IconPhrase"),
                        /* conditionCode */ mapWeatherIconToCode(forecast.getJSONObject("Day").getInt("Icon")),
                        forecast.getString("Date"),
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
        String[] availableLang = {"ar", "ar_ae", "ar_bh", "ar_dz", "ar_eg", "ar_iq", "ar_jo", "ar_kw", "ar_lb", "ar_ly", "ar_ma", "ar_om", "ar_qa", "ar_sa", "ar_sd", "ar_sy", "ar_tn", "ar_ye", "az", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en_au", "en_bz", "en_ca", "en_gb", "en_ie", "en_nz", "en_tt", "en_us", "en_za", "es", "es_ar", "es_bo", "es_cl", "es_co", "es_cr", "es_do", "es_ec", "es_gt", "es_hn", "es_mx", "es_ni", "es_pa", "es_pr", "es_py", "es_sv", "es_uy", "es_ve", "et", "fa", "fi", "fr", "fr_be", "fr_ca", "fr_ch", "fr_lu", "gu", "he", "hi", "hr", "hu", "in", "id", "is", "it", "it_ch", "iw", "ja", "ko", "kk", "kn", "lt", "lv", "mk", "mr", "ms", "nb", "nl", "nl_aw", "nl_be", "nl_cw", "nl_sx", "pa", "pl", "pt", "pt_ao", "pt_cv", "pt_gw", "pt_mz", "pt_st", "pt_br", "ro", "ru", "sk", "sl", "sr", "sr_me", "sv", "sv_se", "sv_fi", "sw", "ta", "te", "tl", "th", "tr", "uk", "ur", "uz", "vi", "zh", "zh_cn", "zh_mo", "zh_sg", "zh_hk", "zh_tw"};
        String sysLang = Locale.getDefault().getLanguage();

        return Arrays.asList(availableLang).contains(sysLang) ? sysLang.replaceFirst("_", "-") : "en-us";
    }

    /* Thanks Chronus(app) */
    private static int mapWeatherIconToCode(int weatherIcon) {
        switch (weatherIcon) {
            case 1:
                return 32;
            case 2:
            case 3:
                return 30;
            case 4:
            case 6:
                return 28;
            case 5:
            case 37:
                return 21;
            case 7:
            case 8:
                return 26;
            case 11:
                return 20;
            case 12:
            case 13:
            case 14:
            case 18:
                return 11;
            case 15:
                return 4;
            case 16:
            case 17:
                return 37;
            case 19:
            case 20:
            case 21:
            case 43:
                return 13;
            case 22:
            case 23:
                return 16;
            case 25:
                return 18;
            case 26:
                return 10;
            case 29:
                return 5;
            case 30:
                return 36;
            case 31:
                return 25;
            case 32:
                return 24;
            case 33:
                return 31;
            case 34:
                return 33;
            case 35:
            case 36:
                return 29;
            case 38:
                return 27;
            case 39:
            case 40:
                return 45;
            case 41:
            case 42:
                return 47;
            case 44:
                return 46;
            default:
                return -1;
        }
    }

    private static float sanitizeTemperature(double tempToC, boolean metric) {
        float value = (float) tempToC;
        if (metric) {
            value = (value - 32) * 5 / 9;
        }
        return value;
    }

    private String getAPIKey() {
        return mContext.getResources().getString(R.string.accu_api_key);
    }

    public boolean shouldRetry() {
        return false;
    }
}
