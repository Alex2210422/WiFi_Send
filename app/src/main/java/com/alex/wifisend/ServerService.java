package com.alex.wifisend;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;

/** Foreground service that keeps the web server running while the screen is off. */
public class ServerService extends Service {

    public static final String ACTION_STOP = "com.alex.wifisend.STOP";
    private static final String CH_RUN = "run";
    private static final String CH_FILES = "files";
    private static final int NOTIF_RUN = 1;

    public static volatile boolean running = false;
    public static volatile int port = 0;
    public static volatile String lastError = null;

    private WebServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private int nextId = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundCompat(buildRunning());
        } catch (Exception e) {
            lastError = e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (server == null) {
            if (!Util.hasStorageAccess(this)) {
                lastError = Util.t("Нет доступа к файлам", "No file access");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                server = new WebServer(this, Util.root(), this::onUploaded);
                server.start(8080);
                port = server.getPort();
                running = true;
                lastError = null;
                acquireLocks();
            } catch (IOException e) {
                server = null;
                running = false;
                lastError = e.getMessage();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_RUN, buildRunning());
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseLocks();
        running = false;
        port = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ notifications

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_RUN, n);
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel run = new NotificationChannel(CH_RUN,
                Util.t("Сервер работает", "Server running"), NotificationManager.IMPORTANCE_LOW);
        NotificationChannel files = new NotificationChannel(CH_FILES,
                Util.t("Полученные файлы", "Received files"), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(run);
        nm.createNotificationChannel(files);
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, channel);
        }
        return new Notification.Builder(this);
    }

    @SuppressWarnings("deprecation")
    private Notification buildRunning() {
        String text = running ? Util.urls(port) : Util.t("Запуск…", "Starting…");
        Notification.Builder b = builder(CH_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(Util.t("WiFi Send работает", "WiFi Send is running"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setContentIntent(openApp());
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                Util.t("Остановить", "Stop"), stopIntent());
        return b.build();
    }

    private synchronized void onUploaded(String name, long size) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        Notification n = builder(CH_FILES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(Util.t("📥 Получен файл", "📥 File received"))
                .setContentText(name + " (" + Util.size(size) + ")")
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        nm.notify(nextId++, n);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        return PendingIntent.getService(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------------ locks

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifisend:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wifisend");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
            // locks are best effort
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
            // ignore
        }
        wakeLock = null;
        wifiLock = null;
    }
}
