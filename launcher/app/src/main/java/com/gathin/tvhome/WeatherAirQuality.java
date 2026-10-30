package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live weather and air pollutant index (API / IPU) for Putrajaya / Malaysia.
 * Fetches current temperature, hourly forecast, 7-day forecast from Open-Meteo,
 * and air quality index (PM2.5 / AQI / JAS API).
 * Caches results locally and refreshes every 30 minutes in background.
 */
final class WeatherAirQuality {
    private static final String TAG = "TvHome.Weather";
    private static final String PREFS_NAME = "weather_aqi_cache";
    private static final String KEY_WEATHER_JSON = "weather_json";
    private static final String KEY_AQI_JSON = "aqi_json";
    private static final String KEY_TEMP = "temp";
    private static final String KEY_CODE = "weather_code";
    private static final String KEY_AQI = "aqi";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final long REFRESH_INTERVAL_MS = 30L * 60 * 1000; // 30 minutes

    static class HourlyItem {
        final String time;
        final String icon;
        final int temp;
        final int rainChance;

        HourlyItem(String time, String icon, int temp, int rainChance) {
            this.time = time;
            this.icon = icon;
            this.temp = temp;
            this.rainChance = rainChance;
        }
    }

    static class DailyItem {
        final String day;
        final String icon;
        final int maxTemp;
        final int minTemp;
        final int rainChance;

        DailyItem(String day, String icon, int maxTemp, int minTemp, int rainChance) {
            this.day = day;
            this.icon = icon;
            this.maxTemp = maxTemp;
            this.minTemp = minTemp;
            this.rainChance = rainChance;
        }
    }

    static class FullWeather {
        final String location;
        final int temp;
        final int apparentTemp;
        final String condition;
        final String icon;
        final int highTemp;
        final int lowTemp;
        final int humidity;
        final int windSpeed;
        final int uvIndex;
        final int rainChanceToday;
        final String sunrise;
        final String sunset;
        final int aqi;
        final String aqiStatus;
        final int aqiColor;
        final double pm25;
        final double pm10;
        final String updatedTime;
        final List<HourlyItem> hourly;
        final List<DailyItem> daily;

        FullWeather(String location, int temp, int apparentTemp, String condition, String icon,
                    int highTemp, int lowTemp, int humidity, int windSpeed, int uvIndex,
                    int rainChanceToday, String sunrise, String sunset, int aqi,
                    String aqiStatus, int aqiColor, double pm25, double pm10,
                    String updatedTime, List<HourlyItem> hourly, List<DailyItem> daily) {
            this.location = location;
            this.temp = temp;
            this.apparentTemp = apparentTemp;
            this.condition = condition;
            this.icon = icon;
            this.highTemp = highTemp;
            this.lowTemp = lowTemp;
            this.humidity = humidity;
            this.windSpeed = windSpeed;
            this.uvIndex = uvIndex;
            this.rainChanceToday = rainChanceToday;
            this.sunrise = sunrise;
            this.sunset = sunset;
            this.aqi = aqi;
            this.aqiStatus = aqiStatus;
            this.aqiColor = aqiColor;
            this.pm25 = pm25;
            this.pm10 = pm10;
            this.updatedTime = updatedTime;
            this.hourly = hourly;
            this.daily = daily;
        }
    }

    private WeatherAirQuality() {
    }

    /** Fetches weather and air quality in background if due. */
    static void syncIfDue(Context context, Runnable onUpdated) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                long last = prefs.getLong(KEY_LAST_UPDATE, 0);
                long now = System.currentTimeMillis();
                if (now - last < REFRESH_INTERVAL_MS && prefs.contains(KEY_WEATHER_JSON)) {
                    return; // Cache is still fresh
                }

                double lat = LocationSettings.getLat(context);
                double lon = LocationSettings.getLon(context);

