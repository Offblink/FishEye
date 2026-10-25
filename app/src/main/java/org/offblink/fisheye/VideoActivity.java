package org.offblink.fisheye;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自己的录像播放器：只放本应用拍的片子。
 *
 * <p>控件全自绘（播放/暂停圆钮 + 进度条），不用系统 MediaController。
 * 拖进度条时不是「抬手才跳」：手指每移动一点就把目标时间交给解码器 seek 一帧，
 * 同时另一条线程用 {@link MediaMetadataRetriever} 取同一时刻的帧盖在上面，
 * 所以解码器慢的时候也立刻能看到对应画面。
 *
 * <p>进出沿用 Prism 那套缩放切页：从相册格子的位置放大进来，返回缩回同一格。
 */
public class VideoActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {

    public static final String EXTRA_ZOOM_FROM = "zoom_from";

    private View root;
    private TextureView surface;
    private ImageView frameOverlay;
    private PlayPauseView playBtn;
    private ScrubBar scrub;
    private TextView time;

    private Uri uri;
    private int[] zoomFrom;
    private boolean closing;

    private MediaPlayer player;
    private volatile MediaMetadataRetriever retriever;
    private ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean prepared;
    private int durationMs = 1;
    /** 片子本身的像素尺寸（按 rotation 摆正过），取帧时按屏幕缩一下 */
    private int videoW = 720;
    private int videoH = 1280;

    private boolean scrubbing;
    private boolean scrubWasPlaying;
    private boolean resumeAfterSeek;

    private boolean seekInFlight;
    private int pendingSeekMs = -1;

    /** 取帧请求的交接：pending 与 in-flight 标志必须在同一把锁里换 */
    private final Object previewLock = new Object();
    private int pendingPreviewMs = -1;
    private boolean previewInFlight;

