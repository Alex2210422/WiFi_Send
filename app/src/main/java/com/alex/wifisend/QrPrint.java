package com.alex.wifisend;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/** Printable picture: title, big QR code and the address underneath. Saved to the gallery. */
final class QrPrint {

    private QrPrint() {
    }

    static Bitmap poster(String hostPort) {
        int w = 1240;
        int h = 1600;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.rgb(17, 24, 39));
        title.setTextSize(96);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setTextAlign(Paint.Align.CENTER);
        c.drawText("WiFi Send", w / 2f, 170, title);

        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(Color.rgb(107, 114, 128));
        sub.setTextSize(44);
        sub.setTextAlign(Paint.Align.CENTER);
        c.drawText(Util.t("Наведите камеру телефона", "Point your phone's camera"), w / 2f, 250, sub);

        Bitmap qr = MainActivity.qr("http://" + hostPort, 1000);
        if (qr != null) {
            c.drawBitmap(qr, (w - qr.getWidth()) / 2f, 300, null);
        }

        Paint url = new Paint(Paint.ANTI_ALIAS_FLAG);
        url.setColor(Color.rgb(37, 99, 235));
        url.setTextSize(84);
        url.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        url.setTextAlign(Paint.Align.CENTER);
        c.drawText(hostPort, w / 2f, 1420, url);

        c.drawText(Util.t("Телефон — в той же Wi‑Fi сети", "Phone on the same Wi‑Fi network"), w / 2f, 1510, sub);
        return bmp;
    }

    /** Saves to Pictures/WiFiSend. Returns true on success. */
    static boolean saveToGallery(Context ctx, Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver r = ctx.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WiFiSend");
                Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return false;
                }
                OutputStream os = r.openOutputStream(uri);
                if (os == null) {
                    return false;
                }
                try {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                } finally {
                    os.close();
                }
                return true;
            }
            @SuppressWarnings("deprecation")
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WiFiSend");
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            } finally {
                fos.close();
            }
            MediaScannerConnection.scanFile(ctx, new String[]{f.getAbsolutePath()}, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "10.0.2.108" -> "10.0.2.200" — a suggestion for a static address in the same network. */
    static String suggestStatic(String ip) {
        int dot = ip.lastIndexOf('.');
        return dot > 0 ? ip.substring(0, dot) + ".200" : ip;
    }
}
