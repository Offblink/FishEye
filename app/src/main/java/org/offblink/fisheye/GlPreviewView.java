package org.offblink.fisheye;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 自己的取景器：SurfaceView + 一条专跑 GL 的线程。
 *
 * <p>相机帧从 CameraX 的 {@link SurfaceRequest} 进来（外部纹理 SurfaceTexture），
 * 走两趟 shader（几何/色散/影调 → 缩放模糊/暗角/颗粒），直接 swap 到 SurfaceView 的窗口上。
 * 全程零 CPU 回读——唯一的回读是每 500ms 取一张 16×16 缩图算取景亮度，用来反色界面文字。
 *
 * <p>同一个实例也负责「烘焙」：把一张 Bitmap 走完全相同的两趟 shader 渲染到离屏 FBO，
 * 再 readPixels 回 Bitmap。预览与成片因此共用一份 GLSL，不存在两边跑偏的可能。
 */
public class GlPreviewView extends SurfaceView implements SurfaceHolder.Callback, Preview.SurfaceProvider {

    public interface LumaListener {
        void onLuma(int luma);
    }

    public interface BakeCallback {
        void onBaked(Bitmap bitmap);
    }

    /** GL 侧失败时把原因抛给界面：宁可弹一条提示，也不要留一片黑 */
    public interface ErrorListener {
        void onGlError(String message);
    }

    private static final float[] QUAD = {
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    };

    private final HandlerThread thread = new HandlerThread("fisheye-gl");
    private Handler gl;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> main.post(command);

    private final Params target = new Params();
    private final Params smooth = new Params();
    private volatile float focusX = 0.5f;
    private volatile float focusY = 0.5f;
    private volatile LumaListener lumaListener;
    private volatile ErrorListener errorListener;
    private int framesDrawn;
    private long lastLumaAt;

    private EGLDisplay eglDpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglCtx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglCfg;
    private EGLSurface eglSrf = EGL14.EGL_NO_SURFACE;
    private boolean glInit;
    private boolean programsReady;

    private Program progExt;
    private Program prog2d;
    private Program progPost;
    /** 预览缓冲相对视图的缩放：慢设备上会自动降级 */
    private float previewScale = 1f;
    private long frameAccMs;
    private int frameAccN;
    private int vao;
    private int vbo;
    private int fbo1 = -1, tex1 = -1;
    private int fbo2 = -1, tex2 = -1;
    private int fbW, fbH;

    private int surfaceW, surfaceH;

    // 相机源
    private int extId;
    private SurfaceTexture camTex;
    private Surface camSurface;
    private SurfaceRequest pendingRequest;
    private int srcW = 1, srcH = 1;
    private int camRotDeg;
    private final AtomicBoolean frameQueued = new AtomicBoolean(false);
    private volatile boolean camBound;


    // 静止图源（调色页 / 烘焙）
    private int imgTex = -1;
    private int imgW = 1, imgH = 1;
    private boolean imageMode;
    private Bitmap pendingImage;

    public GlPreviewView(Context context) {
        this(context, null);
    }

