package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Persists the user's chosen location (lat, lon, timezone offset, display name) and provides
 * two ways to set it: auto-detect from the device's LocationManager, and geocoding search via
 * the Open-Meteo geocoding API. Both WeatherAirQuality and PrayerTimes read from here.
 */
final class LocationSettings {
    private static final String TAG = "TvHome.Location";
    private static final String PREFS = "location_prefs";
    private static final String KEY_LAT = "lat";
    private static final String KEY_LON = "lon";
    private static final String KEY_TZ = "tz";
    private static final String KEY_NAME = "name";
    private static final String KEY_MANUAL = "manual";
    private static final String KEY_ZONE = "jakim_zone";

    static final double DEFAULT_LAT = 2.9264;
    static final double DEFAULT_LON = 101.6964;
    static final double DEFAULT_TZ = 8.0;
    static final String DEFAULT_NAME = "Putrajaya";
    static final String DEFAULT_ZONE = "WLY01";

    static final class Result {
        final String name, country;
        final double lat, lon, tzOffset;

        Result(String name, String country, double lat, double lon, double tzOffset) {
            this.name = name;
            this.country = country;
            this.lat = lat;
            this.lon = lon;
            this.tzOffset = tzOffset;
        }

        @Override public String toString() {
            return country.isEmpty() ? name : name + ", " + country;
        }
    }

    private LocationSettings() {}

    static double getLat(Context ctx) {
        return getPrefs(ctx).getFloat(KEY_LAT, (float) DEFAULT_LAT);
    }

    static double getLon(Context ctx) {
        return getPrefs(ctx).getFloat(KEY_LON, (float) DEFAULT_LON);
    }

    static double getTz(Context ctx) {
        return getPrefs(ctx).getFloat(KEY_TZ, (float) DEFAULT_TZ);
    }

    static String getName(Context ctx) {
        return getPrefs(ctx).getString(KEY_NAME, DEFAULT_NAME);
    }

    /** True when the user has saved a location (either auto or manual). */
    static boolean isCustom(Context ctx) {
        return getPrefs(ctx).contains(KEY_LAT);
    }

