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
import android.util.Size;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MediaStore 的唯一出入口：查、插、删、缩略图。
 *
 * <p>本应用只认自己的相册 {@code FishEye}——别的照片/视频不读也不写。
 * 照片在 {@code Pictures/FishEye}，录像在 {@code Movies/FishEye}，两边混在一起按时间排。
 */
final class AlbumStore {

    static final String ALBUM = "FishEye";

    private AlbumStore() {
    }

    /** 相册里的一项：可能是照片，也可能是录像 */
    static final class Item {
        final Uri uri;
        final boolean video;
        final long durationMs;
        final long dateAdded;

        Item(Uri uri, boolean video, long durationMs, long dateAdded) {
            this.uri = uri;
            this.video = video;
            this.durationMs = durationMs;
            this.dateAdded = dateAdded;
        }
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

    // ------------------------------------------------------------------ 录像

    /** Recorder 往哪儿写：Movies/FishEye，API 29+ 先标 pending，封装完再放出来 */
    static Uri videoCollection() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    static ContentValues videoValues(String name) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            v.put(MediaStore.Video.Media.IS_PENDING, 1);
        }
        return v;
    }

    /** 封装完成：把 pending 放出来，否则相册里看不见 */
    static void videoFinalize(Context ctx, Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
        } catch (Throwable ignored) {
            // 放不出来时文件还在，只是系统相册里要等一次扫描
        }
    }

    // -------------------------------------------------------------------- 查

    /** 本应用相册里的全部照片与录像，新→旧 */
    static List<Item> list(Context ctx) {
        List<Item> out = new ArrayList<>();
        collect(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out);
        collect(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out);
        Collections.sort(out, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));
        return out;
    }

    private static void collect(Context ctx, Uri base, boolean video, List<Item> out) {
        String idCol = video ? MediaStore.Video.Media._ID : MediaStore.Images.Media._ID;
        String dateCol = video ? MediaStore.Video.Media.DATE_ADDED
                : MediaStore.Images.Media.DATE_ADDED;
        String pathCol = video ? MediaStore.Video.Media.RELATIVE_PATH
                : MediaStore.Images.Media.RELATIVE_PATH;
        String dataCol = video ? MediaStore.Video.Media.DATA : MediaStore.Images.Media.DATA;
        String[] proj = video
                ? new String[]{idCol, dateCol, MediaStore.Video.Media.DURATION}
                : new String[]{idCol, dateCol};
        String sel;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sel = pathCol + " LIKE ?";
        } else {
            sel = dataCol + " LIKE ?";
        }
        String[] args = new String[]{"%" + ALBUM + "%"};
        try (android.database.Cursor c = ctx.getContentResolver().query(
                base, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) {
                return;
            }
            while (c.moveToNext()) {
                long id = c.getLong(0);
                long date = c.getLong(1);
                long dur = video ? c.getLong(2) : 0;
                out.add(new Item(ContentUris.withAppendedId(base, id), video, dur, date));
            }
        } catch (Throwable ignored) {
            // 没权限/系统异常时当空相册，界面自己会提示
        }
    }

    static Item latest(Context ctx) {
        List<Item> all = list(ctx);
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

    static Bitmap cachedThumb(Item item, int size) {
        return THUMBS.get(item.uri + "@" + size);
    }

    /** 缩略图：照片先按 1/4 采样解码再缩；录像问系统要一帧封面 */
    static Bitmap thumbnail(Context ctx, Item item, int target) {
        if (item.video) {
            return videoThumbnail(ctx, item.uri, target);
        }
        return photoThumbnail(ctx.getContentResolver(), item.uri, target);
    }

    private static Bitmap photoThumbnail(ContentResolver cr, Uri uri, int target) {
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
                Bitmap out = scaleTo(bmp, target);
                THUMBS.put(uri + "@" + target, out);
                return out;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap videoThumbnail(Context ctx, Uri uri, int target) {
        try {
            Bitmap bmp;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bmp = ctx.getContentResolver().loadThumbnail(uri, new Size(target, target), null);
            } else {
                // 26–28 只有这套老接口能拿视频封面
                bmp = MediaStore.Video.Thumbnails.getThumbnail(ctx.getContentResolver(),
                        ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            if (bmp == null) {
                return null;
            }
            Bitmap out = scaleTo(bmp, target);
            THUMBS.put(uri + "@" + target, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap scaleTo(Bitmap bmp, int target) {
        float s = target / (float) Math.max(bmp.getWidth(), bmp.getHeight());
        if (s >= 1f) {
            return bmp;
        }
        Bitmap small = Bitmap.createScaledBitmap(bmp,
                Math.max(1, Math.round(bmp.getWidth() * s)),
                Math.max(1, Math.round(bmp.getHeight() * s)), true);
        if (small != bmp) {
            bmp.recycle();
        }
        return small;
    }

    static void delete(Context ctx, Uri uri) {
        try {
            ctx.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
            // 删不掉（用户拒了授权）时保留原样
        }
    }

    static boolean hasReadPermission(Context ctx) {
        for (String p : storagePermissions()) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    static String[] storagePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VIDEO};
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