    public GlPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
        setZOrderMediaOverlay(false);
    }

    // ------------------------------------------------------------------ 对外

    public void setParams(Params p) {
        target.copyFrom(p);
        if (imageMode) {
            requestRender();
        }
    }

    public Params params() {
        return target;
    }

    public void setLumaListener(LumaListener l) {
        lumaListener = l;
    }

    public void setErrorListener(ErrorListener l) {
        errorListener = l;
    }

    /** 已经画出去的帧数：界面用它判断「相机到底有没有给画面」 */
    public int framesDrawn() {
        return framesDrawn;
    }

    private void fail(String message) {
        android.util.Log.e("FishEye", message);
        ErrorListener l = errorListener;
        if (l != null) {
            l.onGlError(message);
        }
    }

    /** 缩放模糊的中心点，归一化到 [0,1]（点按对焦时跟着手指走） */
    public void setFocus(float x, float y) {
        focusX = Math.max(0.05f, Math.min(0.95f, x));
        focusY = Math.max(0.05f, Math.min(0.95f, y));
    }

    /** 切到静止图模式：同一套 shader 渲染一张 Bitmap */
    public void setImage(Bitmap bitmap) {
        imageMode = true;
        final Bitmap src = bitmap;
        gl.post(() -> {
            pendingImage = src;
            if (programsReady) {
                uploadImage(src);
                pendingImage = null;
                drawImage();
            }
        });
    }

    /**
     * 把一张照片按当前参数烘焙成 Bitmap。跑在 GL 线程上，用的是刚刚预览用的那一套程序，
     * 所以成片与取景一致；调用方负责回收返回的 Bitmap。
     */
    public void bake(final Bitmap src, final int rotationDeg, final BakeCallback cb) {
        final Params snapshot = new Params();
        snapshot.copyFrom(target);
        gl.post(() -> {
            Bitmap out = null;
            try {
                out = bakeOnGl(src, rotationDeg, snapshot);
            } catch (Throwable t) {
                out = null;
            }
            final Bitmap result = out;
            main.post(() -> cb.onBaked(result));
        });
    }

    // ------------------------------------------------------- Surface lifecycle

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (!thread.isAlive()) {
            thread.start();
            gl = new Handler(thread.getLooper());
            gl.post(this::initGl);
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        final Surface s = holder.getSurface();
        if (s == null || !s.isValid()) {
            return;
        }
        gl.post(() -> bindSurface(s, width, height));
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        gl.post(this::destroyWindowSurface);
    }

    // --------------------------------------------------------- 相机 Surface

    @Override
    public void onSurfaceRequested(@NonNull SurfaceRequest request) {
        pendingRequest = request;
        gl.post(this::consumeRequest);
    }

    private void consumeRequest() {
        SurfaceRequest req = pendingRequest;
        if (req == null || !programsReady) {
            return;
        }
        pendingRequest = null;
        Size size = req.getResolution();
        srcW = Math.max(1, size.getWidth());
        srcH = Math.max(1, size.getHeight());
        ensureCameraTexture();
        camTex.setDefaultBufferSize(srcW, srcH);
        if (camSurface != null) {
            camSurface.release();
        }
        camSurface = new Surface(camTex);
        camBound = true;
        req.setTransformationInfoListener(mainExecutor, info -> {
            camRotDeg = info.getRotationDegrees();
            if (imageMode) {
                return;
            }
            requestRender();
        });
        req.provideSurface(camSurface, mainExecutor, result -> {
            // 相机侧用完（切页、被抢占）时把 Surface 还回去；SurfaceTexture 留着复用
            if (result.getResultCode() != SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY) {
                camBound = false;
            }
        });
    }

    private void ensureCameraTexture() {
        if (camTex != null) {
            return;
        }
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        extId = ids[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, extId);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        camTex = new SurfaceTexture(extId);
        camTex.setOnFrameAvailableListener(st -> requestRender(), gl);
    }

    private void requestRender() {
        if (frameQueued.compareAndSet(false, true)) {
            gl.post(this::drawCamera);
        }
    }

    // ------------------------------------------------------------------- EGL

    private void initGl() {
        eglDpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDpy, ver, 0, ver, 1);

        int[] renderables = {EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_OPENGL_ES2_BIT};
        int[] base = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
        };
        for (int bit : renderables) {
            int[] attrs = new int[base.length + 2];
            attrs[0] = EGL14.EGL_RENDERABLE_TYPE;
            attrs[1] = bit;
            System.arraycopy(base, 0, attrs, 2, base.length);
            EGLConfig[] cfgs = new EGLConfig[1];
            int[] n = new int[1];
            if (EGL14.eglChooseConfig(eglDpy, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) {
                eglCfg = cfgs[0];
                break;
            }
        }
        if (eglCfg == null) {
            fail("找不到可用的 EGL 配置（设备不支持 OpenGL ES 3）");
            return;
        }
        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        eglCtx = EGL14.eglCreateContext(eglDpy, eglCfg, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (eglCtx == EGL14.EGL_NO_CONTEXT) {
            fail("EGL 上下文创建失败（错误码 " + EGL14.eglGetError() + "）");
            return;
        }
        glInit = true;
        // 程序和纹理都得等 eglMakeCurrent 之后才能建，否则所有 GL 调用都是空操作
        main.post(this::tryBind);
    }

    /** 主线程拿当前窗口的 Surface 与尺寸，再回 GL 线程绑上去 */
    private void tryBind() {
        SurfaceHolder h = getHolder();
        Surface s = h.getSurface();
        if (s == null || !s.isValid()) {
            return;
        }
        final Surface surface = s;
        final int w = getWidth();
        final int hgt = getHeight();
        gl.post(() -> bindSurface(surface, w, hgt));
    }

    private void bindSurface(Surface surface, int w, int h) {
        if (!glInit) {
            return;
        }
        surfaceW = Math.max(1, w);
        surfaceH = Math.max(1, h);
        recreateWindowSurface(surface);
        if (eglSrf == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDpy, eglSrf, eglSrf, eglCtx)) {
            return;
        }
        if (!programsReady) {
            progExt = Program.build(Shaders.VERTEX, Shaders.MAIN_EXTERNAL);
            prog2d = Program.build(Shaders.VERTEX, Shaders.MAIN_2D);
            progPost = Program.build(Shaders.VERTEX, Shaders.POST);
            if (progExt == null || prog2d == null || progPost == null) {
                fail("着色器编译失败：" + Program.lastError);
                return;
            }
            int[] vaoIds = new int[1];
            GLES30.glGenVertexArrays(1, vaoIds, 0);
            vao = vaoIds[0];
            int[] vboIds = new int[1];
            GLES30.glGenBuffers(1, vboIds, 0);
            vbo = vboIds[0];
            FloatBuffer buf = ByteBuffer.allocateDirect(QUAD.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buf.put(QUAD).position(0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, QUAD.length * 4, buf, GLES30.GL_STATIC_DRAW);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0);
            programsReady = true;
            ensureCameraTexture();
            if (imageMode && pendingImage != null) {
                uploadImage(pendingImage);
                pendingImage = null;
            }
        }
        consumeRequest();
        if (imageMode) {
            drawImage();
        } else {
            requestRender();
        }
    }

    private void recreateWindowSurface(Surface surface) {
        if (!glInit || surface == null) {
            return;
        }
        if (eglSrf != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDpy, eglSrf);
            eglSrf = EGL14.EGL_NO_SURFACE;
        }
        eglSrf = EGL14.eglCreateWindowSurface(eglDpy, eglCfg, surface, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSrf == EGL14.EGL_NO_SURFACE) {
            fail("EGL 窗口表面创建失败（错误码 " + EGL14.eglGetError() + "）");
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDpy, eglSrf, eglSrf, eglCtx)) {
            fail("eglMakeCurrent 失败（错误码 " + EGL14.eglGetError() + "）");
        }
    }

    private void destroyWindowSurface() {
        if (eglDpy == EGL14.EGL_NO_DISPLAY) {
            return;
        }
        if (eglSrf != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(eglDpy, eglSrf);
            eglSrf = EGL14.EGL_NO_SURFACE;
        }
    }

    // ---------------------------------------------------------------- 绘制

    private void drawCamera() {
        frameQueued.set(false);
        if (!programsReady || camTex == null || eglSrf == EGL14.EGL_NO_SURFACE || imageMode) {
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDpy, eglSrf, eglSrf, eglCtx)) {
            return;
        }
        final long startMs = SystemClock.uptimeMillis();
        try {
            camTex.updateTexImage();
        } catch (Throwable t) {
            return;
        }
        smooth.approach(target, 0.22f);
        ensureFbo(surfaceW, surfaceH);
        int rw = (camRotDeg % 180 == 0) ? srcW : srcH;
        int rh = (camRotDeg % 180 == 0) ? srcH : srcW;
        render(progExt, extId, true, 1f / srcW, 1f / srcH,
                (float) Math.toRadians(-camRotDeg), rw, rh, false);
        EGL14.eglSwapBuffers(eglDpy, eglSrf);
        framesDrawn++;
        adaptResolution(SystemClock.uptimeMillis() - startMs);
    }

    /**
     * 慢 GPU（软件渲染的模拟器、老机型）上把预览缓冲降一级。
     * 降的是 SurfaceView 的缓冲区尺寸，合成器负责放大，画面比例不变。
     */
    private void adaptResolution(long frameMs) {
        if (previewScale <= 0.45f) {
            return;
        }
        frameAccMs += frameMs;
        frameAccN++;
        if (frameAccN < 8) {
            return;
        }
        long avg = frameAccMs / frameAccN;
        frameAccMs = 0;
        frameAccN = 0;
        if (avg > 70) {
            previewScale *= 0.55f;
            main.post(() -> {
                int vw = getWidth();
                int vh = getHeight();
                if (vw > 0 && vh > 0) {
                    getHolder().setFixedSize(
                            Math.max(2, Math.round(vw * previewScale)),
                            Math.max(2, Math.round(vh * previewScale)));
                }
            });
        }
    }

    private void drawImage() {
        if (!programsReady || imgTex < 0 || eglSrf == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (!EGL14.eglMakeCurrent(eglDpy, eglSrf, eglSrf, eglCtx)) {
            return;
        }
        smooth.approach(target, 1f);
        ensureFbo(surfaceW, surfaceH);
        render(prog2d, imgTex, false, 1f / imgW, 1f / imgH, 0f, imgW, imgH, true);
        EGL14.eglSwapBuffers(eglDpy, eglSrf);
    }

    /**
     * 一趟完整管线：主 shader 渲进 fbo1，第二 pass 渲到当前 target（窗口或 fbo2）。
     *
     * @param external 源是不是相机外部纹理
     * @param rot      采样坐标旋转（弧度）
     * @param srcAw    摆正后的源宽（算 cover 用）
     */
    private void render(Program main, int tex, boolean external, float texelX, float texelY,
                        float rot, int srcAw, int srcAh, boolean imageSrc) {
        int outW = (fbo1 >= 0) ? fbW : surfaceW;
        int outH = (fbo1 >= 0) ? fbH : surfaceH;

        float targetAspect = outW / (float) Math.max(1, outH);
        float srcAspect = srcAw / (float) Math.max(1, srcAh);
        float sx = 1f, sy = 1f;
        if (srcAspect > targetAspect) {
            sx = targetAspect / srcAspect;
        } else {
            sy = srcAspect / targetAspect;
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo1);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(main.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(external ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(main.loc("uTex"), 0);
        GLES30.glUniform2f(main.loc("uScale"), sx, sy);
        GLES30.glUniform1f(main.loc("uRot"), rot);
        GLES30.glUniform1f(main.loc("uFlipY"), imageSrc ? 1f : 0f);
        GLES30.glUniform1f(main.loc("uAspect"), targetAspect);
        GLES30.glUniform1f(main.loc("uFish"), smooth.fisheye);
        GLES30.glUniform1f(main.loc("uChroma"), smooth.chroma);
        GLES30.glUniform1f(main.loc("uExposure"), smooth.exposure);
        GLES30.glUniform1f(main.loc("uContrast"), smooth.contrast);
        GLES30.glUniform1f(main.loc("uSaturation"), smooth.saturation);
        GLES30.glUniform1f(main.loc("uLocal"), smooth.local);
        GLES30.glUniform2f(main.loc("uTexel"), texelX, texelY);
        drawQuad(main);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, surfaceW, surfaceH);
        GLES30.glUseProgram(progPost.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex1);
        GLES30.glUniform1i(progPost.loc("uTex"), 0);
        GLES30.glUniform2f(progPost.loc("uResolution"), outW, outH);
        GLES30.glUniform2f(progPost.loc("uFocus"), focusX, focusY);
        GLES30.glUniform1f(progPost.loc("uBlur"), smooth.blur);
        GLES30.glUniform1f(progPost.loc("uVignette"), smooth.vignette);
        GLES30.glUniform1f(progPost.loc("uGrain"), smooth.grain);
        GLES30.glUniform1f(progPost.loc("uTime"), (System.nanoTime() % 1000000000L) / 1e6f);
        drawQuad(progPost);

        sampleLumaIfDue(outW, outH);
    }

    private void drawQuad(Program p) {
        GLES30.glBindVertexArray(vao);
        int pos = p.attrib("aPos");
        if (pos >= 0) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glEnableVertexAttribArray(pos);
            GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, 0);
        }
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glBindVertexArray(0);
    }

    /** 每 500ms 从 tex1 采一张 16×16，算屏幕上真实亮度，交给界面反色 */
    private void sampleLumaIfDue(int outW, int outH) {
        long now = System.currentTimeMillis();
        LumaListener l = lumaListener;
        if (l == null || now - lastLumaAt < 500) {
            return;
        }
        lastLumaAt = now;
        if (fbo2 < 0) {
            int[] f = new int[1];
            int[] t = new int[1];
            GLES30.glGenFramebuffers(1, f, 0);
            GLES30.glGenTextures(1, t, 0);
            fbo2 = f[0];
            tex2 = t[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex2);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 16, 16, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo2);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, tex2, 0);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo2);
        GLES30.glViewport(0, 0, 16, 16);
        GLES30.glUseProgram(progPost.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex1);
        GLES30.glUniform1i(progPost.loc("uTex"), 0);
        GLES30.glUniform2f(progPost.loc("uResolution"), outW, outH);
        GLES30.glUniform2f(progPost.loc("uFocus"), 0.5f, 0.5f);
        GLES30.glUniform1f(progPost.loc("uBlur"), 0f);
        GLES30.glUniform1f(progPost.loc("uVignette"), 0f);
        GLES30.glUniform1f(progPost.loc("uGrain"), 0f);
        GLES30.glUniform1f(progPost.loc("uTime"), 0f);
        drawQuad(progPost);

        ByteBuffer px = ByteBuffer.allocateDirect(16 * 16 * 4);
        GLES30.glReadPixels(0, 0, 16, 16, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
        // 只看屏幕下方三分之一（控件文字压着的那一带）：
        // readback 的行序自下而上，row 0 就是屏幕最底边
        int rows = 6, n = rows * 16;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < 16; x++) {
                int i = (y * 16 + x) * 4;
                r += px.get(i) & 0xFF;
                g += px.get(i + 1) & 0xFF;
                b += px.get(i + 2) & 0xFF;
            }
        }
        int luma = (int) ((r / (double) n) * 0.2126 + (g / (double) n) * 0.7152
                + (b / (double) n) * 0.0722);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        l.onLuma(luma);
    }

    private void ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (fbo1 >= 0 && w == fbW && h == fbH) {
            return;
        }
        if (fbo1 >= 0) {
            GLES30.glDeleteFramebuffers(1, new int[]{fbo1}, 0);
            GLES30.glDeleteTextures(1, new int[]{tex1}, 0);
            fbo1 = -1;
            tex1 = -1;
        }
        int[] f = new int[1];
        int[] t = new int[1];
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glGenTextures(1, t, 0);
        fbo1 = f[0];
        tex1 = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo1);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex1, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        fbW = w;
        fbH = h;
    }

    // -------------------------------------------------------------- 静止图

    private void uploadImage(Bitmap bmp) {
        if (imgTex >= 0) {
            GLES30.glDeleteTextures(1, new int[]{imgTex}, 0);
        }
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        imgTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0);
        imgW = Math.max(1, bmp.getWidth());
        imgH = Math.max(1, bmp.getHeight());
    }

    /** 离屏烘焙：fbo1（主 shader）→ fbo2（第二 pass）→ readPixels → Bitmap */
    private Bitmap bakeOnGl(Bitmap src, int rotationDeg, Params p) {
        Params saved = new Params();
        saved.copyFrom(smooth);
        smooth.copyFrom(p);

        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = (rotationDeg % 180) != 0;
        int outW = swap ? h : w;
        int outH = swap ? w : h;
        // 超大照片先降到 4096 以内：GL 纹理上限因机型而异，宁可小一点也不炸
        float scale = Math.min(1f, 4096f / Math.max(outW, outH));
        outW = Math.max(1, Math.round(outW * scale));
        outH = Math.max(1, Math.round(outH * scale));

        Bitmap scaled = src;
        if (scale < 1f) {
            scaled = Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
        }
        uploadImage(scaled);
        int sw = imgW, sh = imgH;
        int rw = swap ? sh : sw;
        int rh = swap ? sw : sh;

        ensureFbo(outW, outH);
        int mainFbo = fbo1, mainTex = tex1;
        int[] f = new int[1];
        int[] t = new int[1];
        GLES30.glGenFramebuffers(1, f, 0);
        GLES30.glGenTextures(1, t, 0);
        int outFbo = f[0], outTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, outW, outH, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

        // pass 1
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mainFbo);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(prog2d.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, imgTex);
        GLES30.glUniform1i(prog2d.loc("uTex"), 0);
        GLES30.glUniform2f(prog2d.loc("uScale"), 1f, 1f);
        GLES30.glUniform1f(prog2d.loc("uRot"), (float) Math.toRadians(rotationDeg));
        GLES30.glUniform1f(prog2d.loc("uFlipY"), 1f);
        GLES30.glUniform1f(prog2d.loc("uAspect"), outW / (float) outH);
        GLES30.glUniform1f(prog2d.loc("uFish"), smooth.fisheye);
        GLES30.glUniform1f(prog2d.loc("uChroma"), smooth.chroma);
        GLES30.glUniform1f(prog2d.loc("uExposure"), smooth.exposure);
        GLES30.glUniform1f(prog2d.loc("uContrast"), smooth.contrast);
        GLES30.glUniform1f(prog2d.loc("uSaturation"), smooth.saturation);
        GLES30.glUniform1f(prog2d.loc("uLocal"), smooth.local);
        GLES30.glUniform2f(prog2d.loc("uTexel"), 1f / Math.max(1, rw), 1f / Math.max(1, rh));
        drawQuad(prog2d);

        // pass 2
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, outTex, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(progPost.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mainTex);
        GLES30.glUniform1i(progPost.loc("uTex"), 0);
        GLES30.glUniform2f(progPost.loc("uResolution"), outW, outH);
        GLES30.glUniform2f(progPost.loc("uFocus"), focusX, focusY);
        GLES30.glUniform1f(progPost.loc("uBlur"), smooth.blur);
        GLES30.glUniform1f(progPost.loc("uVignette"), smooth.vignette);
        GLES30.glUniform1f(progPost.loc("uGrain"), smooth.grain);
        GLES30.glUniform1f(progPost.loc("uTime"), 12345f);
        drawQuad(progPost);

        // 回读并上下翻（GL 的行序是自下而上）
        ByteBuffer px = ByteBuffer.allocateDirect(outW * outH * 4);
        GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px);
        int[] row = new int[outW];
        int[] all = new int[outW * outH];
        for (int y = 0; y < outH; y++) {
            int srcRow = outH - 1 - y;
            for (int x = 0; x < outW; x++) {
                int i = (srcRow * outW + x) * 4;
                row[x] = 0xFF000000 | ((px.get(i) & 0xFF) << 16)
                        | ((px.get(i + 1) & 0xFF) << 8) | (px.get(i + 2) & 0xFF);
            }
            System.arraycopy(row, 0, all, y * outW, outW);
        }
        Bitmap out = Bitmap.createBitmap(all, outW, outH, Bitmap.Config.ARGB_8888);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(1, new int[]{outFbo}, 0);
        GLES30.glDeleteTextures(1, new int[]{outTex}, 0);
        smooth.copyFrom(saved);
        return out;
    }

    /** 编译 + 建 uniform 位置表的小包装 */
    static final class Program {
        static volatile String lastError = "";
        final int id;
        private final Map<String, Integer> locs = new HashMap<>();
        private final Map<String, Integer> attribs = new HashMap<>();

        private Program(int id) {
            this.id = id;
        }

        static Program build(String vs, String fs) {
            int v = compile(GLES30.GL_VERTEX_SHADER, vs);
            int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) {
                return null;
            }
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, v);
            GLES30.glAttachShader(p, f);
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            if (ok[0] == 0) {
                lastError = GLES30.glGetProgramInfoLog(p);
                GLES30.glDeleteProgram(p);
                return null;
            }
            return new Program(p);
        }

        int attrib(String name) {
            Integer v = attribs.get(name);
            if (v == null) {
                v = GLES30.glGetAttribLocation(id, name);
                attribs.put(name, v);
            }
            return v;
        }

        int loc(String name) {
            Integer v = locs.get(name);
            if (v == null) {
                v = GLES30.glGetUniformLocation(id, name);
                locs.put(name, v);
            }
            return v;
        }

        private static int compile(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                lastError = GLES30.glGetShaderInfoLog(s);
                GLES30.glDeleteShader(s);
                return 0;
            }
            return s;
        }
    }
}
