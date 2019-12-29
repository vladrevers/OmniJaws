/*
 * Author this class - vladrevers
 * Credit - thegr1f, BadManners
 */
package org.omnirom.omnijaws;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import org.omnirom.omnijaws.WeatherInfo.DayForecast;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GismeteoProvider extends AbstractWeatherProvider {
    private static final String TAG = "Gismeteo";

    private static final String URL_LOCATION =
            "http://45e30b7f.services.gismeteo.ru/inform-service/a407a91cfcb53e52063b77e9e777f5bd/cities/?search_all=1&with_facts=1&lat_lng=1&count=10&with_tzone=1&lang=%s&startsWith=%s";
    private static final String URL_GPS =
            "http://45e30b7f.services.gismeteo.ru/inform-service/a407a91cfcb53e52063b77e9e777f5bd/cities/?with_facts=1&lat_lng=1&lng=%f&count=1&lang=%s&lat=%f";
    private static final String URL_FORECAST =
            "http://45e30b7f.services.gismeteo.ru/inform-service/a407a91cfcb53e52063b77e9e777f5bd/forecast/?lang=%s&city=%s";


    public GismeteoProvider(Context context) {
        super(context);
    }

    private static float sanitizeTemperature(String tempToF) {
        float value = Float.parseFloat(tempToF);
        value = (value * 9 / 5) + 32;
        return value;
    }

    public List<WeatherInfo.WeatherLocation> getLocations(String input) {

        String url = String.format(URL_LOCATION, getLang(), Uri.encode(input.replaceAll("\\p{Punct}","%20")));
        String response = retrieve(url);
        if (response == null) {
            return null;
        }

        try {
            XmlPullParserFactory parserFactory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = parserFactory.newPullParser();
            ArrayList<WeatherInfo.WeatherLocation> results = new ArrayList<>();
            parser.setInput(new StringReader(response));

            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("item")) {
                    WeatherInfo.WeatherLocation location = new WeatherInfo.WeatherLocation();

                    location.id = parser.getAttributeValue(null, "id");
                    location.city = parser.getAttributeValue(null, "n");

                    if (parser.getAttributeValue(null, "n").equals(parser.getAttributeValue(null, "district_name"))) {
                        location.countryId = parser.getAttributeValue(null, "country_name");
                    } else {
                        location.countryId = parser.getAttributeValue(null, "country_name") + ", " + parser.getAttributeValue(null, "district_name");
                    }

                    results.add(location);

                }
                parser.next();
            }
            return results;

        } catch (Exception e) {
            Log.w(TAG, "Received malformed location data (input=" + input + ")", e);
        }

        return null;
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        String locale = getLang();
        String forecastUrl = String.format(URL_FORECAST, locale, id);
        String forecastResponse = retrieve(forecastUrl);
        String locationID = null;
        String locationName = null;
        String factDescription = null;
        String factIcon = null;
        float factTemp = 0;
        float factHumidity = 0;
        float factWindSpeed = 0;
        int factWindScale = 0;

        if (forecastResponse == null) {
            return null;
        }
        log(TAG, "Forcast URL = " + forecastUrl + " returning a response of " + forecastResponse);


        try {
            XmlPullParserFactory parserFactory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = parserFactory.newPullParser();
            parser.setInput(new StringReader(forecastResponse));

            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {

                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("location")) {
                    locationID = parser.getAttributeValue(null, "id");
                    locationName = parser.getAttributeValue(null, "name");
                }

                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("fact")) {

                    while (parser.getName() == null || !(parser.getName().equals("values") && parser.getEventType() == XmlPullParser.START_TAG)) {
                        parser.next();
                    }

                    factDescription = parser.getAttributeValue(null, "descr");
                    factIcon = parser.getAttributeValue(null, "icon");
                    factWindSpeed = Float.parseFloat(parser.getAttributeValue(null, "ws"));

                    if (metric) {
                        factTemp = Float.parseFloat(parser.getAttributeValue(null, "t"));
                        factWindSpeed *= 3.6f; // m/s to km/h
                    } else {
                        factTemp = sanitizeTemperature(parser.getAttributeValue(null, "tflt"));
                        factWindSpeed *= 2.236936f; // m/s to mph
                    }

                    factHumidity = Float.parseFloat(parser.getAttributeValue(null, "hum"));
                    factWindScale = Integer.parseInt(parser.getAttributeValue(null, "wd"));
                    Log.d(TAG, Float.toString(factHumidity));
                    Log.d(TAG, Integer.toString(factWindScale));
                    break;
                }
                parser.next();
            }

            ArrayList<DayForecast> forecasts =
                    parseDayForecast(parser, metric);

            WeatherInfo w = new WeatherInfo(mContext, locationID, locationName,
                    /* condition */ factDescription,
                    /* conditionCode */ mapIconToCode(factIcon),
                    /* temperature */ factTemp,
                    /* humidity */ factHumidity,
                    /* wind */ factWindSpeed,
                    /* windDir */ approximateWindDegree(factWindScale),
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;

        } catch (Exception e) {
            Log.w(TAG, "Received malformed weather data (selection = " + id
                    + ", lang = " + locale + ")", e);
        }
        return null;
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String url = String.format(Locale.US, URL_GPS, location.getLongitude(), getLang(), location.getLatitude());
        String response = retrieve(url);

        if (response == null) {
            return null;
        }

        try {
            XmlPullParserFactory parserFactory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = parserFactory.newPullParser();
            parser.setInput(new StringReader(response));

            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("item")) {
                    return getCustomWeather(parser.getAttributeValue(null, "id"), metric);
                }
                parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Received malformed placefinder data (location="
                    + location + ", lang=" + getLang() + ")", e);
        }

        return null;
    }

    public boolean shouldRetry() {
        return false;
    }

    private ArrayList<DayForecast> parseDayForecast(XmlPullParser parser, boolean metric) {
        ArrayList<DayForecast> result = new ArrayList<>();
        boolean firstDay = true;
        float dayTempMin = 0;
        float dayTempMax = 0;

        try {
            while (result.size() < 5 && parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG
                        && parser.getName().equals("day")) {
                    if (!firstDay) {

                        if (metric) {
                            dayTempMin = Float.parseFloat(parser.getAttributeValue(null, "tmin"));
                            dayTempMax = Float.parseFloat(parser.getAttributeValue(null, "tmax"));
                        } else {
                            dayTempMin = sanitizeTemperature(parser.getAttributeValue(null, "tmin"));
                            dayTempMax = sanitizeTemperature(parser.getAttributeValue(null, "tmax"));
                        }

                        DayForecast item = new DayForecast(
                                /* low */ dayTempMin,
                                /* high */ dayTempMax,
                                /* condition */ parser.getAttributeValue(null, "descr"),
                                /* conditionCode */ mapIconToCode(parser.getAttributeValue(null, "icon")),
                                /* date */ parser.getAttributeValue(null, "date"),
                                metric);
                        result.add(item);
                    } else {
                        firstDay = false;
                    }
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

    private String getLang() {
        String language;
        switch (Locale.getDefault().getLanguage()) {
            case "ru":
                language = "ru";
                break;
            case "uk":
                language = "ua";
                break;
            case "pl":
                language = "pl";
                break;
            default:
                language = "en";
                break;
        }
        return language;
    }

    private int mapIconToCode(String factIcon) {
        switch (factIcon) {
            case "c4.st":
            case "d.c2.st":
            case "d.c3.st":
            case "d.st":
            case "n.c2.st":
            case "n.c3.st":
            case "n.st":
            case "c4.rs1.st":
            case "d.c3.rs1.st":
            case "d.c2.rs1.st":
            case "n.c2.rs1.st":
            case "n.c3.rs1.st":
            case "c4.r1.st":
            case "c4.s1.st":
            case "d.c2.r1.st":
            case "d.c2.s1.st":
            case "d.c3.r1.st":
            case "d.c3.s1.st":
            case "n.c2.r1.st":
            case "n.c2.s1.st":
            case "n.c3.r1.st":
            case "n.c3.s1.st":
                return 4; // thunderstorms
            case "c4.rs1":
            case "c4.rs2":
            case "c4.rs3":
            case "d.c2.rs1":
            case "d.c2.rs2":
            case "d.c2.rs3":
            case "d.c3.rs1":
            case "d.c3.rs2":
            case "d.c3.rs3":
            case "n.c2.rs1":
            case "n.c2.rs2":
            case "n.c2.rs3":
            case "n.c3.rs1":
            case "n.c3.rs2":
            case "n.c3.rs3":
                return 5; // mixed rain and snow
            case "c4.r1":
            case "d.c2.r1":
            case "d.c3.r1":
            case "n.c2.r1":
            case "n.c3.r1":
                return 9; // drizzle
            case "c4.r3":
            case "d.c2.r3":
            case "d.c3.r3":
            case "n.c2.r3":
            case "n.c3.r3":
            case "r3.mist":
                return 11; // showers
            case "c4.r2":
            case "d.c2.r2":
            case "d.c3.r2":
            case "n.c2.r2":
            case "n.c3.r2":
            case "r2.mist":
                return 12; // showers
            case "c4.s3":
            case "d.c2.s3":
            case "d.c3.s3":
            case "n.c2.s3":
            case "n.c3.s3":
            case "s3.mist":
                return 13; // snow flurries
            case "c4.s1":
            case "d.c2.s1":
            case "d.c3.s1":
            case "n.c2.s1":
            case "n.c3.s1":
                return 14; // light snow showers
            case "c4.s2":
            case "d.c2.s2":
            case "d.c3.s2":
            case "n.c2.s2":
            case "n.c3.s2":
            case "s2.mist":
                return 16; // snow
            case "mist":
            case "r1.st.mist":
            case "s1.st.mist":
            case "r1.mist":
            case "s1.mist":
                return 20; // foggy
            case "c4":
                return 26; // cloudy
            case "n.c3":
                return 29; // partly cloudy (night)
            case "d.c3":
                return 30; // partly cloudy (day)
            case "n":
                return 31; // clear (night)
            case "d":
                return 32; // sunny
            case "n.c2":
                return 33; // fair (day)
            case "d.c2":
                return 34; // fair (day)
            case "c4.rs2.st":
            case "d.c2.rs2.st":
            case "d.c3.rs2.st":
            case "n.c2.rs2.st":
            case "n.c3.rs2.st":
            case "c4.r2.st":
            case "d.c2.r2.st":
            case "d.c3.r2.st":
            case "r2.st.mist":
            case "n.c2.r2.st":
            case "n.c3.r2.st":
            case "c4.s2.st":
            case "d.c2.s2.st":
            case "d.c3.s2.st":
            case "n.c2.s2.st":
            case "n.c3.s2.st":
            case "s2.st.mist":
                return 37; // isolated thunderstorms
            case "c4.rs3.st":
            case "d.c2.rs3.st":
            case "d.c3.rs3.st":
            case "n.c2.rs3.st":
            case "n.c3.rs3.st":
            case "c4.r3.st":
            case "d.c2.r3.st":
            case "d.c3.r3.st":
            case "r3.st.mist":
            case "n.c2.r3.st":
            case "n.c3.r3.st":
            case "c4.s3.st":
            case "d.c2.s3.st":
            case "d.c3.s3.st":
            case "n.c2.s3.st":
            case "n.c3.s3.st":
            case "s3.st.mist":
                return 39; // scattered thunderstorms

        }

        return -1;
    }

    private int approximateWindDegree(int scale) {
        if (scale == 1) {
            scale = 360;
        } else if (scale >= 2) {
            scale = (scale - 1) * 45;
        }
        return scale;
    }
}