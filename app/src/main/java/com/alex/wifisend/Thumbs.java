package com.alex.wifisend;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;

/** Small square JPEG previews for the file list. */
final class Thumbs {

    private Thumbs() {
    }

    static byte[] make(File f, int size) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) {
                return null;
            }
            int w = b.getWidth();
            int h = b.getHeight();
            int side = Math.min(w, h);
            Matrix m = new Matrix();
            float scale = (float) size / side;
            m.postScale(scale, scale);
            int rotation = rotation(path);
            if (rotation != 0) {
                m.postRotate(rotation);
            }
            Bitmap t = Bitmap.createBitmap(b, (w - side) / 2, (h - side) / 2, side, side, m, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            t.compress(Bitmap.CompressFormat.JPEG, 80, bos);
            if (t != b) {
                t.recycle();
            }
            b.recycle();
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int rotation(String path) {
        try {
            String lower = path.toLowerCase();
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                return 0;
            }
            ExifInterface exif = new ExifInterface(path);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) {
                return 90;
            }
            if (o == ExifInterface.ORIENTATION_ROTATE_180) {
                return 180;
            }
            if (o == ExifInterface.ORIENTATION_ROTATE_270) {
                return 270;
            }
        } catch (Exception ignored) {
            // no EXIF
        }
        return 0;
    }
}
