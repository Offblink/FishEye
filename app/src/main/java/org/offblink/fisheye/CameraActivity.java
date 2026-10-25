package org.offblink.fisheye;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.exifinterface.media.ExifInterface;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 拍摄模式：取景 + 实时张力管线 + 快门 / 录像。
 *
 * <p>和其他相机 App 不一样的地方只有一处：画面上来不是直出，而是先过
 * {@link GlPreviewView} 的两趟 shader。快门按下时，同一张照片被交给同一套 shader
 * 重新渲染一遍再存盘——所以「取景时看到的样子」和「存下来的照片」是同一件事。
 * 录像同理：{@link VideoProcessor} 作为 CameraX 的 SurfaceProcessor 插在相机与编码器之间，
 * 用的还是同一份 GLSL。
 */
public class CameraActivity extends AppCompatActivity {

    private GlPreviewView preview;
    private WebView controls;
    private ImageView thumb;
    private View shutter;
    private View shutterDisc;
    private View flash;
    private View permGate;
    private TextView title;
    private ImageView flipLabel;
    private CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
    private View thumbWrap;

    // 录像
    private TextView modePhoto;
    private TextView modeVideo;
    private View recBar;
    private TextView recTime;
    private VideoProcessor videoProcessor;
    private CameraEffect videoEffect;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private boolean videoMode;
    private long recordStartedAt;
    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if (recording == null) {
                return;
            }
            long s = (System.currentTimeMillis() - recordStartedAt) / 1000;
            recTime.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
            main.postDelayed(this, 500);
        }
    };

    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ExecutorService io;
    private boolean capturing;

    private final Params params = new Params();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean darkUi = true;
    private boolean controlsReady;

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    permGate.setVisibility(View.GONE);
                    startCamera();
                } else {
                    permGate.setVisibility(View.VISIBLE);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_camera);

        preview = findViewById(R.id.cam_preview);
        controls = findViewById(R.id.cam_controls);
        thumb = findViewById(R.id.cam_thumb);
        thumbWrap = findViewById(R.id.cam_thumb_wrap);
        shutter = findViewById(R.id.cam_shutter);
        shutterDisc = findViewById(R.id.cam_shutter_disc);
        flash = findViewById(R.id.cam_flash);
        permGate = findViewById(R.id.cam_perm);
        title = findViewById(R.id.cam_title);
        flipLabel = findViewById(R.id.cam_flip);

        applyInsets();
        setupControls();
        bindTapToFocus();

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-io");
            t.setDaemon(true);
            return t;
        });

        // 点击挂在整块上：中间那块白盘会先接到触摸，只给环设监听的话点正中会没反应
        findViewById(R.id.cam_shutter_wrap).setOnClickListener(v -> onShutter());
        modePhoto = findViewById(R.id.cam_mode_photo);
        modeVideo = findViewById(R.id.cam_mode_video);
        recBar = findViewById(R.id.cam_rec);
        recTime = findViewById(R.id.cam_rec_time);
        modePhoto.setOnClickListener(v -> setVideoMode(false));
        modeVideo.setOnClickListener(v -> setVideoMode(true));
        videoProcessor = new VideoProcessor();
        videoProcessor.setErrorListener(msg -> runOnUiThread(() -> Ui.toast(this, msg)));
        videoProcessor.setParams(params);
        flipLabel.setOnClickListener(v -> flipCamera());
        // 左下缩略图就是相册入口（不再在右下放第二个「相册」）：
        // 从这颗缩略图的位置放大进相册，回来时缩回原处
        thumbWrap.setOnClickListener(v -> {
            int[] rect = Ui.rectOnScreen(v);
            Ui.snapshot(this, () -> {
                startActivity(new Intent(this, GalleryActivity.class)
                        .putExtra(GalleryActivity.EXTRA_ZOOM_FROM, rect));
                overridePendingTransition(0, 0);
            });
        });
        findViewById(R.id.cam_perm_btn).setOnClickListener(v -> permLauncher.launch(requested()));

        preview.setLumaListener(this::onPreviewLuma);
        preview.setErrorListener(msg -> runOnUiThread(() -> Ui.toast(this, msg)));
        preview.setParams(params);

        boolean camGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (camGranted) {
            permGate.setVisibility(View.GONE);
            startCamera();
        } else {
            permGate.setVisibility(View.VISIBLE);
            permLauncher.launch(requested());
        }
        if (!AlbumStore.hasReadPermission(this)) {
            permLauncher.launch(requested());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshThumb();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 离开拍摄页就别继续录了：录出来的东西是黑屏/没人管的残片
        if (recording != null) {
            stopRecording();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) {
            io.shutdown();
        }
        if (videoProcessor != null) {
            videoProcessor.release();
        }
        if (provider != null) {
            provider.unbindAll();
        }
    }

    private String[] requested() {
        // 录音权限单独要：拒了也照样能录画面，只是没声音
        String[] storage = AlbumStore.storagePermissions();
        String[] all = new String[storage.length + 2];
        all[0] = Manifest.permission.CAMERA;
        all[1] = Manifest.permission.RECORD_AUDIO;
        System.arraycopy(storage, 0, all, 2, storage.length);
        return all;
    }

    /** 顶栏与底栏要避开状态栏/导航栏，但取景本身铺满全屏 */
    private void applyInsets() {
        View top = findViewById(R.id.cam_top);
        View bottom = findViewById(R.id.cam_bottom);
        final int topPad = Ui.dp(this, 14);
        final int botPad = Ui.dp(this, 18);
        findViewById(R.id.cam_root).setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            top.setPadding(top.getPaddingLeft(),
                    topPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top,
                    top.getPaddingRight(), top.getPaddingBottom());
            bottom.setPadding(bottom.getPaddingLeft(), bottom.getPaddingTop(),
                    bottom.getPaddingRight(),
                    botPad + in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;
        });
    }

    // ------------------------------------------------------------- 网页控件

    private void setupControls() {
        controls.setBackgroundColor(Color.TRANSPARENT);
        controls.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = controls.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setDomStorageEnabled(false);
        controls.addJavascriptInterface(new Bridge(), "fish");
        controls.loadUrl("file:///android_asset/controls.html");
    }

    private void pushUi() {
        if (!controlsReady) {
            return;
        }
        float[] v = new float[]{
                params.fisheye * 100f,
                params.chroma / 3f * 100f,
                params.contrast * 100f,
                params.local * 100f,
                params.blur * 100f,
                params.vignette * 100f,
        };
        String js = "window.setValues([" + v[0] + "," + v[1] + "," + v[2] + ","
                + v[3] + "," + v[4] + "," + v[5] + "]);";
        controls.evaluateJavascript(js, null);
    }

    private void pushTheme() {
        if (!controlsReady) {
            return;
        }
        controls.evaluateJavascript("window.setTheme(" + (darkUi ? "true" : "false") + ");", null);
    }

    private final class Bridge {

        @JavascriptInterface
        public void param(String key, float value) {
            main.post(() -> {
                params.set(key, value);
                preview.setParams(params);
                if (videoProcessor != null) {
                    videoProcessor.setParams(params);
                }
            });
        }

        @JavascriptInterface
        public void preset(String name) {
            main.post(() -> {
                params.preset(name);
                preview.setParams(params);
                if (videoProcessor != null) {
                    videoProcessor.setParams(params);
                }
                pushUi();
            });
        }

        @JavascriptInterface
        public void reset() {
            main.post(() -> {
                params.preset("default");
                preview.setParams(params);
                if (videoProcessor != null) {
                    videoProcessor.setParams(params);
                }
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

    // --------------------------------------------------------------- 相机

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                provider = future.get();
            } catch (Exception e) {
                Ui.toast(this, "相机启动失败：" + e.getClass().getSimpleName());
                return;
            }
            try {
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    flipLabel.setVisibility(View.GONE);
                }
            } catch (androidx.camera.core.CameraInfoUnavailableException e) {
                flipLabel.setVisibility(View.GONE);
            }
            bindCamera();
            // 看门狗：管线出错会自己弹提示，这里兜「相机根本没给画面」那种
            final int before = preview.framesDrawn();
            main.postDelayed(() -> {
                if (!isFinishing() && preview.framesDrawn() == before) {
                    Ui.toast(this, "取景没有画面：相机可能被其他应用占用");
                }
            }, 8000);
        }, ContextCompat.getMainExecutor(this));
    }

    /** 按当前 selector 重新绑定：翻转镜头走的也是这条路 */
    private void bindCamera() {
        if (provider == null) {
            return;
        }
        // 取景流的画面比例跟着模式走：拍照 4:3 与成片一致，录像 16:9 与编码一致
        ResolutionSelector ratio = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(videoMode
                        ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();
        Preview viewfinder = new Preview.Builder()
                .setResolutionSelector(ratio)
                .build();
        viewfinder.setSurfaceProvider(preview);
        if (imageCapture == null) {
            imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(95)
                    .build();
        }
        if (videoCapture == null) {
            Recorder recorder = new Recorder.Builder()
                    // 模拟器只给得出低分辨率；优先级写全，真机自动挑得到的最高的
                    .setQualitySelector(QualitySelector.fromOrderedList(
                            Arrays.asList(Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);
        }
        if (videoEffect == null) {
            // 只挂在 VideoCapture 上：取景那条链路一点都不动，画面靠同一份 GLSL 保持一致
            videoEffect = new CameraEffect(CameraEffect.VIDEO_CAPTURE,
                    videoProcessor.executor(), videoProcessor,
                    throwable -> runOnUiThread(() ->
                            Ui.toast(this, "录像管线出错：" + throwable.getMessage()))) {
            };
        }
        UseCaseGroup group = new UseCaseGroup.Builder()
                .addUseCase(viewfinder)
                .addUseCase(imageCapture)
                .addUseCase(videoCapture)
                .addEffect(videoEffect)
                .build();
        provider.unbindAll();
        try {
            camera = provider.bindToLifecycle(this, selector, group);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 某些机型不支持「预览 + 拍照 + 录像」三路并发：退回到不录像的组合，
            // 至少拍照还能用，并且把原因说出来（静默降级比崩溃好）
            android.util.Log.e("FishEye", "bind with video failed", e);
            modeVideo.setVisibility(View.GONE);
            setVideoMode(false, false);
            camera = provider.bindToLifecycle(this, selector, viewfinder, imageCapture);
            Ui.toast(this, "这台设备不支持同时录像，只能用拍照：" + e.getClass().getSimpleName());
        }
    }

    private void flipCamera() {
        if (capturing || recording != null || provider == null) {
            return;
        }
        selector = selector == CameraSelector.DEFAULT_BACK_CAMERA
                ? CameraSelector.DEFAULT_FRONT_CAMERA
                : CameraSelector.DEFAULT_BACK_CAMERA;
        bindCamera();
        preview.setFocus(0.5f, 0.5f);
        videoProcessor.setFocus(0.5f, 0.5f);
    }

    /** 点取景任意位置：那里对焦测光，同时把缩放模糊的中心挪过去 */
    private void bindTapToFocus() {
        preview.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            float nx = event.getX() / Math.max(1, v.getWidth());
            float ny = event.getY() / Math.max(1, v.getHeight());
            preview.setFocus(nx, 1f - ny);
            videoProcessor.setFocus(nx, 1f - ny);
            if (camera != null) {
                MeteringPoint point = new androidx.camera.core.SurfaceOrientedMeteringPointFactory(
                        Math.max(1, v.getWidth()), Math.max(1, v.getHeight()))
                        .createPoint(event.getX(), event.getY());
                try {
                    camera.getCameraControl().startFocusAndMetering(
                            new FocusMeteringAction.Builder(point,
                                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                                    .build());
                } catch (IllegalArgumentException ignored) {
                    // 点到了取景外的坐标（贴边），忽略即可
                }
            }
            View ring = findViewById(R.id.cam_focus);
            ring.setX(event.getX() - ring.getWidth() / 2f);
            ring.setY(event.getY() - ring.getHeight() / 2f);
            ring.setAlpha(1f);
            ring.setVisibility(View.VISIBLE);
            ring.animate().alpha(0f).setStartDelay(320).setDuration(260)
                    .withEndAction(() -> ring.setVisibility(View.INVISIBLE)).start();
            return true;
        });
    }

    // --------------------------------------------------------------- 快门

    private void takePhoto() {
        if (capturing || imageCapture == null || recording != null) {
            return;
        }
        capturing = true;
        flash.setAlpha(0f);
        flash.animate().alpha(0.85f).setDuration(60).withEndAction(() ->
                flash.animate().alpha(0f).setDuration(260).start()).start();
        try {
            new MediaActionSound().play(MediaActionSound.SHUTTER_CLICK);
        } catch (Throwable ignored) {
        }

        // 看门狗：解码/烘焙/写 MediaStore 任何一步卡住，20 秒后把快门还回来并说明
        main.postDelayed(() -> {
            if (capturing) {
                capturing = false;
                Ui.toast(this, "处理超时，已放弃这次拍摄");
            }
        }, 20000);

        final File out = AlbumStore.tempJpeg(this);
        ImageCapture.OutputFileOptions opts =
                new ImageCapture.OutputFileOptions.Builder(out).build();
        imageCapture.takePicture(opts, io, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                bakeAndSave(out);
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                capturing = false;
                main.post(() -> Ui.toast(CameraActivity.this, "拍摄失败：" + exception.getMessage()));
            }
        });
    }

    /** 快门后的第二步：把 JPEG 解出来，交给 GL 用同一套 shader 重新渲染，再存进相册 */
    private void bakeAndSave(final File jpeg) {
        io.execute(() -> {
            int rotation = exifRotation(jpeg.getPath());
            Bitmap decoded = decodeSampled(jpeg.getPath(), 2048);
            if (decoded == null) {
                capturing = false;
                //noinspection ResultOfMethodCallIgnored
                jpeg.delete();
                main.post(() -> Ui.toast(CameraActivity.this, "照片解码失败"));
                return;
            }
            // 方向在 CPU 侧一次摆正：GL 侧只做纯 2D 采样，不叠旋转，
            // 免得「竖翻 + 旋转」复合出反射（拍出来会左右镜像）
            final Bitmap upright = rotate(decoded, rotation);
            if (upright != decoded) {
                decoded.recycle();
            }
            preview.bake(upright, 0, baked -> {
                upright.recycle();
                //noinspection ResultOfMethodCallIgnored
                jpeg.delete();
                if (baked == null) {
                    capturing = false;
                    Ui.toast(CameraActivity.this, "烘焙失败");
                    return;
                }
                io.execute(() -> {
                    String name = "FishEye_" + System.currentTimeMillis() + ".jpg";
                    try {
                        if (AlbumStore.legacyWrite()) {
                            File target = new File(AlbumStore.legacyDir(), name);
                            AlbumStore.writeLegacy(target, baked);
                        } else {
                            AlbumStore.insert(CameraActivity.this, baked, name);
                        }
                        baked.recycle();
                        main.post(() -> {
                            capturing = false;
                            refreshThumb();
                        });
                    } catch (IOException e) {
                        baked.recycle();
                        main.post(() -> {
                            capturing = false;
                            Ui.toast(CameraActivity.this, "保存失败：" + e.getMessage());
                        });
                    }
                });
            });
        });
    }

    // --------------------------------------------------------------- 录像

    private void onShutter() {
        if (videoMode) {
            if (recording == null) {
                startRecording();
            } else {
                stopRecording();
            }
        } else {
            takePhoto();
        }
    }

    private void setVideoMode(boolean on) {
        setVideoMode(on, true);
    }

    /**
     * @param rebind 换模式要按新的画面比例重绑：取景是 4:3（跟照片一致），
     *               录像是 16:9（视频能力里只有 16:9），不重绑就会出现
     *               「录下来的比取景框里看到的多一截」。重绑由调用方决定，
     *               免得绑定失败的兜底路径又绕回来。
     */
    private void setVideoMode(boolean on, boolean rebind) {
        if (videoMode == on) {
            return;
        }
        if (recording != null) {
            // 换模式先把当前这段收尾，免得录到一半状态打架
            stopRecording();
        }
        videoMode = on;
        modePhoto.setBackground(on ? null : getDrawable(R.drawable.mode_on));
        modeVideo.setBackground(on ? getDrawable(R.drawable.mode_on) : null);
        modePhoto.setTextColor(getColor(on ? R.color.fg_dim : R.color.fg));
        modeVideo.setTextColor(getColor(on ? R.color.fg : R.color.fg_dim));
        if (rebind && provider != null && !capturing) {
            bindCamera();
        }
    }

    private void startRecording() {
        if (videoCapture == null || recording != null || isFinishing()) {
            return;
        }
        String name = "FishEye_" + System.currentTimeMillis() + ".mp4";
        MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(
                getContentResolver(), AlbumStore.videoCollection())
                .setContentValues(AlbumStore.videoValues(name))
                .build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (audio) {
            pending = pending.withAudioEnabled();
        } else {
            Ui.toast(this, "没有录音权限，这段只录画面");
        }
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), this::onRecordEvent);
        } catch (Throwable t) {
            recording = null;
            Ui.toast(this, "开始录像失败：" + t.getMessage());
            return;
        }
        try {
            new MediaActionSound().play(MediaActionSound.START_VIDEO_RECORDING);
        } catch (Throwable ignored) {
        }
        recordStartedAt = System.currentTimeMillis();
        recTime.setText("00:00");
        recBar.setVisibility(View.VISIBLE);
        recBar.setAlpha(0f);
        recBar.animate().alpha(1f).setDuration(160).start();
        shutterDisc.setBackgroundResource(R.drawable.shutter_rec);
        main.removeCallbacks(recTick);
        main.postDelayed(recTick, 400);
    }

    private void stopRecording() {
        Recording r = recording;
        if (r == null) {
            return;
        }
        android.util.Log.d("FishEye", "stop recording: processor frames="
                + (videoProcessor == null ? -1 : videoProcessor.frames()));
        // recording 要等 Finalize 回来才置空：中途再点快门不能又开一段
        try {
            r.stop();
        } catch (Throwable t) {
            recording = null;
            restoreRecordUi();
        }
        restoreRecordUi();
    }

    private void onRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
            recording = null;
            restoreRecordUi();
            Uri out = f.getOutputResults().getOutputUri();
            if (f.hasError()) {
                android.util.Log.e("FishEye", "record finalize error " + f.getError(), f.getCause());
                // 出错时 MediaStore 里可能留了个空条目，删掉免得相册里多一张打不开的
                if (out != null) {
                    AlbumStore.delete(this, out);
                }
                Ui.toast(this, "录像失败（" + f.getError() + "）");
            } else {
                AlbumStore.videoFinalize(this, out);
                refreshThumb();
            }
        }
    }

    private void restoreRecordUi() {
        main.removeCallbacks(recTick);
        recBar.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> recBar.setVisibility(View.GONE)).start();
        shutterDisc.setBackgroundResource(R.drawable.shutter_disc);
    }

    /** 按 EXIF 角度把位图转正 */
    static Bitmap rotate(Bitmap src, int deg) {
        if (deg == 0) {
            return src;
        }
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    static int exifRotation(String path) {
        try {
            ExifInterface ex = new ExifInterface(path);
            switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    /** 解码；大图先按 2 的幂降采样，免得 12MP 原图直接吃掉 48MB */
    static Bitmap decodeSampled(String path, int maxSide) {
        try {
            return decodeSampledInner(path, maxSide);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decodeSampledInner(String path, int maxSide) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, probe);
        int sample = 1;
        int longest = Math.max(probe.outWidth, probe.outHeight);
        // 「超过上限就降一级」，不要写成「超过两倍才降」：
        // 4032px 的原图对着 2048 的上限，前者会原地不动、把 48MB 整张解出来
        while (longest / sample > maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, o);
    }

    // ------------------------------------------------------------- 缩略图 / 主题

    private void refreshThumb() {
        io.execute(() -> {
            AlbumStore.Item item = AlbumStore.latest(this);
            Bitmap bmp = item == null ? null : AlbumStore.thumbnail(this, item, 160);
            main.post(() -> {
                if (bmp != null) {
                    thumb.setImageBitmap(bmp);
                } else {
                    thumb.setImageDrawable(null);
                }
            });
        });
    }

    /** 取景暗 → 浅色字，取景亮 → 深色字；100/140 的迟滞带防抖 */
    private void onPreviewLuma(int luma) {
        main.post(() -> {
            if (darkUi && luma >= 105) {
                setDarkUi(false);
            } else if (!darkUi && luma <= 80) {
                setDarkUi(true);
            }
        });
    }

    private void setDarkUi(boolean dark) {
        if (darkUi == dark) {
            return;
        }
        darkUi = dark;
        int c = dark ? Color.WHITE : Color.BLACK;
        title.setTextColor(c);
        flipLabel.setImageTintList(android.content.res.ColorStateList.valueOf(c));
        Drawable bg = thumbWrap.getBackground();
        if (bg != null) {
            bg.setTint(dark ? 0x66FFFFFF : 0x44000000);
        }
        pushTheme();
    }
}
