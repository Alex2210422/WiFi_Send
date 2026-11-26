package com.alex.wifisend;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;

/** Foreground service that keeps the web server running in the background 24/7. */
public class ServerService extends Service {

    public static final String ACTION_STOP = "com.alex.wifisend.STOP";
    public static final String ACTION_RESTART = "com.alex.wifisend.RESTART";
    private static final String CH_RUN = "run";
    private static final String CH_FILES = "files";
    private static final int NOTIF_RUN = 1;

    public static volatile boolean running = false;
    public static volatile int port = 0;
    public static volatile String lastError = null;
    private static volatile WebServer current;
    private FolderWatcher watcher;
    private static final int ID_USB = 90;

    private WebServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback netCallback;
    private int nextId = 100;

    public static int activeClients() {
        WebServer s = current;
        return s != null ? s.activeClients() : 0;
    }

    public static void start(Context c) {
        Prefs.setUserStopped(c, false);
        Intent i = new Intent(c, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void stop(Context c) {
        Prefs.setUserStopped(c, true);
        c.startService(new Intent(c, ServerService.class).setAction(ACTION_STOP));
    }

    /** Restart with new settings (e.g. another storage). */
    public static void restart(Context c) {
        if (!running) {
            return;
        }
        Intent i = new Intent(c, ServerService.class).setAction(ACTION_RESTART);
        c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Util.lang = Prefs.lang(this);
        createChannels();
        registerNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
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
        if (ACTION_RESTART.equals(action) && server != null) {
            stopWatcher();
            server.stop();
            server = null;
            current = null;
            running = false;
        }
        if (server == null) {
            if (!Util.hasStorageAccess(this)) {
                lastError = Util.t("Нет доступа к файлам", "No file access");
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                server = new WebServer(this, Util.root(this), new WebServer.Listener() {
                    @Override
                    public void onUploaded(String name, long size, String ip) {
                        uploaded(name, size, ip);
                    }

                    @Override
                    public void onEvent(String icon, String text, String ip) {
                        EventLog.add(ServerService.this, icon, text, ip);
                    }
                });
                server.start(8080);
                current = server;
                watcher = new FolderWatcher(server, this::copiedOverUsb);
                watcher.start();
                port = server.getPort();
                running = true;
                lastError = null;
                acquireLocks();
            } catch (IOException e) {
                server = null;
                current = null;
                running = false;
                lastError = e.getMessage();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Some phones (Xiaomi) kill the service when the app is swiped away — come back.
        if (running && !Prefs.userStopped(this)) {
            scheduleRestart();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        stopWatcher();
        if (server != null) {
            server.stop();
            server = null;
        }
        current = null;
        releaseLocks();
        unregisterNetwork();
        running = false;
        port = 0;
        if (wasRunning && !Prefs.userStopped(this)) {
            scheduleRestart();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleRestart() {
        try {
            Intent i = new Intent(getApplicationContext(), ServerService.class);
            PendingIntent pi;
            if (Build.VERSION.SDK_INT >= 26) {
                pi = PendingIntent.getForegroundService(this, 7, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            } else {
                pi = PendingIntent.getService(this, 7, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, pi);
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ network changes

    private void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    updateNotification();
                }

                @Override
                public void onLost(Network network) {
                    updateNotification();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    updateNotification();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback);
        } catch (Exception ignored) {
            netCallback = null;
        }
    }

    private void unregisterNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && netCallback != null) {
                cm.unregisterNetworkCallback(netCallback);
            }
        } catch (Exception ignored) {
            // ignore
        }
        netCallback = null;
    }

    // ------------------------------------------------------------------ notifications

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_RUN, n);
        }
    }

    private void updateNotification() {
        if (!running) {
            return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.notify(NOTIF_RUN, buildRunning());
            }
        } catch (Exception ignored) {
            // ignore
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
        run.setShowBadge(false);
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
        String text;
        if (running) {
            String hp = Util.hostPort(port);
            text = hp != null ? hp : Util.t("Нет Wi-Fi — жду подключения…", "No Wi-Fi — waiting…");
        } else {
            text = Util.t("Запуск…", "Starting…");
        }
        Notification.Builder b = builder(CH_RUN)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(Util.t("WiFi Send работает в фоне", "WiFi Send is running"))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp());
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                Util.t("Остановить", "Stop"), stopIntent());
        return b.build();
    }

    private synchronized void uploaded(String name, long size, String ip) {
        EventLog.add(this, "📥", name + " (" + Util.size(size) + ")", ip);
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
        if (nextId > 1000) {
            nextId = 100;
        }
    }

    private void stopWatcher() {
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
    }

    /** Files that appeared in the folder not through the web page (copied from a PC over USB). */
    private synchronized void copiedOverUsb(java.util.List<java.io.File> files) {
        long total = 0;
        for (java.io.File f : files) {
            total += f.length();
            EventLog.add(this, "🔌", Util.t("По USB: ", "Via USB: ") + f.getName()
                    + " (" + Util.size(f.length()) + ")", null);
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        String text = files.size() == 1
                ? files.get(0).getName() + " (" + Util.size(total) + ")"
                : Util.t("Файлов: ", "Files: ") + files.size() + " (" + Util.size(total) + ")";
        Notification n = builder(CH_FILES)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(Util.t("🔌 Получено по USB", "🔌 Received via USB"))
                .setContentText(text)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
        nm.notify(ID_USB, n);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent stopIntent() {
        Intent i = new Intent(this, StopReceiver.class);
        return PendingIntent.getBroadcast(this, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // ------------------------------------------------------------------ locks

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifisend:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
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
