package com.alex.wifisend;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors and view builders shared by the screens (light / dark theme). */
final class Ui {

    final Context c;
    final boolean dark;
    final int bg;
    final int card;
    final int text;
    final int muted;
    final int line;
    final int accent;
    final int green;
    final int greenBg;
    final int orange;
    final int orangeBg;
    final int red;

    Ui(Context c) {
        this.c = c;
        String t = Prefs.theme(c);
        boolean d;
        if ("dark".equals(t)) {
            d = true;
        } else if ("light".equals(t)) {
            d = false;
        } else {
            d = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        }
        dark = d;
        bg = d ? Color.rgb(15, 20, 25) : Color.rgb(244, 246, 249);
        card = d ? Color.rgb(26, 33, 41) : Color.WHITE;
        text = d ? Color.rgb(230, 237, 243) : Color.rgb(22, 32, 44);
        muted = d ? Color.rgb(139, 152, 168) : Color.rgb(107, 118, 134);
        line = d ? Color.rgb(42, 51, 61) : Color.rgb(227, 231, 238);
        accent = d ? Color.rgb(90, 162, 240) : Color.rgb(21, 101, 192);
        green = d ? Color.rgb(102, 187, 106) : Color.rgb(46, 125, 50);
        greenBg = d ? Color.rgb(27, 48, 31) : Color.rgb(232, 245, 233);
        orange = d ? Color.rgb(255, 183, 77) : Color.rgb(230, 81, 0);
        orangeBg = d ? Color.rgb(51, 38, 20) : Color.rgb(255, 243, 224);
        red = d ? Color.rgb(239, 83, 80) : Color.rgb(198, 40, 40);
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    @SuppressWarnings("deprecation")
    void window(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = 0;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            g.setStroke(dp(1), strokeColor);
        }
        return g;
    }

    Drawable pressable(Drawable content) {
        int ripple = dark ? Color.argb(60, 255, 255, 255) : Color.argb(40, 0, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, null);
    }

    TextView text(String s, int sp, boolean bold, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    /** kind: 0 = outline, 1 = primary (filled accent), 2 = warning, 3 = danger outline */
    Button button(String s, int kind) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.setMinHeight(dp(48));
        GradientDrawable shape;
        if (kind == 1) {
            shape = round(accent, 12, 0);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (kind == 2) {
            shape = round(orangeBg, 12, 0);
            b.setTextColor(orange);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (kind == 3) {
            shape = round(card, 12, line);
            b.setTextColor(red);
        } else {
            shape = round(card, 12, line);
            b.setTextColor(text);
        }
        b.setBackground(pressable(shape));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /** Small rounded "chip" button used in headers. */
    Button chip(String s) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(text);
        b.setStateListAnimator(null);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(pressable(round(card, 10, line)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(card, 18, line));
        l.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    String themeIcon() {
        String t = Prefs.theme(c);
        return "dark".equals(t) ? "🌙" : "light".equals(t) ? "☀️" : "🌓";
    }

    static String nextTheme(String t) {
        return "auto".equals(t) ? "light" : "light".equals(t) ? "dark" : "auto";
    }

    String themeName() {
        String t = Prefs.theme(c);
        if ("dark".equals(t)) {
            return Util.t("Тёмная", "Dark");
        }
        if ("light".equals(t)) {
            return Util.t("Светлая", "Light");
        }
        return Util.t("Авто", "Auto");
    }
}