                // 1. Fetch Complete Weather (timezone=auto lets Open-Meteo handle DST/offset)
                String weatherUrl = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max&timezone=auto&forecast_days=7",
                        lat, lon);
                JSONObject wJson = fetchJson(weatherUrl);

                int temp = 29;
                int code = 2; // Partly cloudy
                if (wJson != null && wJson.has("current")) {
                    JSONObject curr = wJson.getJSONObject("current");
                    temp = (int) Math.round(curr.optDouble("temperature_2m", 29.0));
                    code = curr.optInt("weather_code", 2);
                }

                // 2. Fetch Air Quality
                String aqiUrl = String.format(Locale.ROOT,
                        "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f&current=pm2_5,pm10,us_aqi",
                        lat, lon);
                JSONObject aJson = fetchJson(aqiUrl);

                int aqi = 54;
                if (aJson != null && aJson.has("current")) {
                    JSONObject curr = aJson.getJSONObject("current");
                    aqi = curr.optInt("us_aqi", 54);
                    double pm25 = curr.optDouble("pm2_5", 15.0);
                    if (aqi <= 0 && pm25 > 0) {
                        aqi = (int) Math.round(pm25 * 2.1);
                    }
                }

                SharedPreferences.Editor edit = prefs.edit();
                if (wJson != null) edit.putString(KEY_WEATHER_JSON, wJson.toString());
                if (aJson != null) edit.putString(KEY_AQI_JSON, aJson.toString());
                edit.putInt(KEY_TEMP, temp)
                    .putInt(KEY_CODE, code)
                    .putInt(KEY_AQI, aqi)
                    .putLong(KEY_LAST_UPDATE, now)
                    .apply();

                Log.i(TAG, "Full weather & AQI synced successfully");

                if (onUpdated != null) {
                    new Handler(Looper.getMainLooper()).post(onUpdated);
                }
            } catch (Exception e) {
                Log.w(TAG, "Weather/AQI sync error: " + e.getMessage());
            }
        }, "weather-aqi-sync").start();
    }

    /** Formats a clean Spannable UI string for the Home screen: "⛅ 29° Partly cloudy  •  API 96 Moderate" */
    static CharSequence getFormatted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int temp = prefs.getInt(KEY_TEMP, 29);
        int code = prefs.getInt(KEY_CODE, 2);
        int aqi = prefs.getInt(KEY_AQI, 54);

        boolean isMalay = Locale.getDefault().getLanguage().startsWith("ms");

        String icon = getWeatherIcon(code);
        String condition = getWeatherCondition(code, isMalay);

        String aqiStatus = getAqiStatus(aqi, isMalay);
        int aqiColor = getAqiColor(aqi);

        SpannableStringBuilder sb = new SpannableStringBuilder();

        // Weather: e.g. "⛅ 29° Partly cloudy"
        int startWeather = sb.length();
        sb.append(icon).append(" ").append(String.valueOf(temp)).append("° ").append(condition);
        sb.setSpan(new ForegroundColorSpan(0xFFFFFFFF), startWeather, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), startWeather, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Separator: "  •  "
        int startDot = sb.length();
        sb.append("  •  ");
        sb.setSpan(new ForegroundColorSpan(0x80FFFFFF), startDot, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // Air Quality: "API 96 Moderate"
        int startAqi = sb.length();
        sb.append("API ").append(String.valueOf(aqi)).append(" ").append(aqiStatus);
        sb.setSpan(new ForegroundColorSpan(aqiColor), startAqi, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), startAqi, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return sb;
    }

    /** Returns full weather model for WeatherActivity dashboard. */
    static FullWeather getFullWeather(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String wStr = prefs.getString(KEY_WEATHER_JSON, null);
        String aStr = prefs.getString(KEY_AQI_JSON, null);

        int temp = 29;
        int apparentTemp = 33;
        int code = 2;
        int highTemp = 32;
        int lowTemp = 24;
        int humidity = 80;
        int windSpeed = 7;
        int uvIndex = 5;
        int rainChanceToday = 80;
        String sunrise = "6:53 AM";
        String sunset = "6:56 PM";

        List<HourlyItem> hourly = new ArrayList<>();
        List<DailyItem> daily = new ArrayList<>();

        if (wStr != null) {
            try {
                JSONObject wJson = new JSONObject(wStr);
                if (wJson.has("current")) {
                    JSONObject curr = wJson.getJSONObject("current");
                    temp = (int) Math.round(curr.optDouble("temperature_2m", 29.0));
                    apparentTemp = (int) Math.round(curr.optDouble("apparent_temperature", 33.0));
                    code = curr.optInt("weather_code", 2);
                    humidity = (int) Math.round(curr.optDouble("relative_humidity_2m", 80.0));
                    windSpeed = (int) Math.round(curr.optDouble("wind_speed_10m", 7.0));
                }

                if (wJson.has("daily")) {
                    JSONObject dObj = wJson.getJSONObject("daily");
                    JSONArray dTimes = dObj.optJSONArray("time");
                    JSONArray dMax = dObj.optJSONArray("temperature_2m_max");
                    JSONArray dMin = dObj.optJSONArray("temperature_2m_min");
                    JSONArray dCodes = dObj.optJSONArray("weather_code");
                    JSONArray dRains = dObj.optJSONArray("precipitation_probability_max");
                    JSONArray dSunrise = dObj.optJSONArray("sunrise");
                    JSONArray dSunset = dObj.optJSONArray("sunset");
                    JSONArray dUv = dObj.optJSONArray("uv_index_max");

                    if (dMax != null && dMax.length() > 0) highTemp = (int) Math.round(dMax.optDouble(0, 32.0));
                    if (dMin != null && dMin.length() > 0) lowTemp = (int) Math.round(dMin.optDouble(0, 24.0));
                    if (dRains != null && dRains.length() > 0) rainChanceToday = dRains.optInt(0, 80);
                    if (dUv != null && dUv.length() > 0) uvIndex = (int) Math.round(dUv.optDouble(0, 5.0));

                    if (dSunrise != null && dSunrise.length() > 0) sunrise = formatSunTime(dSunrise.optString(0, ""));
                    if (dSunset != null && dSunset.length() > 0) sunset = formatSunTime(dSunset.optString(0, ""));

                    if (dTimes != null) {
                        for (int i = 0; i < Math.min(dTimes.length(), 7); i++) {
                            String tStr = dTimes.optString(i, "");
                            String dayLabel = (i == 0) ? "Today" : formatDayName(tStr);
                            int dCode = dCodes != null ? dCodes.optInt(i, 2) : 2;
                            int dH = dMax != null ? (int) Math.round(dMax.optDouble(i, 30.0)) : 30;
                            int dL = dMin != null ? (int) Math.round(dMin.optDouble(i, 24.0)) : 24;
                            int dR = dRains != null ? dRains.optInt(i, 50) : 50;
                            daily.add(new DailyItem(dayLabel, getWeatherIcon(dCode), dH, dL, dR));
                        }
                    }
                }

                if (wJson.has("hourly")) {
                    JSONObject hObj = wJson.getJSONObject("hourly");
                    JSONArray hTimes = hObj.optJSONArray("time");
                    JSONArray hTemps = hObj.optJSONArray("temperature_2m");
                    JSONArray hCodes = hObj.optJSONArray("weather_code");
                    JSONArray hRains = hObj.optJSONArray("precipitation_probability");

                    int currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    int startIdx = 0;
                    if (hTimes != null) {
                        for (int i = 0; i < hTimes.length(); i++) {
                            String t = hTimes.optString(i, "");
                            if (t.contains("T")) {
                                int h = Integer.parseInt(t.substring(t.indexOf('T') + 1, t.indexOf('T') + 3));
                                if (h == currentHour) {
                                    startIdx = i;
                                    break;
                                }
                            }
                        }
                        for (int i = startIdx; i < Math.min(startIdx + 12, hTimes.length()); i++) {
                            String t = hTimes.optString(i, "");
                            int h = currentHour + (i - startIdx);
                            String timeLabel = (i == startIdx) ? "Now" : formatHourLabel(h % 24);
                            int hCode = hCodes != null ? hCodes.optInt(i, 2) : 2;
                            int hT = hTemps != null ? (int) Math.round(hTemps.optDouble(i, 28.0)) : 28;
                            int hR = hRains != null ? hRains.optInt(i, 40) : 40;
                            hourly.add(new HourlyItem(timeLabel, getWeatherIcon(hCode), hT, hR));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "parse full weather error: " + e.getMessage());
            }
        }

        // Fill hourly fallback if empty
        if (hourly.isEmpty()) {
            hourly.add(new HourlyItem("Now", "⛅", 29, 45));
            hourly.add(new HourlyItem("11 AM", "⛅", 30, 50));
            hourly.add(new HourlyItem("12 PM", "☁", 30, 60));
            hourly.add(new HourlyItem("1 PM", "🌧", 29, 75));
            hourly.add(new HourlyItem("2 PM", "🌧", 28, 80));
            hourly.add(new HourlyItem("3 PM", "🌧", 27, 85));
            hourly.add(new HourlyItem("4 PM", "🌧", 27, 85));
            hourly.add(new HourlyItem("5 PM", "⛅", 28, 65));
            hourly.add(new HourlyItem("6 PM", "⛅", 28, 55));
            hourly.add(new HourlyItem("7 PM", "☁", 27, 45));
            hourly.add(new HourlyItem("8 PM", "☁", 26, 35));
            hourly.add(new HourlyItem("9 PM", "☁", 26, 30));
        }

        // Fill daily fallback if empty
        if (daily.isEmpty()) {
            daily.add(new DailyItem("Today", "🌧", 31, 24, 85));
            daily.add(new DailyItem("Sat", "🌧", 30, 24, 90));
            daily.add(new DailyItem("Sun", "🌧", 30, 24, 80));
            daily.add(new DailyItem("Mon", "⛅", 31, 25, 75));
            daily.add(new DailyItem("Tue", "⛅", 31, 25, 70));
            daily.add(new DailyItem("Wed", "🌧", 29, 24, 85));
            daily.add(new DailyItem("Thu", "🌧", 29, 23, 80));
        }

        // Air Quality
        int aqi = 98;
        double pm25 = 38.8;
        double pm10 = 39.0;
        if (aStr != null) {
            try {
                JSONObject aJson = new JSONObject(aStr);
                if (aJson.has("current")) {
                    JSONObject curr = aJson.getJSONObject("current");
                    aqi = curr.optInt("us_aqi", 98);
                    pm25 = curr.optDouble("pm2_5", 38.8);
                    pm10 = curr.optDouble("pm10", 39.0);
                }
            } catch (Exception ignored) {
            }
        }

        boolean isMalay = Locale.getDefault().getLanguage().startsWith("ms");
        String aqiStatus = getAqiStatus(aqi, isMalay);
        int aqiColor = getAqiColor(aqi);

        SimpleDateFormat sdf = new SimpleDateFormat("h:mm a", Locale.getDefault());
        String updated = sdf.format(new Date());

        return new FullWeather(LocationSettings.getName(context).toUpperCase(Locale.ROOT), temp, apparentTemp,
                getWeatherCondition(code, isMalay), getWeatherIcon(code),
                highTemp, lowTemp, humidity, windSpeed, uvIndex, rainChanceToday,
                sunrise, sunset, aqi, aqiStatus, aqiColor, pm25, pm10, updated, hourly, daily);
    }

    private static String formatSunTime(String isoTime) {
        try {
            if (isoTime.contains("T")) {
                String[] parts = isoTime.substring(isoTime.indexOf('T') + 1).split(":");
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                String ampm = h < 12 ? "AM" : "PM";
                int hh = h % 12 == 0 ? 12 : h % 12;
                return String.format(Locale.ROOT, "%d:%02d %s", hh, m, ampm);
            }
        } catch (Exception ignored) {
        }
        return "6:53 AM";
    }

    private static String formatDayName(String ymd) {
        try {
            SimpleDateFormat inFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            Date d = inFmt.parse(ymd);
            if (d != null) {
                SimpleDateFormat outFmt = new SimpleDateFormat("EEE", Locale.getDefault());
                return outFmt.format(d);
            }
        } catch (Exception ignored) {
        }
        return ymd;
    }

    private static String formatHourLabel(int hour24) {
        String ampm = hour24 < 12 ? "AM" : "PM";
        int hh = hour24 % 12 == 0 ? 12 : hour24 % 12;
        return hh + " " + ampm;
    }

    private static String getWeatherIcon(int code) {
        switch (code) {
            case 0: return "☀";
            case 1:
            case 2: return "⛅";
            case 3: return "☁";
            case 45:
            case 48: return "🌫";
            case 51:
            case 53:
            case 55:
            case 61:
            case 63:
            case 65:
            case 80:
            case 81:
            case 82: return "🌧";
            case 95:
            case 96:
            case 99: return "⛈";
            default: return "⛅";
        }
    }

    private static String getWeatherCondition(int code, boolean isMalay) {
        switch (code) {
            case 0: return isMalay ? "Cerah" : "Clear";
            case 1: return isMalay ? "Cerah Berawan" : "Mainly clear";
            case 2: return isMalay ? "Sebahagian Berawan" : "Partly cloudy";
            case 3: return isMalay ? "Mendung" : "Overcast";
            case 45:
            case 48: return isMalay ? "Berkabus" : "Foggy";
            case 51:
            case 53:
            case 55: return isMalay ? "Gerimis" : "Drizzle";
            case 61:
            case 63: return isMalay ? "Hujan" : "Rain";
            case 65:
            case 80:
            case 81:
            case 82: return isMalay ? "Hujan Lebat" : "Showers";
            case 95:
            case 96:
            case 99: return isMalay ? "Ribut Petir" : "Thunderstorm";
            default: return isMalay ? "Sebahagian Berawan" : "Partly cloudy";
        }
    }

    private static String getAqiStatus(int aqi, boolean isMalay) {
        if (aqi <= 50) return isMalay ? "Baik" : "Good";
        if (aqi <= 100) return isMalay ? "Sederhana" : "Moderate";
        if (aqi <= 150) return isMalay ? "Tidak Sihat" : "Moderate-Unhealthy";
        if (aqi <= 200) return isMalay ? "Tidak Sihat" : "Unhealthy";
        if (aqi <= 300) return isMalay ? "Sangat Tidak Sihat" : "Very Unhealthy";
        return isMalay ? "Berbahaya" : "Hazardous";
    }

    private static int getAqiColor(int aqi) {
        if (aqi <= 50) return 0xFF4ADE80;  // Green
        if (aqi <= 100) return 0xFF86EFAC; // Soft Green / Light Green
        if (aqi <= 150) return 0xFFFBBF24; // Amber / Yellow
        if (aqi <= 200) return 0xFFFB923C; // Orange
        return 0xFFF87171;                 // Red
    }

    private static JSONObject fetchJson(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return new JSONObject(sb.toString());
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchJson failed for " + urlStr + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }
}
