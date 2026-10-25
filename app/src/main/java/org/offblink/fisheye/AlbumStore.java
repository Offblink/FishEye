package org.offblink.fisheye;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaStore 的唯一出入口：查、插、删、缩略图。
 *
 * <p>本应用只认自己的相册 {@code Pictures/FishEye}——别的照片不读也不写。
 */
final class AlbumStore {

    static final String ALBUM = "FishEye";

    private AlbumStore() {
    }

    /** 存的照片：返回新条目的 Uri */
    static Uri insert(Context ctx, Bitmap bmp, String name) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("MediaStore insert 失败");
        }
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) {
                throw new IOException("openOutputStream 为空");
            }
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                throw new IOException("JPEG 编码失败");
            }
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
        }
        return uri;
    }

    /** 覆盖已有条目（保留 _ID，相册里不会多出一张） */
    static void overwrite(Context ctx, Uri uri, Bitmap bmp) throws IOException {
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) {
                throw new IOException("openOutputStream 为空");
            }
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) {
                throw new IOException("JPEG 编码失败");
            }
        }
    }

    /** 本应用相册里的全部照片，新→旧 */
    static List<Uri> list(Context ctx) {
        List<Uri> out = new ArrayList<>();
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {MediaStore.Images.Media._ID};
        String sel;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"%" + ALBUM + "%"};
        } else {
            sel = MediaStore.Images.Media.DATA + " LIKE ?";
            args = new String[]{"%" + ALBUM + "%"};
        }
        try (android.database.Cursor c = ctx.getContentResolver().query(
                base, proj, sel, args, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    out.add(ContentUris.withAppendedId(base, c.getLong(0)));
                }
            }
        } catch (Throwable ignored) {
            // 没权限/系统异常时当空相册，界面自己会提示
        }
        return out;
    }

    static Uri latest(Context ctx) {
        List<Uri> all = list(ctx);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 内存里的缩略图缓存：涂抹选中时格子不能闪白，滚动时也不该反复解同一张 */
    private static final android.util.LruCache<String, Bitmap> THUMBS =
            new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    static Bitmap cachedThumb(Uri uri, int size) {
        return THUMBS.get(uri + "@" + size);
    }

    /** 缩略图：先按 1/4 采样解码，再按长边缩到 target */
    static Bitmap thumbnail(ContentResolver cr, Uri uri, int target) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            int sample = 1;
            int longest = Math.max(o.outWidth, o.outHeight);
            while (longest / (sample * 4) >= target) {
                sample *= 4;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (InputStream in2 = cr.openInputStream(uri)) {
                Bitmap bmp = BitmapFactory.decodeStream(in2, null, o2);
                if (bmp == null) {
                    return null;
                }
                float s = target / (float) Math.max(bmp.getWidth(), bmp.getHeight());
                Bitmap out;
                if (s < 1f) {
                    Bitmap small = Bitmap.createScaledBitmap(bmp,
                            Math.max(1, Math.round(bmp.getWidth() * s)),
                            Math.max(1, Math.round(bmp.getHeight() * s)), true);
                    bmp.recycle();
                    out = small;
                } else {
                    out = bmp;
                }
                THUMBS.put(uri + "@" + target, out);
                return out;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    static void delete(Context ctx, Uri uri) {
        try {
            ctx.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
            // 删不掉（用户拒了授权）时保留原样
        }
    }

    static boolean hasReadPermission(Context ctx) {
        String p = Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_IMAGES
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED;
    }

    static String[] storagePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{android.Manifest.permission.READ_MEDIA_IMAGES};
        }
        if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** 相机快门写临时 JPEG 的地方 */
    static File tempJpeg(Context ctx) {
        return new File(ctx.getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
    }

    static boolean legacyWrite() {
        return Build.VERSION.SDK_INT <= 28;
    }

    static File legacyDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static void writeLegacy(File target, Bitmap bmp) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)) {
                throw new IOException("JPEG 编码失败");
            }
        }
    }
}
