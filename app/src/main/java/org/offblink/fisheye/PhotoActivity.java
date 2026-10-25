package org.offblink.fisheye;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 只看，不改：相册里点开一张照片，全屏预览。
 *
 * <p>成片在快门那一刻就已经烘焙好了，这里再调色没有意义——所以这一页只有图和一个返回。
 * 进出用的是 Prism 那套缩放切页：从照片格的屏幕位置放大进来，返回时缩回原位。
 */
public class PhotoActivity extends AppCompatActivity {

    public static final String EXTRA_ZOOM_FROM = "zoom_from";

    private View root;
    private ImageView image;
    private int[] zoomFrom;
    private boolean closing;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_photo);

        root = findViewById(R.id.ph_root);
        image = findViewById(R.id.ph_image);

        Uri uri = getIntent().getData();
        zoomFrom = getIntent().getIntArrayExtra(EXTRA_ZOOM_FROM);
        if (zoomFrom != null) {
            Ui.zoomIn(this, root, zoomFrom);
        }

        // 图片别缩到系统栏下面去：把状态栏/导航栏高度让出来
        final int baseTop = root.getPaddingTop();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in =
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            androidx.core.graphics.Insets bars =
                    in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), baseTop + bars.top,
                    v.getPaddingRight(), baseBottom + bars.bottom);
            return insets;
        });

        // 点哪儿都是返回；预览不需要别的交互
        root.setOnClickListener(v -> close());

        if (uri == null) {
            finish();
            return;
        }

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-photo");
            t.setDaemon(true);
            return t;
        });
        io.execute(() -> {
            Bitmap bmp = load(uri);
            main.post(() -> {
                if (bmp != null) {
                    image.setImageBitmap(bmp);
                } else {
                    Ui.toast(this, "这张照片读不出来");
                    close();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        close();
    }

    private void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (zoomFrom != null) {
            Ui.shrinkFinish(this, root, zoomFrom);
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    /** 预览用不着原图：长边压到屏幕量级就够 */
    private Bitmap load(Uri uri) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return null;
                }
                BitmapFactory.decodeStream(in, null, probe);
            }
            int sample = 1;
            int longest = Math.max(probe.outWidth, probe.outHeight);
            while (longest / sample > 2048) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
