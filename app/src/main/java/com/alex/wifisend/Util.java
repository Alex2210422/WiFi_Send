package com.alex.wifisend;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** Small helpers: language, storage, network addresses, formatting. */
public final class Util {

    /** "auto", "ru" or "en" — set from Prefs at startup. */
    public static volatile String lang = "auto";

    private Util() {
    }

    public static boolean ru() {
        if ("ru".equals(lang)) {
            return true;
        }
        if ("en".equals(lang)) {
            return false;
        }
        String l = Locale.getDefault().getLanguage();
        return l.equals("ru") || l.equals("uk") || l.equals("be");
    }

    public static String t(String ru, String en) {
        return ru() ? ru : en;
    }

    // ------------------------------------------------------------------ storage

    @SuppressWarnings("deprecation")
    public static File internalBase() {
        return Environment.getExternalStorageDirectory();
    }

    /** Base storage chosen in settings (internal or memory card). */
    public static File base(Context c) {
        String s = Prefs.storage(c);
        if (!s.isEmpty()) {
            File f = new File(s);
            if (f.isDirectory()) {
                return f;
            }
        }
        return internalBase();
    }

    /** Shared folder, visible on the PC as "WiFiSend". */
    public static File root(Context c) {
        return new File(base(c), "WiFiSend");
    }

    /** Available storages: {path ("" = internal), label}. */
    public static List<String[]> volumes(Context c) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"", t("📱 Встроенная память", "📱 Internal storage")});
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                StorageManager sm = c.getSystemService(StorageManager.class);
                if (sm != null) {
                    for (StorageVolume v : sm.getStorageVolumes()) {
                        if (v.isPrimary()) {
                            continue;
                        }
                        File d = v.getDirectory();
                        if (d != null && Environment.MEDIA_MOUNTED.equals(v.getState())) {
                            out.add(new String[]{d.getAbsolutePath(),
                                    "💾 " + v.getDescription(c) + " (" + d.getName() + ")"});
                        }
                    }
                }
            } else {
                File[] dirs = c.getExternalFilesDirs(null);
                for (int i = 1; i < dirs.length; i++) {
                    if (dirs[i] == null) {
                        continue;
                    }
                    String p = dirs[i].getAbsolutePath();
                    int idx = p.indexOf("/Android/");
                    if (idx > 0) {
                        out.add(new String[]{p.substring(0, idx), t("💾 Карта памяти", "💾 SD card")});
                    }
                }
            }
        } catch (Exception ignored) {
            // only internal storage
        }
        return out;
    }

    public static boolean hasStorageAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ network

    /** IPv4 addresses of Wi-Fi / hotspot / LAN interfaces, best first. */
    public static List<String> ipv4() {
        List<String> preferred = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) {
                return preferred;
            }
            for (NetworkInterface ni : Collections.list(en)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName().toLowerCase(Locale.ROOT);
                boolean wifi = name.startsWith("wlan") || name.startsWith("ap")
                        || name.startsWith("swlan") || name.startsWith("eth");
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (wifi) {
                        preferred.add(ip);
                    } else if (a.isSiteLocalAddress()) {
                        other.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
            // no network info available
        }
        preferred.addAll(other);
        return preferred;
    }

    /** "192.168.1.25:8080" or null when there is no network. */
    public static String hostPort(int port) {
        List<String> ips = ipv4();
        if (ips.isEmpty()) {
            return null;
        }
        return port == 80 ? ips.get(0) : ips.get(0) + ":" + port;
    }

    public static String urls(int port) {
        List<String> ips = ipv4();
        if (ips.isEmpty()) {
            return t("Нет Wi-Fi подключения", "No Wi-Fi connection");
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("http://").append(ip);
            if (port != 80) {
                sb.append(':').append(port);
            }
        }
        return sb.toString();
    }

    public static String size(long b) {
        if (b < 1024) {
            return b + " B";
        }
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b;
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }

    public static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }
}
