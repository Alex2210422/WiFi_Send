package com.alex.wifisend;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notices files that appear in the share folder from outside the web page —
 * mostly files copied from a computer over USB (MTP).
 *
 * Polls the folder tree every second (inotify is unreliable for files written by
 * the system MTP service on recent Android). A file is reported once its size
 * and date stopped changing, i.e. the copy has finished.
 */
public class FolderWatcher {

    public interface Callback {
        /** Called on the watcher thread with the files that just finished copying. */
        void onNewFiles(List<File> files);
    }

    private static final long INTERVAL = 1000;
    private static final int MAX_FILES = 30000;

    private final WebServer server;
    private final Callback callback;
    private volatile boolean stop;
    private Thread thread;

    /** rel path → {length, lastModified} as last seen. */
    private final Map<String, long[]> seen = new HashMap<>();
    /** rel path → time we first saw it change (waiting for the copy to finish). */
    private final Map<String, Long> pending = new HashMap<>();
    private String rootPath;

    public FolderWatcher(WebServer server, Callback callback) {
        this.server = server;
        this.callback = callback;
    }

    public void start() {
        thread = new Thread(this::loop, "folder-watcher");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public void stop() {
        stop = true;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (!stop) {
            try {
                scanOnce();
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(INTERVAL);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void scanOnce() {
        File root = server.getRoot();
        if (root == null || !root.isDirectory()) {
            return;
        }
        String rp = root.getAbsolutePath();
        boolean first = !rp.equals(rootPath);
        if (first) {
            rootPath = rp;
            seen.clear();
            pending.clear();
        }
        long now = System.currentTimeMillis();
        Set<String> present = new HashSet<>();
        List<File> ready = new ArrayList<>();
        List<File> stack = new ArrayList<>();
        stack.add(root);
        int count = 0;
        while (!stack.isEmpty() && count < MAX_FILES) {
            File dir = stack.remove(stack.size() - 1);
            File[] kids = dir.listFiles();
            if (kids == null) {
                continue;
            }
            for (File f : kids) {
                String name = f.getName();
                if (name.startsWith(".")) {
                    continue; // hidden + our own temp files while uploading
                }
                if (f.isDirectory()) {
                    stack.add(f);
                    continue;
                }
                if (++count > MAX_FILES) {
                    break;
                }
                String rel = f.getAbsolutePath().substring(rp.length() + 1)
                        .replace(File.separatorChar, '/');
                present.add(rel);
                long[] cur = {f.length(), f.lastModified()};
                long[] old = seen.get(rel);
                if (first) {
                    seen.put(rel, cur);
                    continue;
                }
                if (old == null || old[0] != cur[0] || old[1] != cur[1]) {
                    seen.put(rel, cur);
                    pending.put(rel, now);
                    continue;
                }
                Long since = pending.get(rel);
                if (since != null && now - since >= INTERVAL) {
                    pending.remove(rel);
                    if (!server.isOwn(rel)) {
                        server.addRecent(rel);
                        ready.add(f);
                    }
                }
            }
        }
        if (count <= MAX_FILES) {
            Iterator<String> it = seen.keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (!present.contains(k)) {
                    it.remove();
                    pending.remove(k);
                }
            }
        }
        if (!ready.isEmpty() && callback != null) {
            callback.onNewFiles(ready);
        }
    }
}
