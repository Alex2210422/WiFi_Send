package com.alex.wifisend;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** All user settings in one place. */
public final class Prefs {

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("wifisend", Context.MODE_PRIVATE);
    }

    /** "auto", "ru" or "en". */
    public static String lang(Context c) {
        return sp(c).getString("lang", "auto");
    }

    public static void setLang(Context c, String v) {
        sp(c).edit().putString("lang", v).apply();
        Util.lang = v;
    }

    /** "auto", "light" or "dark". */
    public static String theme(Context c) {
        return sp(c).getString("theme", "auto");
    }

    public static void setTheme(Context c, String v) {
        sp(c).edit().putString("theme", v).apply();
    }

    public static boolean pinEnabled(Context c) {
        return sp(c).getBoolean("pinEnabled", false) && !pin(c).isEmpty();
    }

    public static String pin(Context c) {
        return sp(c).getString("pin", "");
    }

    public static void setPin(Context c, boolean enabled, String pin) {
        sp(c).edit().putBoolean("pinEnabled", enabled).putString("pin", pin).apply();
        resetToken(c);
    }

    /** Session token stored in the browser cookie after a correct PIN. */
    public static String token(Context c) {
        String t = sp(c).getString("token", "");
        if (t.isEmpty()) {
            t = resetToken(c);
        }
        return t;
    }

    public static String resetToken(Context c) {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        String t = sb.toString();
        sp(c).edit().putString("token", t).apply();
        return t;
    }

    /** "" = internal storage, otherwise the root path of a memory card. */
    public static String storage(Context c) {
        return sp(c).getString("storage", "");
    }

    public static void setStorage(Context c, String v) {
        sp(c).edit().putString("storage", v).apply();
    }

    public static boolean bootStart(Context c) {
        return sp(c).getBoolean("bootStart", true);
    }

    public static void setBootStart(Context c, boolean v) {
        sp(c).edit().putBoolean("bootStart", v).apply();
    }

    public static boolean dimScreen(Context c) {
        return sp(c).getBoolean("dimScreen", false);
    }

    public static void setDimScreen(Context c, boolean v) {
        sp(c).edit().putBoolean("dimScreen", v).apply();
    }

    /** How long freshly uploaded files stay on the web page's home screen, seconds (0 = off). */
    public static int recentSec(Context c) {
        return sp(c).getInt("recentSec", 30);
    }

    public static void setRecentSec(Context c, int v) {
        sp(c).edit().putInt("recentSec", v).apply();
    }

    /** Address encoded in the printed QR code ("10.0.2.200:8080"), "" = never printed. */
    public static String printedHost(Context c) {
        return sp(c).getString("printedHost", "");
    }

    public static void setPrintedHost(Context c, String v) {
        sp(c).edit().putString("printedHost", v).apply();
    }

    /** True after the user pressed «Stop» — then we don't restart by ourselves. */
    public static boolean userStopped(Context c) {
        return sp(c).getBoolean("userStopped", false);
    }

    public static void setUserStopped(Context c, boolean v) {
        sp(c).edit().putBoolean("userStopped", v).apply();
    }
}
