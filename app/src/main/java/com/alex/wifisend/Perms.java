package com.alex.wifisend;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** Opens the right system screens for permissions (incl. Xiaomi specific ones). */
final class Perms {

    private Perms() {
    }

    static void requestStorage(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!tryStart(a, new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())))) {
                tryStart(a, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            a.requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    static boolean batteryOk(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void requestBattery(Activity a) {
        if (!tryStart(a, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + a.getPackageName())))) {
            tryStart(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    static boolean notificationsOk(Context c) {
        return Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void requestNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    static void openAppDetails(Activity a) {
        tryStart(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + a.getPackageName())));
    }

    /** Xiaomi / Redmi / POCO: «Autostart» screen of the Security app. */
    static void openXiaomiAutostart(Activity a) {
        Intent i = new Intent();
        i.setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (!tryStart(a, i)) {
            openAppDetails(a);
        }
    }

    /** Xiaomi / Redmi / POCO: per-app battery saver («No restrictions»). */
    static void openXiaomiBattery(Activity a) {
        Intent i = new Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY");
        i.putExtra("package_name", a.getPackageName());
        i.putExtra("package_label", "WiFi Send");
        if (!tryStart(a, i)) {
            openAppDetails(a);
        }
    }

    private static boolean tryStart(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
