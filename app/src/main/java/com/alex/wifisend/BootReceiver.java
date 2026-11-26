package com.alex.wifisend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the server after the tablet is switched on, and again after an app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) {
            return;
        }
        Util.lang = Prefs.lang(c);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.QUICKBOOT_POWERON".equals(a)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot && !updated) {
            return;
        }
        if (boot && !Prefs.bootStart(c)) {
            return;
        }
        if (updated && Prefs.userStopped(c)) {
            return;
        }
        if (!Util.hasStorageAccess(c)) {
            return;
        }
        Intent s = new Intent(c, ServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception ignored) {
            // the system refused; the user can open the app
        }
    }
}
