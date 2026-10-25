package org.offblink.fisheye;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.SurfaceProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 录像支路：相机帧（外部纹理）→ 两趟张力管线 → 编码器的输入 Surface。
 *
 * <p>接法是 CameraX 的 {@code CameraEffect}：给 {@link SurfaceProcessor} 一个只给
 * VideoCapture 用的注入点，取景器那条链路完全不动。两边的画面之所以一致，
 * 是因为都走 {@link TensionRenderer} 里同一份 GLSL。
 *
 * <p>方向不在 GL 里自己算：每一帧从 {@link SurfaceTexture#getTransformMatrix} 拿到
 * buffer 朝向，再交给 {@link SurfaceOutput#updateTransformMatrix} 叠上 CameraX 要的
 * 旋转/镜像/裁剪，得到的矩阵就是采样矩阵（正好是 shader 的 {@code uSt}）。
 * 这样竖屏录出来就是正的，不用自己推 sensor 朝向。
 *
 * <p>整个类只在一条 GL 线程上跑：CameraX 通过 {@link #executor()} 回调，
 * SurfaceTexture 的帧回调也挂同一个 Handler。
 */
final class VideoProcessor implements SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {

    interface ErrorListener {
        void onError(String message);
    }

    /** 相机帧 + 编码器输出的 EGL 绑定 */
    private static final class Out {
        final SurfaceOutput output;
        final Surface surface;
        final EGLSurface egl;
        final int w, h;
        final float[] updated = new float[16];

        Out(SurfaceOutput output, Surface surface, EGLSurface egl, int w, int h) {
            this.output = output;
            this.surface = surface;
            this.egl = egl;
            this.w = w;
            this.h = h;
        }
    }

    private final HandlerThread thread = new HandlerThread("fisheye-video-gl");
    private final Handler handler;
    private final Executor executor;

    private final Params target = new Params();
    private final Params smooth = new Params();
    private volatile float focusX = 0.5f;
    private volatile float focusY = 0.5f;
    private volatile ErrorListener errorListener;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private boolean glReady;
    private boolean released;

    private int texId;
    private SurfaceTexture camTex;
    private Surface camSurface;
    private int srcW = 1, srcH = 1;
    private volatile int frames;
    private int rotDeg;

    /** 这一路自己的 GL 程序（与取景器的实例互不相干） */
    private final TensionRenderer renderer = new TensionRenderer();

    private final float[] st4 = new float[16];
    private final float[] st3 = new float[9];
    private final List<Out> outputs = new ArrayList<>();

    VideoProcessor() {
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = handler::post;
    }

    /** CameraX 的回调执行器，也是这一整套 GL 的线程 */
    Executor executor() {
        return executor;
    }

    void setErrorListener(ErrorListener l) {
        errorListener = l;
    }

    /** 主线程每次改参数都调一次：GL 线程拿一份快照，滑块动起来才有逐帧平滑 */
    void setParams(Params p) {
        final Params copy = new Params();
        copy.copyFrom(p);
        handler.post(() -> target.copyFrom(copy));
    }

    void setFocus(float x, float y) {
        focusX = Math.max(0.05f, Math.min(0.95f, x));
        focusY = Math.max(0.05f, Math.min(0.95f, y));
    }

    /** 已经处理过多少帧：界面用它判断「录像管线到底有没有在跑」 */
    int frames() {
        return frames;
    }

    private void fail(String message) {
        android.util.Log.e("FishEye", message);
        ErrorListener l = errorListener;
        if (l != null) {
            l.onError(message);
        }
    }

    // ------------------------------------------------------- SurfaceProcessor

    @Override
    public void onInputSurface(SurfaceRequest request) {
        if (released) {
            request.willNotProvideSurface();
            return;
        }
        if (!ensureGl()) {
            request.willNotProvideSurface();
            return;
        }
        Size size = request.getResolution();
        srcW = Math.max(1, size.getWidth());
        srcH = Math.max(1, size.getHeight());
        if (camTex == null) {
            int[] ids = new int[1];
            GLES30.glGenTextures(1, ids, 0);
            texId = ids[0];
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            camTex = new SurfaceTexture(texId);
            camTex.setOnFrameAvailableListener(this, handler);
        }
        camTex.setDefaultBufferSize(srcW, srcH);
        if (camSurface != null) {
            camSurface.release();
        }
        camSurface = new Surface(camTex);
        request.setTransformationInfoListener(executor, info -> rotDeg = info.getRotationDegrees());
        request.provideSurface(camSurface, executor, result -> {
            android.util.Log.d("FishEye", "video input surface done: " + result.getResultCode());
        });
        android.util.Log.d("FishEye", "video processor input " + srcW + "x" + srcH);
    }

    @Override
    public void onOutputSurface(SurfaceOutput output) {
        if (!ensureGl()) {
            output.close();
            return;
        }
        final Surface surface = output.getSurface(executor, event -> dropOutput(output));
        Size size = output.getSize();
        EGLSurface egl = createWindowSurface(surface);
        if (egl == EGL14.EGL_NO_SURFACE) {
            output.close();
            return;
        }
        outputs.add(new Out(output, surface, egl, Math.max(1, size.getWidth()),
                Math.max(1, size.getHeight())));
        android.util.Log.d("FishEye", "video processor output " + size + " targets="
                + output.getTargets());
    }

    private void dropOutput(SurfaceOutput output) {
        for (int i = outputs.size() - 1; i >= 0; i--) {
            Out o = outputs.get(i);
            if (o.output != output) {
                continue;
            }
            outputs.remove(i);
            makeCurrentPBuffer();
            if (o.egl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(dpy, o.egl);
            }
            // Surface 是 CameraX 的，自己不能 release；close 才是「我不再写了」的确认
            o.output.close();
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (!glReady || released || outputs.isEmpty()) {
            return;
        }
        try {
            surfaceTexture.updateTexImage();
        } catch (Throwable t) {
            return;
        }
        surfaceTexture.getTransformMatrix(st4);
        final long timestampNs = surfaceTexture.getTimestamp();
        smooth.approach(target, 0.22f);
        final float timeMs = (System.nanoTime() % 1000000000L) / 1e6f;
        for (int i = 0; i < outputs.size(); i++) {
            Out o = outputs.get(i);
            if (o.egl == EGL14.EGL_NO_SURFACE) {
                continue;
            }
            // CameraX 的附加变换（相对旋转/镜像/裁剪）叠在 buffer 朝向之后
            o.output.updateTransformMatrix(o.updated, st4);
            st3[0] = o.updated[0];
            st3[1] = o.updated[1];
            st3[2] = 0f;
            st3[3] = o.updated[4];
            st3[4] = o.updated[5];
            st3[5] = 0f;
            st3[6] = o.updated[12];
            st3[7] = o.updated[13];
            st3[8] = 1f;
            if (!EGL14.eglMakeCurrent(dpy, o.egl, o.egl, ctx)) {
                continue;
            }
            // 输出尺寸就是编码器要的尺寸，不做 cover 裁切：裁剪已经由上面的矩阵表达
            renderer.render(texId, true, st3, 1f / srcW, 1f / srcH, 0f, 0f,
                    o.w, o.h, smooth, focusX, focusY, timeMs, o.w, o.h, 0, o.w, o.h);
            EGLExt.eglPresentationTimeANDROID(dpy, o.egl, timestampNs);
            EGL14.eglSwapBuffers(dpy, o.egl);
        }
        frames++;
        if (frames == 1) {
            android.util.Log.d("FishEye", "video first frame src=" + srcW + "x" + srcH
                    + " rot=" + rotDeg + " outputs=" + outputs.size());
        }
    }

    // ------------------------------------------------------------------- EGL

    private boolean ensureGl() {
        if (glReady) {
            return true;
        }
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) {
            fail("录像侧 EGL 初始化失败");
            return false;
        }
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
            if (EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) && n[0] > 0) {
                cfg = cfgs[0];
                break;
            }
        }
        if (cfg == null) {
            fail("录像侧找不到可用的 EGL 配置");
            return false;
        }
        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            fail("录像侧 EGL 上下文创建失败（错误码 " + EGL14.eglGetError() + "）");
            return false;
        }
        int[] pb = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        pbuffer = EGL14.eglCreatePbufferSurface(dpy, cfg, pb, 0);
        if (pbuffer == EGL14.EGL_NO_SURFACE) {
            fail("录像侧离屏表面创建失败（错误码 " + EGL14.eglGetError() + "）");
            return false;
        }
        if (!EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)) {
            fail("录像侧 eglMakeCurrent 失败（错误码 " + EGL14.eglGetError() + ")");
            return false;
        }
        if (!renderer.init()) {
            fail("录像侧着色器编译失败：" + TensionRenderer.Program.lastError);
            return false;
        }
        glReady = true;
        return true;
    }

    private void makeCurrentPBuffer() {
        if (glReady) {
            EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx);
        }
    }

    private EGLSurface createWindowSurface(Surface surface) {
        makeCurrentPBuffer();
        EGLSurface egl = EGL14.eglCreateWindowSurface(dpy, cfg, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if (egl == EGL14.EGL_NO_SURFACE) {
            fail("录像侧窗口表面创建失败（错误码 " + EGL14.eglGetError() + "）");
        }
        return egl;
    }

    /** 停录/换镜头时调用：资源全部在 GL 线程上放掉 */
    void release() {
        handler.post(() -> {
            released = true;
            for (Out o : outputs) {
                if (o.egl != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(dpy, o.egl);
                }
                o.output.close();
            }
            outputs.clear();
            if (camSurface != null) {
                camSurface.release();
                camSurface = null;
            }
            if (camTex != null) {
                camTex.setOnFrameAvailableListener(null);
                camTex.release();
                camTex = null;
            }
            if (glReady) {
                makeCurrentPBuffer();
                renderer.destroy();
                if (texId != 0) {
                    GLES30.glDeleteTextures(1, new int[]{texId}, 0);
                    texId = 0;
                }
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (pbuffer != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(dpy, pbuffer);
                }
                if (ctx != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(dpy, ctx);
                }
                EGL14.eglTerminate(dpy);
                glReady = false;
            }
            thread.quitSafely();
        });
    }
}
