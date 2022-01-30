package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class MsnWeatherProvider extends AbstractWeatherProvider {
    private static final String TAG = "MsnWeatherProvider";

    private static final String URL_WEATHER =
            "https://weather.service.msn.com/find.aspx?weadegreetype=%s&culture=%s&weasearchstr=%s&src=omnijaws";
    private static final String URL_PLACES =
            "http://api.geonames.org/searchJSON?q=%s&lang=%s&username=omnijaws&isNameRequired=true";
    private static final String PART_COORDINATES =
            "%f,%f";

    public MsnWeatherProvider(Context context) {
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
        return handleWeatherRequest(coordinates, metric);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return handleWeatherRequest(id, metric);
    }

    private WeatherInfo handleWeatherRequest(String id, boolean metric) {
        String units = metric ? "C" : "F";
        String lang = "en"; // windSpeed unit and windDirection depend on the language and units
        String url = String.format(URL_WEATHER, units, lang, id);
        String response = retrieve(url);

        String localizedCityName = "", conditionCode = "", conditionDescription = "", msnWindDisplay = "";
        int temperature = 0, humidity = 0, windSpeed = 0;

        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(response));

            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {

                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("weather")) {

                    String locationName = parser.getAttributeValue(null, "weatherlocationname");
                    localizedCityName = locationName.split(",")[0];

                    while (parser.getName() == null || !(parser.getName().equals("current") && parser.getEventType() == XmlPullParser.START_TAG))
                        parser.next();

                    conditionDescription = parser.getAttributeValue(null, "skytext");
                    conditionCode = parser.getAttributeValue(null, "skycode");
                    temperature = Integer.parseInt(parser.getAttributeValue(null, "temperature"));
                    humidity = Integer.parseInt(parser.getAttributeValue(null, "humidity"));
                    windSpeed = Integer.parseInt(parser.getAttributeValue(null, "windspeed").split(" ")[0]);
                    msnWindDisplay = parser.getAttributeValue(null, "winddisplay");
                    break;
                }

                parser.next();
            }

            ArrayList<WeatherInfo.DayForecast> forecasts =
                    parseDayForecast(parser, metric);

            WeatherInfo w = new WeatherInfo(mContext, id, localizedCityName,
                    /* condition */ conditionDescription,
                    /* conditionCode */ convertWeatherCode(conditionCode),
                    /* temperature */ temperature,
                    /* humidity */ humidity,
                    /* wind */ windSpeed,
                    /* windDir */ parseWindDirection(msnWindDisplay),
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (Exception e) {
            Log.w(TAG, "Received malformed weather data (id = " + id + ")", e);
        }

        return null;
    }

    private ArrayList<WeatherInfo.DayForecast> parseDayForecast(XmlPullParser parser, boolean metric) {
        ArrayList<WeatherInfo.DayForecast> result = new ArrayList<>();
        float dayTempMin, dayTempMax;
        boolean hasYesterday = false, hasTwoDaysAgo = false;
        Calendar calendar = Calendar.getInstance();

        calendar.add(Calendar.DATE, -1);
        String yesterday = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());

        calendar.add(Calendar.DATE, -1);
        String twoDaysAgo = new SimpleDateFormat("yyyy-MM-dd").format(calendar.getTime());

        try {
            while (result.size() < 5 && parser.getEventType() != XmlPullParser.END_DOCUMENT) {

                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("forecast")) {

                    if (parser.getAttributeValue(null, "date").equals(yesterday)) {
                        hasYesterday = true;
                        parser.next();
                        continue;
                    } else if (parser.getAttributeValue(null, "date").equals(twoDaysAgo)) {
                        hasTwoDaysAgo = true;
                        parser.next();
                        continue;
                    }

                    dayTempMin = Float.parseFloat(parser.getAttributeValue(null, "low"));
                    dayTempMax = Float.parseFloat(parser.getAttributeValue(null, "high"));

                    WeatherInfo.DayForecast item = new WeatherInfo.DayForecast(
                            /* low */ dayTempMin,
                            /* high */ dayTempMax,
                            /* condition */ parser.getAttributeValue(null, "skytextday"),
                            /* conditionCode */ convertWeatherCode(parser.getAttributeValue(null, "skycodeday")),
                            /* date */ parser.getAttributeValue(null, "date"),
                            metric);
                    result.add(item);

                    // api returns forecast for 5 days and usually first is yesterday or two days ago
                    // But need a forecast for current and next 4 days, so we duplicate last item
                    if (result.size() == 3 && hasTwoDaysAgo)
                        result.add(result.get(2));
                    if (result.size() == 4 && hasYesterday)
                        result.add(result.get(3));
                }

                parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "Received malformed forecast data", e);
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

    private static int parseWindDirection(String msnWindDisplay) {
        // example msnWindDisplay - 22 km/h Southwest
        int startWindDirIndex = msnWindDisplay.indexOf("h ");

        if(startWindDirIndex != -1) {
            String windDirStr = msnWindDisplay.substring(startWindDirIndex + 2);
            switch (windDirStr) {
                case "Northeast":
                    return 45;
                case "East":
                    return 90;
                case "Southeast":
                    return 135;
                case "South":
                    return 180;
                case "Southwest":
                    return 225;
                case "West":
                    return 270;
                case "Northwest":
                    return 315;
                case "North":
                    return 360;
            }
        }

        return 0;
    }

    private static int convertWeatherCode(String conditionCode) {
        int numberCode = Integer.parseInt(conditionCode);

        if (numberCode == 39 || numberCode == 45)
            return 40;

        return numberCode;
    }

    public boolean shouldRetry() {
        return false;
    }
}
