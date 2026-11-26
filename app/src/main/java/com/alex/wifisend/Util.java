package com.alex.wifisend;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

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

    private Util() {
    }

    /** Russian for ru/uk/be system languages, English otherwise. */
    public static String t(String ru, String en) {
        String l = Locale.getDefault().getLanguage();
        return (l.equals("ru") || l.equals("uk") || l.equals("be")) ? ru : en;
    }

    /** Shared folder in the tablet's main storage. Visible on the PC as "WiFiSend". */
    @SuppressWarnings("deprecation")
    public static File root() {
        return new File(Environment.getExternalStorageDirectory(), "WiFiSend");
    }

    public static boolean hasStorageAccess(Context c) {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

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
}
