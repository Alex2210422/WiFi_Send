package com.alex.wifisend;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/** Small journal: who uploaded / downloaded / deleted what and when. Keeps the last 300 lines. */
public final class EventLog {

    private static final int MAX = 300;
    private static final LinkedList<String> LINES = new LinkedList<>();
    private static boolean loaded;

    private EventLog() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "log.txt");
    }

    public static synchronized void add(Context c, String icon, String text, String ip) {
        load(c);
        String time = new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date());
        String line = time + "  " + icon + " " + text + (ip != null ? "  · " + ip : "");
        LINES.addFirst(line);
        while (LINES.size() > MAX) {
            LINES.removeLast();
        }
        save(c);
    }

    public static synchronized List<String> all(Context c) {
        load(c);
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear(Context c) {
        LINES.clear();
        loaded = true;
        save(c);
    }

    private static void load(Context c) {
        if (loaded) {
            return;
        }
        loaded = true;
        File f = file(c);
        if (!f.exists()) {
            return;
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null && LINES.size() < MAX) {
                    if (!line.isEmpty()) {
                        LINES.add(line);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
            // start with an empty log
        }
    }

    private static void save(Context c) {
        try {
            Writer w = new OutputStreamWriter(new FileOutputStream(file(c)), StandardCharsets.UTF_8);
            try {
                for (String l : LINES) {
                    w.write(l);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
            // log is best effort
        }
    }
}
