package com.alex.wifisend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** «Stop» button in the notification. */
public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        try {
            ServerService.stop(c);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
