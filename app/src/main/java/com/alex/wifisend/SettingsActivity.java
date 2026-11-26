package com.alex.wifisend;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** «Settings & help»: permissions, Xiaomi tips, storage, PIN, background, look, journal, help. */
public class SettingsActivity extends Activity {

    private Ui ui;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.lang = Prefs.lang(this);
        ui = new Ui(this);
        ui.window(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(20);
        root.setPadding(pad, ui.dp(14), pad, ui.dp(28));
        sv.addView(root);
        setContentView(sv);

        LinearLayout header = ui.row();
        Button back = ui.chip("‹");
        ((LinearLayout.LayoutParams) back.getLayoutParams()).leftMargin = 0;
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = ui.text(Util.t("Настройки и помощь", "Settings & help"), 20, true, ui.text);
        title.setPadding(ui.dp(12), 0, 0, 0);
        header.addView(title, Ui.weight1());
        root.addView(header);

        // ---- permissions
        section(Util.t("Разрешения", "Permissions"));
        LinearLayout perms = ui.card();
        permRow(perms, Util.t("🔓 Доступ к файлам", "🔓 File access"),
                Util.hasStorageAccess(this), () -> Perms.requestStorage(this));
        permRow(perms, Util.t("🔋 Работа в фоне", "🔋 Background work"),
                Perms.batteryOk(this), () -> Perms.requestBattery(this));
        permRow(perms, Util.t("🔔 Уведомления", "🔔 Notifications"),
                Perms.notificationsOk(this), () -> Perms.requestNotifications(this));
        root.addView(perms);

        // ---- Xiaomi
        if (Util.isXiaomi()) {
            section("Xiaomi / Redmi");
            LinearLayout x = ui.card();
            x.addView(note(Util.t(
                    "Xiaomi сам усыпляет приложения. Чтобы WiFi Send работал всегда, сделайте три шага:",
                    "Xiaomi puts apps to sleep. To keep WiFi Send always running, do three steps:")));
            Button auto = ui.button(Util.t("1. 🚀 Автозапуск → включить", "1. 🚀 Autostart → enable"), 0);
            auto.setOnClickListener(v -> Perms.openXiaomiAutostart(this));
            x.addView(auto);
            Button bat = ui.button(Util.t("2. 🔋 Контроль активности → «Нет ограничений»",
                    "2. 🔋 Battery saver → «No restrictions»"), 0);
            bat.setOnClickListener(v -> Perms.openXiaomiBattery(this));
            x.addView(bat);
            TextView lock = note(Util.t(
                    "3. 🔒 Откройте список недавних приложений, зажмите карточку WiFi Send "
                            + "(или потяните вниз) и нажмите на замочек — тогда очистка памяти его не закроет.",
                    "3. 🔒 Open recent apps, long-press the WiFi Send card (or pull it down) "
                            + "and tap the lock — then memory cleanup won't close it."));
            lock.setPadding(0, ui.dp(12), 0, 0);
            x.addView(lock);
            root.addView(x);
        }

        // ---- background
        section(Util.t("Работа в фоне", "Background"));
        LinearLayout bg = ui.card();
        toggleRow(bg, Util.t("Запускать при включении планшета", "Start when the tablet turns on"),
                Prefs.bootStart(this), v -> {
                    Prefs.setBootStart(this, v);
                    build();
                });
        toggleRow(bg, Util.t("Не выключать экран (затемнять через 30 с)",
                "Keep screen on (dim after 30 s)"),
                Prefs.dimScreen(this), v -> {
                    Prefs.setDimScreen(this, v);
                    build();
                });
        root.addView(bg);

        // ---- storage
        section(Util.t("Где хранить файлы", "Storage"));
        LinearLayout stor = ui.card();
        List<String[]> vols = Util.volumes(this);
        String cur = Prefs.storage(this);
        for (String[] vol : vols) {
            boolean selected = vol[0].equals(cur) || (cur.isEmpty() && vol[0].isEmpty());
            Button b = ui.button((selected ? "✅ " : "⬜ ") + vol[1], selected ? 1 : 0);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setOnClickListener(v -> {
                Prefs.setStorage(this, vol[0]);
                ServerService.restart(this);
                build();
            });
            stor.addView(b);
        }
        if (vols.size() == 1) {
            stor.addView(note(Util.t("Карта памяти не найдена.", "No memory card found.")));
        }
        TextView path = note("📁 " + Util.root(this).getAbsolutePath());
        path.setPadding(0, ui.dp(10), 0, 0);
        stor.addView(path);
        root.addView(stor);

        // ---- printable QR
        section(Util.t("🖨️ QR‑код для печати", "🖨️ QR code to print"));
        LinearLayout pr = ui.card();
        final String hp = ServerService.running ? Util.hostPort(ServerService.port) : null;
        if (hp == null) {
            pr.addView(note(Util.t("Запустите сервер и подключите планшет к Wi‑Fi.",
                    "Start the server and connect the tablet to Wi‑Fi.")));
        } else {
            ImageView qv = new ImageView(this);
            qv.setImageBitmap(MainActivity.qr("http://" + hp, 400));
            qv.setBackgroundColor(android.graphics.Color.WHITE);
            qv.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(ui.dp(150), ui.dp(150));
            qlp.gravity = Gravity.CENTER_HORIZONTAL;
            pr.addView(qv, qlp);
            TextView addr = ui.text(hp, 18, true, ui.accent);
            addr.setGravity(Gravity.CENTER);
            addr.setPadding(0, ui.dp(8), 0, 0);
            pr.addView(addr);
            Button save = ui.button(Util.t("💾 Сохранить QR в галерею", "💾 Save QR to gallery"), 1);
            save.setOnClickListener(v -> {
                boolean ok = QrPrint.saveToGallery(this, QrPrint.poster(hp),
                        "WiFiSend-QR-" + hp.replace(':', '_') + ".png");
                if (ok) {
                    Prefs.setPrintedHost(this, hp);
                }
                Toast.makeText(this, ok
                        ? Util.t("Сохранено в Галерею → Pictures/WiFiSend", "Saved to Gallery → Pictures/WiFiSend")
                        : Util.t("Не удалось сохранить", "Could not save"), Toast.LENGTH_LONG).show();
                build();
            });
            pr.addView(save);
            String ip = hp.contains(":") ? hp.substring(0, hp.indexOf(':')) : hp;
            String st = QrPrint.suggestStatic(ip);
            TextView how = note(Util.t(
                    "Чтобы распечатанный код всегда работал, задайте планшету постоянный адрес:\n"
                            + "1. Настройки → Wi‑Fi → нажмите на текущую сеть → «Настройки IP» → «Статический».\n"
                            + "2. IP‑адрес: " + st + " (как сейчас, только последнее число другое). "
                            + "Остальные поля оставьте как есть.\n"
                            + "3. Сохраните, вернитесь сюда и сохраните QR заново.\n"
                            + "Выбирайте число 200–250 — так меньше шанс, что адрес занят.",
                    "To keep a printed code working, give the tablet a fixed address:\n"
                            + "1. Settings → Wi‑Fi → tap the current network → «IP settings» → «Static».\n"
                            + "2. IP address: " + st + " (same as now, only the last number differs). "
                            + "Leave the other fields as they are.\n"
                            + "3. Save, come back here and save the QR again.\n"
                            + "Pick 200–250 — such addresses are less likely to be taken."));
            how.setPadding(0, ui.dp(12), 0, 0);
            pr.addView(how);
        }
        String printed = Prefs.printedHost(this);
        if (!printed.isEmpty()) {
            TextView pt = note(Util.t("Распечатан QR для адреса: ", "Printed QR address: ") + printed);
            pt.setPadding(0, ui.dp(10), 0, 0);
            pr.addView(pt);
            Button forget = ui.button(Util.t("Больше не следить за этим адресом", "Stop tracking this address"), 0);
            forget.setOnClickListener(v -> {
                Prefs.setPrintedHost(this, "");
                build();
            });
            pr.addView(forget);
        }
        root.addView(pr);

        // ---- security
        section(Util.t("Безопасность", "Security"));
        LinearLayout sec = ui.card();
        boolean pinOn = Prefs.pinEnabled(this);
        toggleRow(sec, Util.t("PIN‑код для входа на страничку", "PIN code to open the page"), pinOn, v -> {
            if (v) {
                askPin();
            } else {
                Prefs.setPin(this, false, "");
                build();
            }
        });
        if (pinOn) {
            Button change = ui.button(Util.t("Изменить PIN", "Change PIN"), 0);
            change.setOnClickListener(v -> askPin());
            sec.addView(change);
        }
        sec.addView(note(Util.t(
                "Нужен, если в общей сети вашу страничку может открыть кто‑то ещё. "
                        + "После ввода PIN телефон запоминается на 30 дней.",
                "Useful if others on the shared network could open your page. "
                        + "After entering the PIN the phone is remembered for 30 days.")));
        root.addView(sec);

        // ---- look
        section(Util.t("Вид", "Appearance"));
        LinearLayout look = ui.card();
        Button lang = ui.button(Util.t("🌐 Язык: Русский", "🌐 Language: English"), 0);
        lang.setOnClickListener(v -> {
            Prefs.setLang(this, Util.ru() ? "en" : "ru");
            recreate();
        });
        look.addView(lang);
        Button theme = ui.button(ui.themeIcon() + " " + Util.t("Тема: ", "Theme: ") + ui.themeName(), 0);
        theme.setOnClickListener(v -> {
            Prefs.setTheme(this, Ui.nextTheme(Prefs.theme(this)));
            recreate();
        });
        look.addView(theme);
        root.addView(look);

        // ---- journal
        section(Util.t("📜 Журнал", "📜 Journal"));
        LinearLayout log = ui.card();
        List<String> lines = EventLog.all(this);
        if (lines.isEmpty()) {
            log.addView(note(Util.t("Пока пусто.", "Nothing yet.")));
        } else {
            StringBuilder sb = new StringBuilder();
            int n = Math.min(lines.size(), 60);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
            }
            TextView tv = ui.text(sb.toString(), 12, false, ui.text);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            tv.setLineSpacing(0, 1.25f);
            log.addView(tv);
            Button clear = ui.button(Util.t("Очистить журнал", "Clear journal"), 3);
            clear.setOnClickListener(v -> {
                EventLog.clear(this);
                build();
            });
            log.addView(clear);
        }
        root.addView(log);