    static boolean isManual(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_MANUAL, false);
    }

    /**
     * Returns the JAKIM zone code to use for prayer times.
     * - Default (no custom location): "WLY01" (Putrajaya)
     * - Custom Malaysian location: the detected zone code (e.g. "SGR01"), or null while detecting
     * - Custom non-Malaysian location: null (solar calculation will be used)
     */
    static String getJakimZone(Context ctx) {
        if (!isCustom(ctx)) return DEFAULT_ZONE;
        String z = getPrefs(ctx).getString(KEY_ZONE, null);
        if (z == null) return null;       // not yet detected
        return z.isEmpty() ? null : z;    // "" = non-Malaysian, null = use solar calc
    }

    static void saveZone(Context ctx, String zone) {
        getPrefs(ctx).edit().putString(KEY_ZONE, zone != null ? zone : "").apply();
    }

    static void save(Context ctx, Result r, boolean manual) {
        getPrefs(ctx).edit()
                .putFloat(KEY_LAT, (float) r.lat)
                .putFloat(KEY_LON, (float) r.lon)
                .putFloat(KEY_TZ, (float) r.tzOffset)
                .putString(KEY_NAME, r.name)
                .putBoolean(KEY_MANUAL, manual)
                .remove(KEY_ZONE) // clear so zone is re-detected for new location
                .apply();
        invalidateCache(ctx);
    }

    static void reset(Context ctx) {
        getPrefs(ctx).edit().clear().apply();
        invalidateCache(ctx);
    }

    /**
     * Synchronously detects the JAKIM zone for the given coordinates by calling
     * api.waktusolat.app/zones/{lat}/{lon}. Returns null for non-Malaysian coordinates.
     * Must be called from a background thread.
     */
    static String detectJakimZoneSync(double lat, double lon) {
        // Rough bounding box for Malaysia (peninsula + Sabah/Sarawak)
        if (lat < 0.8 || lat > 7.4 || lon < 99.6 || lon > 119.3) return null;
        try {
            String u = String.format(Locale.ROOT, "https://api.waktusolat.app/zones/%.6f/%.6f", lat, lon);
            JSONObject j = fetchJson(u);
            if (j != null && j.has("zone")) {
                String zone = j.optString("zone", "").trim();
                return zone.isEmpty() ? null : zone;
            }
        } catch (Exception e) {
            Log.w(TAG, "detectJakimZoneSync: " + e.getMessage());
        }
        return null;
    }

    /** Clears weather and prayer caches so fresh data is fetched at the new location. */
    private static void invalidateCache(Context ctx) {
        ctx.getSharedPreferences("weather_aqi_cache", Context.MODE_PRIVATE).edit().clear().apply();
        ctx.getSharedPreferences("prayer_times_cache", Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Tries GPS then network provider for a last-known fix, then reverse-geocodes the name.
     * Calls back on the main thread with null if no fix is available.
     */
    @SuppressWarnings("deprecation")
    static void detectAuto(Context ctx, Consumer<Result> callback) {
        new Thread(() -> {
            Result result = null;
            try {
                LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        Location loc = lm.getLastKnownLocation(provider);
                        if (loc != null && (best == null || loc.getAccuracy() < best.getAccuracy()))
                            best = loc;
                    } catch (SecurityException | IllegalArgumentException ignored) {}
                }
                if (best != null) {
                    double lat = best.getLatitude(), lon = best.getLongitude();
                    String name = null, country = "";
                    if (Geocoder.isPresent()) {
                        try {
                            List<Address> addrs = new Geocoder(ctx, Locale.getDefault())
                                    .getFromLocation(lat, lon, 1);
                            if (addrs != null && !addrs.isEmpty()) {
                                Address a = addrs.get(0);
                                name = a.getLocality() != null ? a.getLocality()
                                        : a.getSubAdminArea() != null ? a.getSubAdminArea()
                                        : a.getAdminArea();
                                if (a.getCountryName() != null) country = a.getCountryName();
                            }
                        } catch (Exception ignored) {}
                    }
                    // Get timezone offset from Open-Meteo forecast (timezone=auto)
                    double tz = DEFAULT_TZ;
                    try {
                        String u = String.format(Locale.ROOT,
                                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&forecast_days=1&current=temperature_2m",
                                lat, lon);
                        JSONObject j = fetchJson(u);
                        if (j != null) {
                            tz = j.optInt("utc_offset_seconds", 28800) / 3600.0;
                            if (name == null) {
                                String tzStr = j.optString("timezone", "");
                                if (tzStr.contains("/"))
                                    name = tzStr.substring(tzStr.lastIndexOf('/') + 1).replace('_', ' ');
                            }
                        }
                    } catch (Exception ignored) {}
                    if (name == null) name = String.format(Locale.ROOT, "%.3f, %.3f", lat, lon);
                    result = new Result(name, country, lat, lon, tz);
                }
            } catch (Exception e) {
                Log.w(TAG, "detectAuto: " + e.getMessage());
            }
            final Result r = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(r));
        }, "loc-detect").start();
    }

    /** Searches by city name using Open-Meteo geocoding API. Calls back on the main thread. */
    static void search(String query, Consumer<List<Result>> callback) {
        new Thread(() -> {
            List<Result> list = new ArrayList<>();
            try {
                String u = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(query, "UTF-8") + "&count=6&language=en&format=json";
                JSONObject obj = fetchJson(u);
                if (obj != null) {
                    JSONArray arr = obj.optJSONArray("results");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject r = arr.getJSONObject(i);
                            String name = r.optString("name", "");
                            String admin1 = r.optString("admin1", "");
                            String country = r.optString("country", "");
                            if (!admin1.isEmpty() && !admin1.equals(name)) name = name + ", " + admin1;
                            double lat = r.optDouble("latitude", DEFAULT_LAT);
                            double lon = r.optDouble("longitude", DEFAULT_LON);
                            double tz = r.optInt("utc_offset_seconds", 28800) / 3600.0;
                            list.add(new Result(name, country, lat, lon, tz));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "search: " + e.getMessage());
            }
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(list));
        }, "loc-search").start();
    }

    private static JSONObject fetchJson(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();
                return new JSONObject(sb.toString());
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "fetchJson: " + e.getMessage());
        }
        return null;
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