    /** 播放中每 120ms 把进度与时间推给控件 */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (closing || player == null || !prepared) {
                return;
            }
            int pos;
            try {
                pos = player.getCurrentPosition();
            } catch (IllegalStateException e) {
                return;
            }
            if (!scrubbing) {
                scrub.setFraction(pos / (float) durationMs);
                updateTime(pos);
            }
            main.postDelayed(this, 120);
        }
    };

    /** 取帧循环：只认最新请求，解码没跟上就丢掉中间那些 */
    private final Runnable previewLoop = new Runnable() {
        @Override
        public void run() {
            while (true) {
                int ms;
                synchronized (previewLock) {
                    ms = pendingPreviewMs;
                    pendingPreviewMs = -1;
                    if (ms < 0) {
                        // 清标志和查队列必须在同一把锁里，否则最后一帧会被丢掉
                        previewInFlight = false;
                        break;
                    }
                }
                Bitmap bmp = null;
                try {
                    bmp = grabFrame(ms);
                } catch (Throwable ignored) {
                    bmp = null;
                }
                if (bmp != null) {
                    final Bitmap shot = bmp;
                    main.post(() -> {
                        if (!closing && scrubbing) {
                            frameOverlay.setImageBitmap(shot);
                        }
                    });
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_video);

        root = findViewById(R.id.vid_root);
        surface = findViewById(R.id.vid_surface);
        frameOverlay = findViewById(R.id.vid_frame);
        playBtn = findViewById(R.id.vid_play);
        scrub = findViewById(R.id.vid_scrub);
        time = findViewById(R.id.vid_time);

        zoomFrom = getIntent().getIntArrayExtra(EXTRA_ZOOM_FROM);
        if (zoomFrom != null) {
            Ui.zoomIn(this, root, zoomFrom);
        }
        applyInsets();

        uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fisheye-videoframe");
            t.setDaemon(true);
            return t;
        });
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(this, uri);
            readVideoSize();
        } catch (Throwable t) {
            retriever = null;
        }

        surface.setSurfaceTextureListener(this);

        findViewById(R.id.vid_back).setOnClickListener(v -> close());
        playBtn.setOnClickListener(v -> toggle());
        scrub.setListener(new ScrubBar.Listener() {
            @Override
            public void onScrubStart() {
                if (player == null || !prepared) {
                    return;
                }
                scrubbing = true;
                scrubWasPlaying = player.isPlaying();
                if (scrubWasPlaying) {
                    player.pause();
                }
                playBtn.setPlaying(false);
                main.removeCallbacks(tick);
                frameOverlay.setVisibility(View.VISIBLE);
            }

            @Override
            public void onScrub(float fraction) {
                if (player == null || !prepared) {
                    return;
                }
                int ms = (int) (fraction * durationMs);
                updateTime(ms);
                seekTo(ms);
                requestFrame(ms);
            }

            @Override
            public void onScrubEnd(float fraction) {
                if (player == null || !prepared) {
                    scrubbing = false;
                    frameOverlay.setVisibility(View.GONE);
                    return;
                }
                scrubbing = false;
                resumeAfterSeek = scrubWasPlaying;
                seekTo((int) (fraction * durationMs));
                main.removeCallbacks(tick);
                main.post(tick);
            }
        });
    }

    /** 播放条与时间跟着系统栏让位，画面本身铺满 */
    private void applyInsets() {
        View controls = findViewById(R.id.vid_controls);
        View back = findViewById(R.id.vid_back);
        final int cBottom = controls.getPaddingBottom();
        final int bTop = back.getPaddingTop();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            androidx.core.view.WindowInsetsCompat in =
                    androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v);
            androidx.core.graphics.Insets bars =
                    in.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            controls.setPadding(controls.getPaddingLeft(), controls.getPaddingTop(),
                    controls.getPaddingRight(), cBottom + bars.bottom);
            back.setPadding(back.getPaddingLeft(), bTop + bars.top,
                    back.getPaddingRight(), back.getPaddingBottom());
            return insets;
        });
    }

    // ------------------------------------------------------------- 播放控制

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
        if (player == null) {
            startPlayer(new Surface(st));
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
        releasePlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
    }

    private void startPlayer(Surface s) {
        player = new MediaPlayer();
        player.setSurface(s);
        player.setOnPreparedListener(mp -> {
            prepared = true;
            durationMs = Math.max(1, mp.getDuration());
            updateTime(0);
            scrub.setFraction(0f);
            mp.start();
            playBtn.setPlaying(true);
            main.removeCallbacks(tick);
            main.post(tick);
        });
        player.setOnSeekCompleteListener(mp -> {
            seekInFlight = false;
            if (pendingSeekMs >= 0) {
                doSeek();
                return;
            }
            if (!scrubbing) {
                frameOverlay.setVisibility(View.GONE);
            }
            if (resumeAfterSeek && prepared && player != null) {
                try {
                    if (!player.isPlaying()) {
                        player.start();
                        playBtn.setPlaying(true);
                    }
                } catch (IllegalStateException ignored) {
                    // 解码器刚好在重建
                }
            }
            resumeAfterSeek = false;
        });
        player.setOnCompletionListener(mp -> {
            playBtn.setPlaying(false);
            scrub.setFraction(1f);
            updateTime(durationMs);
            main.removeCallbacks(tick);
        });
        player.setOnErrorListener((mp, what, extra) -> {
            Ui.toast(this, "这段视频放不了（" + what + "/" + extra + "）");
            playBtn.setPlaying(false);
            return true;
        });
        try {
            player.setDataSource(this, uri);
            player.prepareAsync();
        } catch (Exception e) {
            Ui.toast(this, "打不开视频：" + e.getMessage());
        }
    }

    private void releasePlayer() {
        prepared = false;
        main.removeCallbacks(tick);
        if (player != null) {
            try {
                player.reset();
            } catch (Throwable ignored) {
                // 已经处于错误状态就跳过
            }
            player.release();
            player = null;
        }
    }

    private void toggle() {
        if (player == null || !prepared) {
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                playBtn.setPlaying(false);
                main.removeCallbacks(tick);
            } else {
                if (durationMs > 0 && player.getCurrentPosition() >= durationMs - 80) {
                    player.seekTo(0);
                }
                player.start();
                playBtn.setPlaying(true);
                main.removeCallbacks(tick);
                main.post(tick);
            }
        } catch (IllegalStateException ignored) {
            // 解码器正好在重建，下一次点击再来
        }
    }

    /** 只发一次 seek：还在飞的请求不叠加，最新目标排在下一次回调之后 */
    private void seekTo(int ms) {
        pendingSeekMs = Math.max(0, Math.min(durationMs, ms));
        if (!seekInFlight) {
            doSeek();
        }
    }

    private void doSeek() {
        int target = pendingSeekMs;
        pendingSeekMs = -1;
        if (player == null || !prepared) {
            return;
        }
        try {
            seekInFlight = true;
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } catch (IllegalStateException e) {
            seekInFlight = false;
        }
    }

    private void updateTime(int pos) {
        time.setText(Ui.duration(pos) + " / " + Ui.duration(durationMs));
    }

    // --------------------------------------------------------------- 取帧

    private void requestFrame(int ms) {
        synchronized (previewLock) {
            pendingPreviewMs = ms;
            if (previewInFlight) {
                return;
            }
            previewInFlight = true;
        }
        if (io != null) {
            io.execute(previewLoop);
        }
    }

    private void readVideoSize() {
        try {
            int w = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = 0;
            String r = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (r != null) {
                rot = Integer.parseInt(r);
            }
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            if (w > 0 && h > 0) {
                videoW = w;
                videoH = h;
            }
        } catch (Throwable ignored) {
            // 元数据缺失就按竖屏默认值缩
        }
    }

    /** 取「那个时刻」的帧：拖动时立刻能看到画面，不用等解码器 seek 完 */
    private Bitmap grabFrame(int ms) {
        MediaMetadataRetriever r = retriever;
        if (r == null) {
            return null;
        }
        long us = Math.max(0, ms) * 1000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // 缩到屏幕量级就行，别每次都解一张 720×1280 的原图
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxSide = Math.max(dm.widthPixels, dm.heightPixels);
            float k = Math.min(1f, maxSide / (float) Math.max(videoW, videoH));
            return r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST,
                    Math.max(2, Math.round(videoW * k)), Math.max(2, Math.round(videoH * k)));
        }
        return r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST);
    }

    // --------------------------------------------------------------- 生命周期

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && prepared) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (IllegalStateException ignored) {
                // 解码器正在重配
            }
            playBtn.setPlaying(false);
            main.removeCallbacks(tick);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closing = true;
        releasePlayer();
        MediaMetadataRetriever r = retriever;
        retriever = null;
        if (io != null) {
            io.shutdownNow();
            try {
                // 取帧线程可能正卡在解码里：等它出来再释放，别让 native 先没了
                io.awaitTermination(400, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignored) {
                // 已经释放过
            }
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
        releasePlayer();
        if (zoomFrom != null) {
            Ui.shrinkFinish(this, findViewById(R.id.vid_root), zoomFrom);
        } else {
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
