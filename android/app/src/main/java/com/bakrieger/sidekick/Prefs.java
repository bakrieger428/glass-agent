package com.bakrieger.sidekick;

import android.content.Context;
import android.content.SharedPreferences;

/** Key/value storage for API keys and settings. */
public final class Prefs {
    private static final String FILE = "sidekick";

    public static final String K_DEEPINFRA = "key.deepinfra";
    public static final String K_ZAI = "key.zai";
    public static final String K_OWNER = "owner.name";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key) {
        return sp(c).getString(key, "");
    }

    public static void set(Context c, String key, String value) {
        sp(c).edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    public static boolean has(Context c, String key) {
        String v = get(c, key);
        return v != null && v.length() > 8;
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }
}
