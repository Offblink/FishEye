package org.offblink.fisheye;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调色模式：把一张已有的照片读进来，走与取景完全相同的两趟 shader 重新渲染。
 *
 * <p>方向在解码时就摆正（EXIF 旋转一次），所以预览与烘焙都不必再传角度。
 */
public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_ALLOW_OVERWRITE = "allow_overwrite";

    private GlPreviewView preview;
    private WebView controls;
    private TextView saveBtn;
    private TextView title;
    private View flash;

    private final Params params = new Params();
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Uri uri;
    private Bitmap photo;
    private boolean allowOverwrite;
    private boolean saving;
    private boolean controlsReady;
    private boolean darkUi = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_editor);

        preview = findViewById(R.id.edit_preview);
        controls = findViewById(R.id.edit_controls);
        saveBtn = findViewById(R.id.edit_save);
        title = findViewById(R.id.edit_title);
        flash = findViewById(R.id.edit_flash);

        applyInsets();
        uri = getIntent().getData();
        allowOverwrite = getIntent().getBooleanExtra(EXTRA_ALLOW_OVERWRITE, true);
        if (uri == null) {
            finish();
            return;
        }

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-edit");
            t.setDaemon(true);
            return t;
        });

        preview.setLumaListener(luma -> runOnUiThread(() -> {
            if (darkUi && luma >= 105) {
                setDarkUi(false);
            } else if (!darkUi && luma <= 80) {
                setDarkUi(true);
            }
        }));
        preview.setParams(params);

        setupControls();
        saveBtn.setOnClickListener(v -> save());

        io.execute(() -> {
            Bitmap bmp = load();
            main.post(() -> {
                if (bmp == null) {
                    Ui.toast(this, "这张照片读不出来");
                    finish();
                    return;
                }
                photo = bmp;
                preview.setImage(bmp);
            });
        });
    }

    private void applyInsets() {
        View top = findViewById(R.id.edit_top);
        View bottom = findViewById(R.id.edit_bottom);
        final int topPad = top.getPaddingTop();
        final int botPad = Ui.dp(this, 8);
        findViewById(R.id.edit_top).setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in =
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            v.setPadding(v.getPaddingLeft(),
                    topPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top,
                    v.getPaddingRight(), v.getPaddingBottom());
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(),
                    botPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
    }

    /** 读一张照片：按 EXIF 摆正，超长边压到 3000 以内（调色用不到原始 12MP） */
    private Bitmap load() {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, probe);
            int sample = 1;
            int longest = Math.max(probe.outWidth, probe.outHeight);
            while (longest / (sample * 2) >= 3000) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw;
            try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                raw = BitmapFactory.decodeStream(in2, null, o);
            }
            if (raw == null) {
                return null;
            }
            int rot = 0;
            try (InputStream in3 = getContentResolver().openInputStream(uri)) {
                ExifInterface ex = new ExifInterface(in3);
                switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL)) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
                    default: rot = 0; break;
                }
            } catch (IOException ignored) {
            }
            if (rot == 0) {
                return raw;
            }
            Matrix m = new Matrix();
            m.postRotate(rot);
            Bitmap fixed = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (fixed != raw) {
                raw.recycle();
            }
            return fixed;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- 网页控件

    private void setupControls() {
        controls.setBackgroundColor(Color.TRANSPARENT);
        controls.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = controls.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        controls.addJavascriptInterface(new Bridge(), "fish");
        controls.loadUrl("file:///android_asset/controls.html");
    }

    private void pushUi() {
        if (!controlsReady) {
            return;
        }
        String js = "window.setValues([" + (params.fisheye * 100f) + ","
                + (params.chroma / 3f * 100f) + "," + (params.contrast * 100f) + ","
                + (params.local * 100f) + "," + (params.blur * 100f) + ","
                + (params.vignette * 100f) + "]);";
        controls.evaluateJavascript(js, null);
    }

    private void pushTheme() {
        if (!controlsReady) {
            return;
        }
        controls.evaluateJavascript("window.setTheme(" + (darkUi ? "true" : "false") + ");", null);
    }

    private void setDarkUi(boolean dark) {
        if (darkUi == dark) {
            return;
        }
        darkUi = dark;
        int c = dark ? Color.WHITE : Color.BLACK;
        title.setTextColor(c);
        pushTheme();
    }

    private final class Bridge {

        @JavascriptInterface
        public void param(String key, float value) {
            main.post(() -> {
                params.set(key, value);
                preview.setParams(params);
            });
        }

        @JavascriptInterface
        public void preset(String name) {
            main.post(() -> {
                params.preset(name);
                preview.setParams(params);
                pushUi();
            });
        }

        @JavascriptInterface
        public void reset() {
            main.post(() -> {
                params.preset("default");
                preview.setParams(params);
                pushUi();
            });
        }

        @JavascriptInterface
        public void ready() {
            main.post(() -> {
                controlsReady = true;
                pushTheme();
                pushUi();
            });
        }
    }

    // --------------------------------------------------------------- 保存

    private void save() {
        if (saving || photo == null) {
            return;
        }
        if (!allowOverwrite) {
            bakeAndWrite(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setItems(new String[]{"另存为新照片", "覆盖原图"}, (d, which) -> bakeAndWrite(which == 1))
                .show();
    }

    private void bakeAndWrite(final boolean overwrite) {
        saving = true;
        flash.setAlpha(0f);
        flash.animate().alpha(0.6f).setDuration(60)
                .withEndAction(() -> flash.animate().alpha(0f).setDuration(240).start()).start();

        preview.bake(photo, 0, baked -> {
            if (baked == null) {
                saving = false;
                Ui.toast(this, "烘焙失败");
                return;
            }
            io.execute(() -> {
                String err = null;
                try {
                    if (overwrite) {
                        AlbumStore.overwrite(this, uri, baked);
                    } else {
                        AlbumStore.insert(this, baked,
                                "FishEye_" + System.currentTimeMillis() + ".jpg");
                    }
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                baked.recycle();
                final String message = err;
                main.post(() -> {
                    saving = false;
                    if (message == null) {
                        Ui.toast(this, overwrite ? "已覆盖" : "已另存");
                        if (overwrite) {
                            finish();
                        }
                    } else {
                        Ui.toast(this, "保存失败：" + message);
                    }
                });
            });
        });
    }
}