        // ---- help
        section(Util.t("Как пользоваться", "How to use"));
        LinearLayout help = ui.card();
        help.addView(ui.text(Util.t(
                "1. Планшет и телефон — в одной Wi‑Fi сети (например, общей судовой).\n"
                        + "2. Откройте WiFi Send — сервер запустится сам и будет работать в фоне.\n"
                        + "3. На телефоне наведите камеру на QR‑код или введите адрес в браузере "
                        + "(Safari, Chrome).\n"
                        + "4. «Отправить» — загрузить файлы на планшет, «Просмотреть» — открыть, "
                        + "скачать, переименовать или удалить.\n\n"
                        + "💻 Компьютер: подключите планшет кабелем и выберите режим «Передача файлов». "
                        + "Папка «WiFiSend» появится в памяти планшета. После загрузки по Wi‑Fi "
                        + "просто обновите папку (F5).\n\n"
                        + "🔔 О каждом полученном файле приходит уведомление.\n"
                        + "🔁 Если Wi‑Fi пропал и вернулся — адрес и QR‑код обновятся сами.\n"
                        + "🔌 Если планшет постоянно на зарядке — по возможности включите защиту "
                        + "батареи (зарядка до 80%).",
                "1. Tablet and phone on the same Wi‑Fi network.\n"
                        + "2. Open WiFi Send — the server starts by itself and keeps running in the "
                        + "background.\n"
                        + "3. On the phone, scan the QR code or type the address in a browser.\n"
                        + "4. «Send» uploads files to the tablet, «Browse» lets you open, download, "
                        + "rename or delete them.\n\n"
                        + "💻 Computer: connect the tablet by cable and choose «File transfer». "
                        + "The «WiFiSend» folder is in the tablet's storage. After a Wi‑Fi upload "
                        + "just refresh the folder (F5).\n\n"
                        + "🔔 You get a notification for every received file.\n"
                        + "🔁 If Wi‑Fi drops and comes back, the address and QR code update by themselves.\n"
                        + "🔌 If the tablet is always charging, turn on battery protection (80%) if "
                        + "available."),
                14, false, ui.text));
        root.addView(help);

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // ignore
        }
        TextView v = ui.text("WiFi Send " + ver, 12, false, ui.muted);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, ui.dp(18), 0, 0);
        root.addView(v);
    }

    // ------------------------------------------------------------------ pieces

    private interface Toggle {
        void set(boolean v);
    }

    private void section(String s) {
        TextView tv = ui.text(s, 14, true, ui.muted);
        tv.setPadding(ui.dp(4), ui.dp(20), 0, 0);
        root.addView(tv);
    }

    private TextView note(String s) {
        TextView tv = ui.text(s, 13, false, ui.muted);
        tv.setPadding(0, ui.dp(6), 0, 0);
        return tv;
    }

    private void permRow(LinearLayout parent, String label, boolean ok, Runnable fix) {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        r.addView(ui.text(label, 15, false, ui.text), Ui.weight1());
        if (ok) {
            r.addView(ui.text("✅", 18, false, ui.green));
        } else {
            Button b = ui.chip(Util.t("Разрешить", "Allow"));
            b.setTextColor(ui.orange);
            b.setOnClickListener(v -> fix.run());
            r.addView(b);
        }
        parent.addView(r);
    }

    private void toggleRow(LinearLayout parent, String label, boolean on, Toggle t) {
        LinearLayout r = ui.row();
        r.setPadding(0, ui.dp(6), 0, ui.dp(6));
        TextView l = ui.text(label, 15, false, ui.text);
        l.setPadding(0, 0, ui.dp(8), 0);
        r.addView(l, Ui.weight1());
        Button b = ui.chip(on ? Util.t("Вкл", "On") : Util.t("Выкл", "Off"));
        b.setTextColor(on ? ui.green : ui.muted);
        b.setBackground(ui.pressable(ui.round(on ? ui.greenBg : ui.card, 10, on ? 0 : ui.line)));
        b.setOnClickListener(v -> t.set(!on));
        r.addView(b);
        parent.addView(r);
    }

    private void askPin() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        et.setHint(Util.t("4–8 цифр", "4–8 digits"));
        et.setGravity(Gravity.CENTER);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        int theme = ui.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
        new AlertDialog.Builder(this, theme)
                .setTitle(Util.t("Новый PIN‑код", "New PIN code"))
                .setView(box)
                .setPositiveButton(Util.t("Сохранить", "Save"), (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.length() >= 4 && p.matches("[0-9]+")) {
                        Prefs.setPin(this, true, p);
                    }
                    build();
                })
                .setNegativeButton(Util.t("Отмена", "Cancel"), (d, w) -> build())
                .show();
    }
}
