package com.alex.wifisend;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Simple control screen: start/stop the server and show the address to open on the phone. */
public class MainActivity extends Activity {

    private TextView status;
    private TextView urlView;
    private Button toggle;
    private Button access;
    private Button battery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        ll.setPadding(pad, pad, pad, pad);
        sv.addView(ll);
        setContentView(sv);

        ll.addView(text("WiFi Send", 28, true));
        status = text("", 18, true);
        ll.addView(status);
        urlView = text("", 26, true);
        urlView.setTextIsSelectable(true);
        urlView.setTextColor(Color.rgb(25, 118, 210));
        ll.addView(urlView);

        toggle = button("");
        toggle.setOnClickListener(v -> onToggle());
        ll.addView(toggle);

        access = button(Util.t("🔓 Разрешить доступ к файлам", "🔓 Allow file access"));
        access.setOnClickListener(v -> requestAccess());
        ll.addView(access);

        battery = button(Util.t("🔋 Не усыплять приложение", "🔋 Don't put the app to sleep"));
        battery.setOnClickListener(v -> requestBattery());
        ll.addView(battery);

        ll.addView(text(Util.t(
                "Как пользоваться:\n"
                        + "1. Планшет и телефон — в одной Wi-Fi сети.\n"
                        + "2. Нажмите «Запустить».\n"
                        + "3. На телефоне откройте адрес выше в браузере (Safari, Chrome).\n\n"
                        + "Файлы хранятся в папке «WiFiSend» на планшете. Если планшет подключён "
                        + "к компьютеру кабелем (режим «Передача файлов»), эта папка видна и на "
                        + "компьютере — после загрузки просто обновите папку (F5).",
                "How to use:\n"
                        + "1. Tablet and phone on the same Wi-Fi network.\n"
                        + "2. Tap «Start».\n"
                        + "3. On the phone, open the address above in a browser.\n\n"
                        + "Files are stored in the «WiFiSend» folder on the tablet. If the tablet "
                        + "is connected to a computer by cable (File transfer mode), the folder "
                        + "is visible there too — just refresh the folder (F5)."),
                15, false));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void refresh() {
        boolean ok = Util.hasStorageAccess(this);
        access.setVisibility(ok ? View.GONE : View.VISIBLE);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        battery.setVisibility(ignoring ? View.GONE : View.VISIBLE);

        if (ServerService.running) {
            status.setText(Util.t("✅ Работает. Откройте на телефоне:",
                    "✅ Running. Open on your phone:"));
            urlView.setText(Util.urls(ServerService.port));
            toggle.setText(Util.t("⏹ Остановить", "⏹ Stop"));
        } else {
            String err = ServerService.lastError;
            status.setText(Util.t("⏸ Остановлен", "⏸ Stopped") + (err != null ? "\n" + err : ""));
            urlView.setText("");
            toggle.setText(Util.t("▶️ Запустить", "▶️ Start"));
        }
    }

    private void onToggle() {
        if (ServerService.running) {
            startService(new Intent(this, ServerService.class)
                    .setAction(ServerService.ACTION_STOP));
        } else {
            if (!Util.hasStorageAccess(this)) {
                requestAccess();
                return;
            }
            Intent i = new Intent(this, ServerService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        }
        handler.postDelayed(this::refresh, 800);
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    private void requestBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                // nothing else to try
            }
        }
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setPadding(0, dp(8), 0, dp(8));
        return tv;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }
}
