package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Home Assistant link's settings. Address, device name and the on/off switch live in config.json
 * like every other setting; the access token lives only in the app's private storage, never in
 * config.json (it sits in a readable folder and themes overwrite it). The token is never logged.
 */
final class HaSettings {
    private static final String TAG = "TvHome.HaSettings";
    private static final String PREFS = "ha_link";
    private static final String KEY_TOKEN = "token";
    static final String TOKEN_FILE = "ha-token.txt";
    static final String DEFAULT_DEVICE = "tv";
    private static final int MAX_TOKEN_CHARS = 2048;

    private HaSettings() {}

    /**
     * Cleans the text of a pushed token file: removes a byte-order mark, surrounding whitespace and one
     * pair of quotes. Returns null when nothing usable is left or the text still holds whitespace.
     */
    static String parseTokenFile(String text) {
        if (text == null) return null;
        String s = text;
        if (s.startsWith("﻿")) s = s.substring(1);
        s = s.trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' || a == '\'') && a == b) s = s.substring(1, s.length() - 1).trim();
        }
        if (s.isEmpty() || s.length() > MAX_TOKEN_CHARS) return null;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return null;
        }
        return s;
    }

    private static JSONObject overlay(Context ctx) {
        File dir = Config.dir(ctx);
        return dir == null ? null : Config.read(new File(dir, Config.FILE_NAME));
    }

    static boolean enabled(Context ctx) {
        JSONObject j = overlay(ctx);
        Object v = j == null ? null : j.opt("ha_enabled");
        return v instanceof Boolean && (Boolean) v;
    }

    /** The address as the owner typed it; empty when not set. */
    static String url(Context ctx) {
        JSONObject j = overlay(ctx);
        Object v = j == null ? null : j.opt("ha_url");
        return v instanceof String ? ((String) v).trim() : "";
    }

    static String device(Context ctx) {
        JSONObject j = overlay(ctx);
        Object v = j == null ? null : j.opt("ha_device");
        String s = v instanceof String ? ((String) v).trim() : "";
        return s.isEmpty() ? DEFAULT_DEVICE : s;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The saved token, or an empty string. */
    static String token(Context ctx) {
        return prefs(ctx).getString(KEY_TOKEN, "");
    }

    static void setToken(Context ctx, String token) {
        String t = token == null ? null : parseTokenFile(token);
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (t == null) e.remove(KEY_TOKEN); else e.putString(KEY_TOKEN, t);
        e.apply();
    }

    /**
     * If {@code ha-token.txt} was pushed into the app's files folder, stores its token privately and
     * deletes the file. Returns whether a token was imported.
     */
    static boolean importTokenFile(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        File f = new File(dir, TOKEN_FILE);
        if (!f.isFile()) return false;
        try {
            if (f.length() > 4 * MAX_TOKEN_CHARS) {
                Log.w(TAG, TOKEN_FILE + " is too large; ignored");
                return false;
            }
            String token = parseTokenFile(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (token == null) {
                Log.w(TAG, TOKEN_FILE + " does not hold a usable token; left in place");
                return false;
            }
            setToken(ctx, token);
            if (!f.delete()) Log.w(TAG, "could not delete " + TOKEN_FILE);
            Log.i(TAG, "access token imported from " + TOKEN_FILE);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not read " + TOKEN_FILE + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
