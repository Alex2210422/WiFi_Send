package com.alex.wifisend;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tiny HTTP server without external libraries.
 * Serves the web page and a small JSON API to list / upload / download / rename / delete
 * files inside one shared folder. Uploads use a raw PUT body (no multipart parsing).
 */
public class WebServer {

    public interface Listener {
        void onUploaded(String name, long size, String ip);

        void onEvent(String icon, String text, String ip);
    }

    private static final String TEMP_SUFFIX = ".wifisend-part";
    private static final long CLIENT_TIMEOUT_MS = 90000;

    private final Context ctx;
    private final File root;
    private final Listener listener;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running;
    private int port;
    /** client ip -> last request time */
    private final Map<String, Long> clients = new HashMap<>();
    /** client ip -> {wrong PIN attempts, blocked until (ms)} */
    private final Map<String, long[]> fails = new HashMap<>();
    /** path|mtime -> JPEG preview */
    private final Map<String, byte[]> thumbs = new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) {
            return size() > 300;
        }
    };

    /** Latest new files: {relative path, time}. Newest last. */
    private final List<Object[]> recent = new ArrayList<>();
    /** Paths this server itself created or renamed recently — the folder watcher ignores them. */
    private final Map<String, Long> own = new HashMap<>();

    /** Number of phones / computers that used the page during the last 90 seconds. */
    public int activeClients() {
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (clients) {
            for (Long t : clients.values()) {
                if (now - t < CLIENT_TIMEOUT_MS) {
                    n++;
                }
            }
        }
        return n;
    }

    public WebServer(Context ctx, File root, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.root = root;
        this.listener = listener;
    }

    public int getPort() {
        return port;
    }

    public void start(int preferredPort) throws IOException {
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Cannot create folder " + root);
        }
        IOException last = null;
        for (int p = preferredPort; p < preferredPort + 10 && serverSocket == null; p++) {
            ServerSocket s = new ServerSocket();
            try {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                serverSocket = s;
                port = p;
            } catch (IOException e) {
                last = e;
                try {
                    s.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
        if (serverSocket == null) {
            throw last != null ? last : new IOException("No free port");
        }
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::acceptLoop, "wifisend-accept");
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                s.setSoTimeout(120000);
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) {
                    break;
                }
            } catch (Exception e) {
                // pool rejected the task while stopping
                if (!running) {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private void handle(Socket s) {
        OutputStream out = null;
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) {
                    headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT),
                            line.substring(i + 1).trim());
                }
            }
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            String ip = s.getInetAddress() != null ? s.getInetAddress().getHostAddress() : "?";
            try {
                route(method, path, parseQuery(query), headers, in, out, ip);
            } catch (SecurityException e) {
                sendError(out, 403, "forbidden");
            } catch (Exception e) {
                sendError(out, 500, String.valueOf(e.getMessage()));
            }
            out.flush();
        } catch (Exception ignored) {
            // client went away
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private void route(String method, String path, Map<String, String> q, Map<String, String> h,
                       InputStream in, OutputStream out, String ip) throws Exception {
        boolean get = method.equals("GET") || method.equals("HEAD");
        boolean post = method.equals("POST");
        if (path.equals("/") || path.equals("/index.html")) {
            if (!get) {
                sendError(out, 405, "method not allowed");
                return;
            }
            sendBytes(out, 200, "text/html; charset=utf-8", readAsset("index.html"), null);
            return;
        }
        if (!path.startsWith("/api/")) {
            sendError(out, 404, "not found");
            return;
        }
        boolean needPin = Prefs.pinEnabled(ctx);
        boolean authorized = !needPin || Prefs.token(ctx).equals(cookie(h, "ws_auth"));
        if (path.equals("/api/auth")) {
            JSONObject o = new JSONObject();
            o.put("pin", needPin);
            o.put("ok", authorized);
            sendJson(out, 200, o);
            return;
        }
        if (path.equals("/api/login")) {
            if (!post) {
                sendError(out, 405, "method not allowed");
            } else {
                login(q, out, ip);
            }
            return;
        }
        if (!authorized) {
            sendError(out, 401, "pin");
            return;
        }
        synchronized (clients) {
            clients.put(ip, System.currentTimeMillis());
        }
        switch (path) {
            case "/api/ping":
                sendOk(out);
                break;
            case "/api/info":
                info(out);
                break;
            case "/api/recent":
                recent(out);
                break;
            case "/api/list":
                list(q, out);
                break;
            case "/api/file":
                if (!get) {
                    sendError(out, 405, "method not allowed");
                } else {
                    file(method, q, h, out, ip);
                }
                break;
            case "/api/thumb":
                thumb(q, out);
                break;
            case "/api/zip":
                zip(q, out, ip);
                break;
            case "/api/upload":
                if (!method.equals("PUT") && !post) {
                    sendError(out, 405, "method not allowed");
                } else {
                    upload(q, h, in, out, ip);
                }
                break;
            case "/api/mkdir":
                if (!post) {
                    sendError(out, 405, "method not allowed");
                } else {
                    mkdir(q, out, ip);
                }
                break;
            case "/api/delete":
                if (!post) {
                    sendError(out, 405, "method not allowed");
                } else {
                    delete(q, out, ip);
                }
                break;
            case "/api/rename":
                if (!post) {
                    sendError(out, 405, "method not allowed");
                } else {
                    rename(q, out, ip);
                }
                break;
            default:
                sendError(out, 404, "not found");
        }
    }

    // ------------------------------------------------------------------ PIN

    private void login(Map<String, String> q, OutputStream out, String ip) throws Exception {
        long now = System.currentTimeMillis();
        long[] f;
        synchronized (fails) {
            f = fails.get(ip);
            if (f == null) {
                f = new long[]{0, 0};
                fails.put(ip, f);
            }
            if (f[1] > now) {
                JSONObject o = new JSONObject();
                o.put("error", "wait");
                o.put("wait", (f[1] - now + 999) / 1000);
                sendJson(out, 429, o);
                return;
            }
        }
        String pin = q.get("pin");
        if (Prefs.pinEnabled(ctx) && pin != null && pin.equals(Prefs.pin(ctx))) {
            synchronized (fails) {
                fails.remove(ip);
            }
            Map<String, String> extra = new LinkedHashMap<>();
            extra.put("Set-Cookie", "ws_auth=" + Prefs.token(ctx)
                    + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax");
            sendBytes(out, 200, "application/json; charset=utf-8",
                    "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), extra);
            event("🔓", Util.t("Вход по PIN", "PIN login"), ip);
            return;
        }
        synchronized (fails) {
            f[0]++;
            if (f[0] >= 5) {
                f[0] = 0;
                f[1] = now + 60000;
            }
        }
        event("⛔", Util.t("Неверный PIN", "Wrong PIN"), ip);
        sendError(out, 403, "wrong pin");
    }

    private static String cookie(Map<String, String> h, String name) {
        String c = h.get("cookie");
        if (c == null) {
            return "";
        }
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) {
                return p.substring(name.length() + 1);
            }
        }
        return "";
    }

    private void event(String icon, String text, String ip) {
        if (listener != null) {
            try {
                listener.onEvent(icon, text, ip);
            } catch (Exception ignored) {
                // never break a request because of the log
            }
        }
    }

    // ------------------------------------------------------------------ API

    private void info(OutputStream out) throws Exception {
        JSONObject o = new JSONObject();
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        o.put("folder", root.getName());
        o.put("free", root.getUsableSpace());
        o.put("total", root.getTotalSpace());
        sendJson(out, 200, o);
    }

    /** A new file appeared (web upload or copied over USB): show it in «Just received». */
    public void addRecent(String rel) {
        synchronized (recent) {
            for (int i = recent.size() - 1; i >= 0; i--) {
                if (rel.equals(recent.get(i)[0])) {
                    recent.remove(i);
                }
            }
            recent.add(new Object[]{rel, System.currentTimeMillis()});
            while (recent.size() > 20) {
                recent.remove(0);
            }
        }
    }

    private void markOwn(String rel) {
        synchronized (own) {
            long now = System.currentTimeMillis();
            own.put(rel, now);
            if (own.size() > 500) {
                Iterator<Map.Entry<String, Long>> it = own.entrySet().iterator();
                while (it.hasNext()) {
                    if (now - it.next().getValue() > 120_000) {
                        it.remove();
                    }
                }
            }
        }
    }

    /** True if the server itself wrote / renamed this path in the last two minutes. */
    public boolean isOwn(String rel) {
        synchronized (own) {
            Long t = own.get(rel);
            return t != null && System.currentTimeMillis() - t < 120_000;
        }
    }

    public File getRoot() {
        return root;
    }

    /**
     * Files uploaded a moment ago, for the «Just received» block on the home screen.
     * The block stays while less than recentSec seconds passed since the LAST upload.
     */
    private void recent(OutputStream out) throws Exception {
        int win = Prefs.recentSec(ctx);
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        JSONArray arr = new JSONArray();
        long left = 0;
        List<Object[]> copy;
        synchronized (recent) {
            copy = new ArrayList<>(recent);
        }
        if (win > 0 && !copy.isEmpty()) {
            long last = (Long) copy.get(copy.size() - 1)[1];
            left = last + win * 1000L - now;
            if (left > 0) {
                for (int i = copy.size() - 1; i >= 0 && arr.length() < 5; i--) {
                    String rp = (String) copy.get(i)[0];
                    File f;
                    try {
                        f = resolve(rp);
                    } catch (IOException e) {
                        continue;
                    }
                    if (!f.isFile()) {
                        continue;
                    }
                    JSONObject it = new JSONObject();
                    it.put("path", rp);
                    it.put("name", f.getName());
                    it.put("size", f.length());
                    it.put("mtime", f.lastModified());
                    it.put("ago", Math.max(0, (now - (Long) copy.get(i)[1]) / 1000));
                    arr.put(it);
                }
            }
        }
        o.put("left", arr.length() > 0 ? Math.max(0, left / 1000) + 1 : 0);
        o.put("leftMs", arr.length() > 0 ? Math.max(0, left) : 0);
        o.put("win", win);
        o.put("items", arr);
        sendJson(out, 200, o);
    }

    private void list(Map<String, String> q, OutputStream out) throws Exception {
        File dir = resolve(q.get("path"));
        if (!dir.isDirectory()) {
            sendError(out, 404, "not a folder");
            return;
        }
        JSONArray arr = new JSONArray();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(TEMP_SUFFIX)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", n);
                o.put("dir", f.isDirectory());
                o.put("size", f.isDirectory() ? 0 : f.length());
                o.put("mtime", f.lastModified());
                arr.put(o);
            }
        }
        JSONObject res = new JSONObject();
        res.put("path", rel(dir));
        res.put("items", arr);
        sendJson(out, 200, res);
    }

    private void file(String method, Map<String, String> q, Map<String, String> h,
                      OutputStream out, String ip) throws Exception {
        File f = resolve(q.get("path"));
        if (!f.isFile()) {
            sendError(out, 404, "file not found");
            return;
        }
        long len = f.length();
        long start = 0;
        long end = len - 1;
        boolean partial = false;
        String range = h.get("range");
        if (range != null && range.startsWith("bytes=") && len > 0) {
            String r = range.substring(6);
            int comma = r.indexOf(',');
            if (comma >= 0) {
                r = r.substring(0, comma);
            }
            int dash = r.indexOf('-');
            if (dash >= 0) {
                try {
                    String a = r.substring(0, dash).trim();
                    String b = r.substring(dash + 1).trim();
                    if (a.isEmpty()) {
                        long n = Long.parseLong(b);
                        start = Math.max(0, len - n);
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) {
                            end = Math.min(Long.parseLong(b), len - 1);
                        }
                    }
                    partial = true;
                } catch (NumberFormatException ignored) {
                    start = 0;
                    end = len - 1;
                }
            }
            if (partial && (start > end || start >= len)) {
                Map<String, String> hh = new LinkedHashMap<>();
                hh.put("Content-Range", "bytes */" + len);
                hh.put("Content-Length", "0");
                writeHead(out, 416, hh);
                return;
            }
        }
        long count = len == 0 ? 0 : end - start + 1;
        boolean download = "1".equals(q.get("dl"));
        Map<String, String> hh = new LinkedHashMap<>();
        // iOS Safari opens PDFs / pictures in place even with «attachment»;
        // a neutral type makes it really download the file.
        hh.put("Content-Type", download ? "application/octet-stream" : mimeOf(f.getName()));
        hh.put("Content-Length", String.valueOf(count));
        hh.put("Accept-Ranges", "bytes");
        if (partial) {
            hh.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
        }
        hh.put("Content-Disposition", (download ? "attachment" : "inline")
                + "; filename*=UTF-8''" + encode(f.getName()));
        hh.put("Cache-Control", "no-store");
        writeHead(out, partial ? 206 : 200, hh);
        if (method.equals("HEAD") || count == 0) {
            return;
        }
        if (start == 0) {
            event(download ? "⬇️" : "👁️", rel(f) + " (" + Util.size(len) + ")", ip);
        }
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                left -= n;
            }
        } finally {
            raf.close();
        }
    }

    private void upload(Map<String, String> q, Map<String, String> h, InputStream in,
                        OutputStream out, String ip) throws Exception {
        File dir = resolve(q.get("path"));
        if (!dir.isDirectory()) {
            sendError(out, 404, "not a folder");
            return;
        }
        String name = sanitize(q.get("name"));
        if (name.isEmpty()) {
            sendError(out, 400, "bad name");
            return;
        }
        String cl = h.get("content-length");
        if (cl == null) {
            sendError(out, 411, "length required");
            return;
        }
        long len = Long.parseLong(cl.trim());
        if (len > root.getUsableSpace()) {
            sendError(out, 507, "not enough space");
            return;
        }
        String expect = h.get("expect");
        if (expect != null && expect.toLowerCase(Locale.ROOT).contains("100-continue")) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        File target = unique(dir, name);
        File tmp = new File(dir, "." + target.getName() + TEMP_SUFFIX);
        try {
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) {
                        throw new IOException("Upload interrupted");
                    }
                    fos.write(buf, 0, n);
                    left -= n;
                }
                fos.getFD().sync();
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (target.exists()) {
            target = unique(dir, name);
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Cannot save file");
        }
        scan(target.getAbsolutePath());
        addRecent(rel(target));
        markOwn(rel(target));
        if (listener != null) {
            listener.onUploaded(rel(target), len, ip);
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("name", target.getName());
        sendJson(out, 200, o);
    }

    private void mkdir(Map<String, String> q, OutputStream out, String ip) throws Exception {
        File dir = resolve(q.get("path"));
        String name = sanitize(q.get("name"));
        if (!dir.isDirectory() || name.isEmpty()) {
            sendError(out, 400, "bad request");
            return;
        }
        File nd = new File(dir, name);
        if (nd.exists()) {
            sendError(out, 409, "exists");
            return;
        }
        if (!nd.mkdirs()) {
            sendError(out, 500, "cannot create folder");
            return;
        }
        scan(nd.getAbsolutePath());
        event("📁", Util.t("Новая папка: ", "New folder: ") + rel(nd), ip);
        sendOk(out);
    }

    private void delete(Map<String, String> q, OutputStream out, String ip) throws Exception {
        File f = resolve(q.get("path"));
        if (isRoot(f)) {
            sendError(out, 403, "forbidden");
            return;
        }
        if (!f.exists()) {
            sendError(out, 404, "not found");
            return;
        }
        String name = rel(f);
        List<String> paths = new ArrayList<>();
        deleteRecursive(f, paths);
        scan(paths.toArray(new String[0]));
        event("🗑️", Util.t("Удалено: ", "Deleted: ") + name, ip);
        sendOk(out);
    }

    private void rename(Map<String, String> q, OutputStream out, String ip) throws Exception {
        File f = resolve(q.get("path"));
        String name = sanitize(q.get("name"));
        if (isRoot(f) || name.isEmpty()) {
            sendError(out, 400, "bad request");
            return;
        }
        if (!f.exists()) {
            sendError(out, 404, "not found");
            return;
        }
        File nf = new File(f.getParentFile(), name);
        if (nf.exists()) {
            sendError(out, 409, "exists");
            return;
        }
        String oldName = rel(f);
        List<String> paths = new ArrayList<>();
        collect(f, paths);
        if (!f.renameTo(nf)) {
            sendError(out, 500, "cannot rename");
            return;
        }
        collect(nf, paths);
        for (String ap : paths) {
            try {
                markOwn(rel(new File(ap)));
            } catch (IOException ignored) {
            }
        }
        scan(paths.toArray(new String[0]));
        event("✏️", oldName + " → " + nf.getName(), ip);
        sendOk(out);
    }

    private void thumb(Map<String, String> q, OutputStream out) throws Exception {
        File f = resolve(q.get("path"));
        if (!f.isFile()) {
            sendError(out, 404, "not found");
            return;
        }
        String key = f.getAbsolutePath() + "|" + f.lastModified();
        byte[] data;
        synchronized (thumbs) {
            data = thumbs.get(key);
        }
        if (data == null) {
            data = Thumbs.make(f, 160);
            if (data == null) {
                sendError(out, 404, "no preview");
                return;
            }
            synchronized (thumbs) {
                thumbs.put(key, data);
            }
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "image/jpeg");
        h.put("Content-Length", String.valueOf(data.length));
        h.put("Cache-Control", "max-age=86400");
        writeHead(out, 200, h);
        out.write(data);
    }

    /** Several files / folders as one ZIP archive (streamed, no temp file). */
    private void zip(Map<String, String> q, OutputStream out, String ip) throws Exception {
        String paths = q.get("paths");
        List<File> files = new ArrayList<>();
        if (paths != null) {
            for (String p : paths.split("\n")) {
                if (p.trim().isEmpty()) {
                    continue;
                }
                File f = resolve(p);
                if (f.exists() && !isRoot(f)) {
                    files.add(f);
                }
            }
        }
        if (files.isEmpty()) {
            sendError(out, 404, "nothing to download");
            return;
        }
        String name;
        if (files.size() == 1) {
            name = files.get(0).getName() + ".zip";
        } else {
            name = "WiFiSend-" + new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                    .format(new java.util.Date()) + ".zip";
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/zip");
        h.put("Content-Disposition", "attachment; filename*=UTF-8''" + encode(name));
        h.put("Cache-Control", "no-store");
        writeHead(out, 200, h);
        ZipOutputStream zos = new ZipOutputStream(out);
        zos.setLevel(1);
        int[] count = {0};
        for (File f : files) {
            addToZip(zos, f, f.getName(), count);
        }
        zos.finish();
        out.flush();
        event("🗜️", Util.t("ZIP: файлов ", "ZIP: files ") + count[0] + " (" + name + ")", ip);
    }

    private static void addToZip(ZipOutputStream zos, File f, String entry, int[] count) throws IOException {
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entry + "/"));
            zos.closeEntry();
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    if (!c.getName().endsWith(TEMP_SUFFIX)) {
                        addToZip(zos, c, entry + "/" + c.getName(), count);
                    }
                }
            }
            return;
        }
        ZipEntry ze = new ZipEntry(entry);
        ze.setTime(f.lastModified());
        zos.putNextEntry(ze);
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        zos.closeEntry();
        count[0]++;
    }

    // ------------------------------------------------------------------ helpers

    private File resolve(String relPath) throws IOException {
        if (relPath == null) {
            relPath = "";
        }
        String rootCanon = root.getCanonicalPath();
        File f = new File(rootCanon, relPath);
        String canon = f.getCanonicalPath();
        if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
            throw new SecurityException("outside of shared folder");
        }
        return new File(canon);
    }

    private boolean isRoot(File f) throws IOException {
        return f.getCanonicalPath().equals(root.getCanonicalPath());
    }

    private String rel(File f) throws IOException {
        String r = root.getCanonicalPath();
        String p = f.getCanonicalPath();
        if (p.equals(r)) {
            return "";
        }
        return p.substring(r.length() + 1).replace(File.separatorChar, '/');
    }

    private static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String s = sb.toString().trim();
        if (s.equals(".") || s.equals("..")) {
            return "";
        }
        if (s.length() > 200) {
            s = s.substring(s.length() - 200);
        }
        return s;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) {
                return f;
            }
        }
        return new File(dir, base + " (" + System.currentTimeMillis() + ")" + ext);
    }

    private static void deleteRecursive(File f, List<String> paths) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c, paths);
                }
            }
        }
        paths.add(f.getAbsolutePath());
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void collect(File f, List<String> paths) {
        paths.add(f.getAbsolutePath());
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    collect(c, paths);
                }
            }
        }
    }

    /** Tell Android about changed files so the PC (MTP) sees them after a refresh. */
    private void scan(String... paths) {
        if (paths.length == 0) {
            return;
        }
        try {
            MediaScannerConnection.scanFile(ctx, paths, null, null);
        } catch (Exception ignored) {
            // scanning is best effort
        }
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) {
                if (m.startsWith("text/")) {
                    return m + "; charset=utf-8";
                }
                return m;
            }
        }
        return "application/octet-stream";
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "file";
        }
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int i = pair.indexOf('=');
            String k = i >= 0 ? pair.substring(0, i) : pair;
            String v = i >= 0 ? pair.substring(i + 1) : "";
            map.put(decode(k), decode(v));
        }
        return map;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 16384) {
                throw new IOException("Line too long");
            }
        }
        if (c == -1 && b.size() == 0) {
            return null;
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 507: return "Insufficient Storage";
            default: return "Error";
        }
    }

    private static void writeHead(OutputStream out, int code, Map<String, String> h)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        for (Map.Entry<String, String> e : h.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(OutputStream out, int code, String type, byte[] body,
                                  Map<String, String> extra) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", type);
        h.put("Content-Length", String.valueOf(body.length));
        h.put("Cache-Control", "no-store");
        if (extra != null) {
            h.putAll(extra);
        }
        writeHead(out, code, h);
        out.write(body);
    }

    private static void sendJson(OutputStream out, int code, JSONObject o) throws IOException {
        sendBytes(out, code, "application/json; charset=utf-8",
                o.toString().getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendOk(OutputStream out) throws IOException {
        sendBytes(out, 200, "application/json; charset=utf-8",
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null);
    }

    private static void sendError(OutputStream out, int code, String msg) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("error", msg);
        } catch (Exception ignored) {
            // ignore
        }
        sendJson(out, code, o);
    }
}
