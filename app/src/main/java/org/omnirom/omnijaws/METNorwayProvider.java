// Author this class - vladrevers

package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.util.Log;
import android.text.TextUtils;

import org.apache.http.client.HttpClient;
import org.apache.http.params.CoreProtocolPNames;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import java.io.IOException;
import java.util.TimeZone;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

public class METNorwayProvider extends AbstractWeatherProvider {
    private static final String TAG = "METNorwayProvider";

    private static final String URL_WEATHER =
            "https://api.met.no/weatherapi/locationforecast/2.0/?";
    private static final String PART_COORDINATES =
            "lat=%f&lon=%f";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&isNameRequired=true";

    private static final SimpleDateFormat gmt0Format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private static final SimpleDateFormat userTimeZoneFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private static final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public METNorwayProvider(Context context) {
        super(context);
        initTimeZoneFormat();
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES, location.getLatitude(), location.getLongitude());
        return getAllWeather(coordinates, metric);
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

    private WeatherInfo getAllWeather(String coordinates, boolean metric) {
        String url = URL_WEATHER + coordinates;
        String response = retrieve(url);
        if (response == null) {
            return null;
        }
        log(TAG, "URL = " + url + " returning a response of " + response);

        try {
            JSONArray timeseries = new JSONObject(response).getJSONObject("properties").getJSONArray("timeseries");
            JSONObject weather = timeseries.getJSONObject(0).getJSONObject("data").getJSONObject("instant").getJSONObject("details");

            double windSpeed = weather.getDouble("wind_speed");
            if (metric) {
                windSpeed *= 3.6;
            }

            String symbolCode = timeseries.getJSONObject(0).getJSONObject("data").getJSONObject("next_1_hours").getJSONObject("summary").getString("symbol_code");

            String city = getNameLocality(coordinates);
            if (TextUtils.isEmpty(city)) {
                city = mContext.getResources().getString(R.string.omnijaws_city_unkown);
            }

            WeatherInfo w = new WeatherInfo(mContext,
                    /* id */ coordinates,
                    /* cityId */ city,
                    /* condition */ symbolCode,
                    /* conditionCode */ arrayWeatherIconToCode[getPriorityCondition(symbolCode)],
                    /* temperature */ convertTemperature(weather.getDouble("air_temperature"), metric),
                    /* humidity */ (float) weather.getDouble("relative_humidity"),
                    /* wind */ (float) windSpeed,
                    /* windDir */ (int) weather.getDouble("wind_from_direction"),
                    metric,
                    parseForecasts(timeseries, metric),
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (coordinates = " + coordinates + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONArray timeseries, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<>(5);
        int count = timeseries.length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }

        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());

        int whileIndex = 0;

        while (convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")).contains(yesterday)) {
            whileIndex++;
        }

        boolean endDay = (whileIndex == 0) && isEndDay(convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")));

        for (int i = 0; i < 5; i++) {
            DayForecast item;
            try {

                double temp_max = Double.MIN_VALUE;
                double temp_min = Double.MAX_VALUE;
                String day = getDay(i);
                int symbolCode = 0;
                int scSixToTwelve = 0; // symbolCode next_6_hours in 06:00
                int scTwelveToEighteen = 0; // symbolCode next_6_hours in 12:00
                boolean hasFastCondition = false;
                String conditionDescription = "";
                String cdSixToEighteen = ""; // SymbolCode in 06:00 or 12:00

                while (convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")).contains(day)) {
                    double tempI = timeseries.getJSONObject(whileIndex).getJSONObject("data").getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");

                    if (tempI > temp_max) {
                        temp_max = tempI;
                    }
                    if (tempI < temp_min) {
                        temp_min = tempI;
                    }

                    boolean hasOneHour = timeseries.getJSONObject(whileIndex).getJSONObject("data").has("next_1_hours");
                    boolean hasSixHours = timeseries.getJSONObject(whileIndex).getJSONObject("data").has("next_6_hours");

                    hasFastCondition = scSixToTwelve != 0 && scTwelveToEighteen != 0;

                    if (!hasFastCondition && ((i == 0 && endDay) || isMorningOrAfternoon(convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")), hasOneHour))) {
                        String stepHours = hasOneHour ? "next_1_hours" : "next_6_hours";

                        String stepTextSymbolCode = timeseries.getJSONObject(whileIndex).getJSONObject("data").getJSONObject(stepHours).getJSONObject("summary").getString("symbol_code");
                        int stepSymbolCode = getPriorityCondition(stepTextSymbolCode);

                        if (stepSymbolCode > symbolCode) {
                            symbolCode = stepSymbolCode;
                            conditionDescription = stepTextSymbolCode;
                        }

                        if(hasSixHours) {
                            if (convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")).contains("T06")) {
                                String textSymbolCode = timeseries.getJSONObject(whileIndex).getJSONObject("data").getJSONObject("next_6_hours").getJSONObject("summary").getString("symbol_code");
                                scSixToTwelve = getPriorityCondition(textSymbolCode);
                                cdSixToEighteen = textSymbolCode;
                            } else if (scSixToTwelve != 0 && convertTimeZone(timeseries.getJSONObject(whileIndex).getString("time")).contains("T12")) {
                                String textSymbolCode = timeseries.getJSONObject(whileIndex).getJSONObject("data").getJSONObject("next_6_hours").getJSONObject("summary").getString("symbol_code");
                                scTwelveToEighteen = getPriorityCondition(textSymbolCode);

                                if (scSixToTwelve < scTwelveToEighteen) {
                                    cdSixToEighteen = textSymbolCode;
                                }
                            }
                        }
                    }
                    whileIndex++;
                }

                if(hasFastCondition) {
                    symbolCode = (scSixToTwelve > scTwelveToEighteen) ? scSixToTwelve : scTwelveToEighteen;
                    conditionDescription = cdSixToEighteen;
                }

                item = new DayForecast(
                        /* low */ convertTemperature(temp_min, metric),
                        /* high */ convertTemperature(temp_max, metric),
                        /* condition */ conditionDescription,
                        /* conditionCode */ arrayWeatherIconToCode[symbolCode],
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

    private static final HashMap<String, Integer> SYMBOL_CODE_MAPPING = new HashMap<>();
    static {
        SYMBOL_CODE_MAPPING.put("clearsky", 1);
        SYMBOL_CODE_MAPPING.put("fair", 2);
        SYMBOL_CODE_MAPPING.put("partlycloudy", 3);
        SYMBOL_CODE_MAPPING.put("cloudy", 4);
        SYMBOL_CODE_MAPPING.put("rainshowers", 5);
        SYMBOL_CODE_MAPPING.put("rainshowersandthunder", 6);
        SYMBOL_CODE_MAPPING.put("sleetshowers", 7);
        SYMBOL_CODE_MAPPING.put("snowshowers", 8);
        SYMBOL_CODE_MAPPING.put("rain", 9);
        SYMBOL_CODE_MAPPING.put("heavyrain", 10);
        SYMBOL_CODE_MAPPING.put("heavyrainandthunder", 11);
        SYMBOL_CODE_MAPPING.put("sleet", 12);
        SYMBOL_CODE_MAPPING.put("snow", 13);
        SYMBOL_CODE_MAPPING.put("snowandthunder", 14);
        SYMBOL_CODE_MAPPING.put("fog", 15);
        SYMBOL_CODE_MAPPING.put("sleetshowersandthunder", 20);
        SYMBOL_CODE_MAPPING.put("snowshowersandthunder", 21);
        SYMBOL_CODE_MAPPING.put("rainandthunder", 22);
        SYMBOL_CODE_MAPPING.put("sleetandthunder", 23);
        SYMBOL_CODE_MAPPING.put("lightrainshowersandthunder", 24);
        SYMBOL_CODE_MAPPING.put("heavyrainshowersandthunder", 25);
        SYMBOL_CODE_MAPPING.put("lightssleetshowersandthunder", 26);
        SYMBOL_CODE_MAPPING.put("heavysleetshowersandthunder", 27);
        SYMBOL_CODE_MAPPING.put("lightssnowshowersandthunder", 28);
        SYMBOL_CODE_MAPPING.put("heavysnowshowersandthunder", 29);
        SYMBOL_CODE_MAPPING.put("lightrainandthunder", 30);
        SYMBOL_CODE_MAPPING.put("lightsleetandthunder", 31);
        SYMBOL_CODE_MAPPING.put("heavysleetandthunder", 32);
        SYMBOL_CODE_MAPPING.put("lightsnowandthunder", 33);
        SYMBOL_CODE_MAPPING.put("heavysnowandthunder", 34);
        SYMBOL_CODE_MAPPING.put("lightrainshowers", 40);
        SYMBOL_CODE_MAPPING.put("heavyrainshowers", 41);
        SYMBOL_CODE_MAPPING.put("lightsleetshowers", 42);
        SYMBOL_CODE_MAPPING.put("heavysleetshowers", 43);
        SYMBOL_CODE_MAPPING.put("lightsnowshowers", 44);
        SYMBOL_CODE_MAPPING.put("heavysnowshowers", 45);
        SYMBOL_CODE_MAPPING.put("lightrain", 46);
        SYMBOL_CODE_MAPPING.put("lightsleet", 47);
        SYMBOL_CODE_MAPPING.put("heavysleet", 48);
        SYMBOL_CODE_MAPPING.put("lightsnow", 49);
        SYMBOL_CODE_MAPPING.put("heavysnow", 50);
    }

    /* Thanks Chronus(app) */
    private static final int[] arrayWeatherIconToCode = {-1, /*1*/ 32, /*2*/ 34, /*3*/ 30, /*4*/ 26, /*5*/ 40, /*6*/ 39, /*7*/ 6, /*8*/ 14, /*9*/ 11, /*10*/ 12, /*11*/ 4, /*12*/ 18, /*13*/ 16, /*14*/ 15, /*15*/ 20, /*16*/ -1, /*17*/ -1, /*18*/ -1, /*19*/ -1, /*20*/ 42, /*21*/ 42, /*22*/ 4, /*23*/ 6, /*24*/ 39, /*25*/ 39, /*26*/ 42, /*27*/ 42, /*28*/ 42, /*29*/ 42, /*30*/ 4, /*31*/ 6, /*32*/ 6, /*33*/ 15, /*34*/ 15, /*35*/ -1, /*36*/ -1, /*37*/ -1, /*38*/ -1, /*39*/ -1, /*40*/ 40, /*41*/ 40, /*42*/ 6, /*43*/ 6, /*44*/ 14, /*45*/ 14, /*46*/ 9, /*47*/ 18, /*48*/ 18, /*49*/ 16, /*50*/ 16};

    private static int getPriorityCondition(String condition) {
        int endIndex = condition.indexOf("_");
        if(endIndex != -1) {
            condition = condition.substring(0, endIndex);
        }
        return SYMBOL_CODE_MAPPING.getOrDefault(condition, 0);
    }

    @Override
    protected String retrieve(String url) {
        HttpGet request = new HttpGet(url);
        try {
            HttpClient client = new DefaultHttpClient();
            client.getParams().setParameter(CoreProtocolPNames.USER_AGENT, "OmniJawsApp/1.0");
            HttpResponse response = client.execute(request);
            int code = response.getStatusLine().getStatusCode();
            if (!(code == HttpStatus.SC_OK || code == HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION)) {
                log(TAG, "HttpStatus: " + code + " for url: " + url);
                return null;
            }
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                return EntityUtils.toString(entity);
            }
        } catch (IOException e) {
            Log.e(TAG, "Couldn't retrieve data from url " + url, e);
        }
        return null;
    }

    private void initTimeZoneFormat() {
        gmt0Format.setTimeZone(TimeZone.getTimeZone("GMT"));
        userTimeZoneFormat.setTimeZone(TimeZone.getDefault());
    }

    private String convertTimeZone(String tmp) {
        try {
            return userTimeZoneFormat.format(gmt0Format.parse(tmp));
        } catch (ParseException e) {
            return tmp;
        }
    }


    private String getDay(int i) {
        Calendar calendar = Calendar.getInstance();
        if(i > 0) {
            calendar.add(Calendar.DATE, i);
        }
        return dayFormat.format(calendar.getTime());
    }

    private Boolean isMorningOrAfternoon(String time, boolean hasOneHour) {
        int endI = hasOneHour ? 17 : 13;
        for (int i = 6; i <= endI; i++) {
            if(time.contains((i < 10) ? "T0":"T" + i)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEndDay(String time) {
        for (int i = 18; i <= 23; i++) {
            if(time.contains("T" + i)) {
                return true;
            }
        }
        return false;
    }

    private String getNameLocality(String coordinate) {
        double latitude = Double.valueOf(coordinate.substring(4, coordinate.indexOf("&")));
        double longitude = Double.valueOf(coordinate.substring(coordinate.indexOf("lon=") + 4));
        Geocoder geocoder = new Geocoder(mContext.getApplicationContext(), Locale.getDefault());
        try {
            List<Address> listAddresses = geocoder.getFromLocation(latitude, longitude, 1);
            if(listAddresses != null && listAddresses.size() > 0){
                return listAddresses.get(0).getLocality();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static float convertTemperature(double value, boolean metric) {
        if (!metric) {
            value = (value * 1.8) + 32;
        }
        return (float) value;
    }

    public boolean shouldRetry() {
        return false;
    }
}
