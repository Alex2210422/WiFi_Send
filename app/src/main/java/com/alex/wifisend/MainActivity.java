package com.alex.wifisend;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Main screen: big QR code with the address, status and a Start/Stop button. */
public class MainActivity extends Activity {

    private Ui ui;
    private TextView status;
    private LinearLayout setupBox;
    private LinearLayout qrCard;
    private ImageView qrImage;
    private TextView qrCaption;
    private TextView orText;
    private TextView urlView;
    private TextView infoView;
    private Button toggle;
    private Button bgWarning;
    private Button addrWarning;
    private String shownUrl = "";
    private boolean autoStarted;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };
    private final Runnable dimmer = new Runnable() {
        @Override
        public void run() {
            setBrightness(0.02f);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.lang = Prefs.lang(this);
        ui = new Ui(this);
        ui.window(this);
        // A fresh open always starts the server; a recreate (language/theme) respects «Stop».
        autoStarted = savedInstanceState != null && Prefs.userStopped(this);
        build();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = ui.dp(22);
        root.setPadding(pad, ui.dp(18), pad, pad);
        sv.addView(root);
        setContentView(sv);

        // header: title + language + theme
        LinearLayout header = ui.row();
        TextView title = ui.text("📶 WiFi Send", 22, true, ui.text);
        header.addView(title, Ui.weight1());
        Button lang = ui.chip(Util.ru() ? "RU" : "EN");
        lang.setOnClickListener(v -> {
            Prefs.setLang(this, Util.ru() ? "en" : "ru");
            recreate();
        });
        header.addView(lang);
        Button theme = ui.chip(ui.themeIcon());
        theme.setOnClickListener(v -> {
            Prefs.setTheme(this, Ui.nextTheme(Prefs.theme(this)));
            recreate();
        });
        header.addView(theme);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // status pill
        status = ui.text("", 15, true, ui.green);
        status.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = ui.dp(10);
        root.addView(status, sp);

        // first-run box: file access is required
        setupBox = ui.card();
        TextView st = ui.text(Util.t(
                "Чтобы начать, разрешите приложению доступ к файлам — там будет папка обмена «WiFiSend».",
                "To start, allow file access — the shared folder «WiFiSend» lives there."),
                15, false, ui.text);
        setupBox.addView(st);
        Button allow = ui.button(Util.t("🔓 Разрешить доступ к файлам", "🔓 Allow file access"), 1);
        allow.setOnClickListener(v -> Perms.requestStorage(this));
        setupBox.addView(allow);
        root.addView(setupBox);

        // QR card
        qrCard = new LinearLayout(this);
        qrCard.setOrientation(LinearLayout.VERTICAL);
        qrCard.setGravity(Gravity.CENTER_HORIZONTAL);
        qrCard.setBackground(ui.round(Color.WHITE, 22, ui.dark ? 0 : ui.line));
        qrCard.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(14));
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qp.topMargin = ui.dp(18);
        qrImage = new ImageView(this);
        int qrSize = Math.min(ui.dp(280), getResources().getDisplayMetrics().widthPixels - ui.dp(100));
        qrCard.addView(qrImage, new LinearLayout.LayoutParams(qrSize, qrSize));
        qrCaption = ui.text(Util.t("Наведите камеру телефона", "Point your phone's camera"),
                14, false, Color.rgb(107, 118, 134));
        qrCaption.setPadding(0, ui.dp(10), 0, 0);
        qrCard.addView(qrCaption);
        root.addView(qrCard, qp);

        orText = ui.text(Util.t("или введите в браузере:", "or type in the browser:"), 14, false, ui.muted);
        orText.setPadding(0, ui.dp(16), 0, 0);
        root.addView(orText);
        urlView = ui.text("", 26, true, ui.accent);
        urlView.setTextIsSelectable(true);
        urlView.setGravity(Gravity.CENTER);
        root.addView(urlView);
        infoView = ui.text("", 13, false, ui.muted);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, ui.dp(4), 0, 0);
        root.addView(infoView);

        toggle = ui.button("", 0);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

        addrWarning = ui.button("", 2);
        addrWarning.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(addrWarning);

        bgWarning = ui.button(Util.t("⚠️ Разрешите работу в фоне", "⚠️ Allow background work"), 2);
        bgWarning.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(bgWarning);

        TextView hint = ui.text(Util.t(
                "💡 Телефон и планшет — в одной Wi‑Fi сети. Файлы лежат в папке «WiFiSend» — "
                        + "через кабель она видна и на компьютере (обновите папку F5).",
                "💡 Phone and tablet on the same Wi‑Fi. Files are in the «WiFiSend» folder — "
                        + "over a cable it's visible on the computer too (refresh with F5)."),
                13, false, ui.muted);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(ui.dp(6), ui.dp(16), ui.dp(6), 0);
        root.addView(hint);

        Button settings = ui.button(Util.t("⚙️ Настройки и помощь", "⚙️ Settings & help"), 0);
        settings.setTextColor(ui.muted);
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settings);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!autoStarted && !ServerService.running && Util.hasStorageAccess(this)) {
            autoStarted = true;
            try {
                ServerService.start(this);
            } catch (Exception ignored) {
                // shown as «stopped»
            }
        }
        if (Prefs.dimScreen(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            scheduleDim();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            handler.removeCallbacks(dimmer);
            setBrightness(-1f);
        }
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(dimmer);
        setBrightness(-1f);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (Prefs.dimScreen(this)) {
            setBrightness(-1f);
            scheduleDim();
        }
    }

    private void scheduleDim() {
        handler.removeCallbacks(dimmer);
        handler.postDelayed(dimmer, 30000);
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness != v) {
            lp.screenBrightness = v;
            getWindow().setAttributes(lp);
        }
    }

    private void refresh() {
        boolean access = Util.hasStorageAccess(this);
        setupBox.setVisibility(access ? View.GONE : View.VISIBLE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean bgOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        bgWarning.setVisibility(access && !bgOk ? View.VISIBLE : View.GONE);

        boolean running = ServerService.running;
        String hp = running ? Util.hostPort(ServerService.port) : null;
        boolean showQr = running && hp != null;

        qrCard.setVisibility(showQr ? View.VISIBLE : View.GONE);
        String printed = Prefs.printedHost(this);
        boolean moved = showQr && !printed.isEmpty() && !printed.equals(hp);
        addrWarning.setVisibility(moved ? View.VISIBLE : View.GONE);
        if (moved) {
            addrWarning.setText(Util.t("⚠️ Адрес изменился — распечатанный QR (" + printed + ") не сработает. Нажмите, чтобы исправить",
                    "⚠️ Address changed — the printed QR (" + printed + ") won't work. Tap to fix"));
        }
        orText.setVisibility(showQr ? View.VISIBLE : View.GONE);
        toggle.setVisibility(access ? View.VISIBLE : View.GONE);

        if (running) {
            if (hp != null) {
                status.setText(Util.t("● Работает в фоне", "● Running in background"));
                status.setTextColor(ui.green);
                status.setBackground(ui.round(ui.greenBg, 20, 0));
                String url = "http://" + hp;
                if (!url.equals(shownUrl)) {
                    Bitmap qr = qr(url, 512);
                    if (qr != null) {
                        qrImage.setImageBitmap(qr);
                    }
                    shownUrl = url;
                }
                StringBuilder sb = new StringBuilder(hp);
                List<String> ips = Util.ipv4();
                for (int i = 1; i < ips.size(); i++) {
                    sb.append("\n").append(ips.get(i)).append(':').append(ServerService.port);
                }
                urlView.setText(sb.toString());
            } else {
                status.setText(Util.t("● Нет Wi‑Fi — жду сеть", "● No Wi‑Fi — waiting"));
                status.setTextColor(ui.orange);
                status.setBackground(ui.round(ui.orangeBg, 20, 0));
                urlView.setText(Util.t("Подключите планшет к Wi‑Fi", "Connect the tablet to Wi‑Fi"));
                shownUrl = "";
            }
            int n = ServerService.activeClients();
            infoView.setText("📁 " + Util.root(this).getName()
                    + (Prefs.storage(this).isEmpty() ? "" : " (SD)")
                    + "   ·   📱 " + Util.t("Подключено: ", "Connected: ") + n
                    + (Prefs.pinEnabled(this) ? "   ·   🔒 PIN" : ""));
            toggle.setText(Util.t("⏹ Остановить", "⏹ Stop"));
            toggle.setTextColor(ui.text);
        } else {
            String err = ServerService.lastError;
            status.setText(Util.t("● Остановлен", "● Stopped"));
            status.setTextColor(ui.muted);
            status.setBackground(ui.round(ui.card, 20, ui.line));
            urlView.setText("");
            infoView.setText(err != null && access ? err : "");
            toggle.setText(Util.t("▶️ Запустить", "▶️ Start"));
            toggle.setTextColor(ui.accent);
            shownUrl = "";
        }
    }

    private void onToggle() {
        if (ServerService.running) {
            ServerService.stop(this);
        } else {
            if (!Util.hasStorageAccess(this)) {
                Perms.requestStorage(this);
                return;
            }
            try {
                ServerService.start(this);
            } catch (Exception e) {
                ServerService.lastError = e.getMessage();
            }
        }
        handler.postDelayed(this::refresh, 700);
    }

    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, w, 0, 0, w, h);
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
