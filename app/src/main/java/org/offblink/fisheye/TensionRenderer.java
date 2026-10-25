package org.offblink.fisheye;

import android.opengl.GLES11Ext;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 张力管线的一份实例：程序、四边形、中间 FBO 都归它。
 *
 * <p>三条通路共用同一份实现与 {@link Shaders} 里的同一份 GLSL：
 * <ul>
 *   <li>取景：相机外部纹理 → 屏幕（{@link GlPreviewView}）</li>
 *   <li>录像：相机外部纹理 → 编码器 Surface（{@link VideoProcessor}）</li>
 *   <li>烘焙：Bitmap 的 2D 纹理 → 离线 FBO（{@link GlPreviewView#bake}）</li>
 * </ul>
 *
 * <p><b>必须是实例，不能是 static。</b>程序名、FBO 名都是「某个 EGL 上下文里的整数」：
 * 取景与录像各有一个上下文，共用一份 static 状态的话，后建的那个上下文会拿到前一个
 * 上下文的名字，glUseProgram 指向不存在的程序——画出来是纯黑，而且不报任何 GL 错误。
 *
 * <p>所有方法都必须在「已经 eglMakeCurrent 的那个 GL 线程」上调用。
 */
final class TensionRenderer {

    static final float[] IDENTITY3 = {1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f};

    private static final float[] QUAD = {
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    };

    private Program progExt;
    private Program prog2d;
    private Program progPost;
    private int vao;
    private int vbo;

    /** 第一趟的中间纹理：尺寸跟着输出走，换尺寸时重建 */
    private int fbo1 = -1;
    private int tex1 = -1;
    private int fbW, fbH;

    boolean ready() {
        return progPost != null;
    }

    /** 编译程序、建四边形缓冲。必须在 eglMakeCurrent 之后调用。失败返回 false，原因见 {@link Program#lastError} */
    boolean init() {
        if (ready()) {
            return true;
        }
        progExt = Program.build(Shaders.VERTEX, Shaders.MAIN_EXTERNAL);
        prog2d = Program.build(Shaders.VERTEX, Shaders.MAIN_2D);
        progPost = Program.build(Shaders.VERTEX, Shaders.POST);
        if (progExt == null || prog2d == null || progPost == null) {
            return false;
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
        return true;
    }

    void destroy() {
        if (progExt != null) {
            progExt.delete();
        }
        if (prog2d != null) {
            prog2d.delete();
        }
        if (progPost != null) {
            progPost.delete();
        }
        progExt = prog2d = progPost = null;
        if (fbo1 >= 0) {
            GLES30.glDeleteFramebuffers(1, new int[]{fbo1}, 0);
            GLES30.glDeleteTextures(1, new int[]{tex1}, 0);
        }
        fbo1 = -1;
        tex1 = -1;
    }

    /** 第一趟渲染出来的中间纹理（取景亮度采样会读它） */
    int internalTex() {
        return tex1;
    }

    /**
     * 一趟完整管线：主 shader 渲进内部 FBO，第二 pass 渲到 {@code targetFbo}。
     *
     * @param external 源是不是相机外部纹理
     * @param st3      采样坐标变换（相机：SurfaceTexture/相机管线给的矩阵；成片：单位阵）
     * @param rot      uRot（弧度），只给烘焙用；相机路径由 st3 表达朝向
     * @param flipY    uFlipY，只给烘焙用
     * @param srcAw    摆正后的源宽（算 cover 裁切用）；传输出尺寸即表示不裁
     */
    void render(int srcTex, boolean external, float[] st3,
                float texelX, float texelY, float rot, float flipY,
                float srcAw, float srcAh,
                Params p, float focusX, float focusY, float timeMs,
                int intW, int intH, int targetFbo, int outW, int outH) {
        ensureFbo(intW, intH);

        float targetAspect = outW / (float) Math.max(1, outH);
        float srcAspect = srcAw / (float) Math.max(1, srcAh);
        float sx = 1f, sy = 1f;
        if (srcAspect > targetAspect) {
            sx = targetAspect / srcAspect;
        } else {
            sy = srcAspect / targetAspect;
        }

        Program main = external ? progExt : prog2d;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo1);
        GLES30.glViewport(0, 0, intW, intH);
        GLES30.glUseProgram(main.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(external ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES30.GL_TEXTURE_2D,
                srcTex);
        GLES30.glUniform1i(main.loc("uTex"), 0);
        GLES30.glUniform2f(main.loc("uScale"), sx, sy);
        GLES30.glUniform1f(main.loc("uRot"), rot);
        GLES30.glUniform1f(main.loc("uFlipY"), flipY);
        GLES30.glUniform1f(main.loc("uAspect"), targetAspect);
        GLES30.glUniform1f(main.loc("uFish"), p.fisheye);
        GLES30.glUniform1f(main.loc("uChroma"), p.chroma);
        GLES30.glUniform1f(main.loc("uExposure"), p.exposure);
        GLES30.glUniform1f(main.loc("uContrast"), p.contrast);
        GLES30.glUniform1f(main.loc("uSaturation"), p.saturation);
        GLES30.glUniform1f(main.loc("uLocal"), p.local);
        GLES30.glUniform2f(main.loc("uTexel"), texelX, texelY);
        GLES30.glUniformMatrix3fv(main.loc("uSt"), 1, false, st3, 0);
        drawQuad(main);

        post(tex1, targetFbo, outW, outH, focusX, focusY, p, timeMs);
    }

    /** 第二 pass：径向缩放模糊 + 暗角 + 颗粒 */
    void post(int srcTex, int targetFbo, int outW, int outH,
              float focusX, float focusY, Params p, float timeMs) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glUseProgram(progPost.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glUniform1i(progPost.loc("uTex"), 0);
        GLES30.glUniform2f(progPost.loc("uResolution"), outW, outH);
        GLES30.glUniform2f(progPost.loc("uFocus"), focusX, focusY);
        GLES30.glUniform1f(progPost.loc("uBlur"), p.blur);
        GLES30.glUniform1f(progPost.loc("uVignette"), p.vignette);
        GLES30.glUniform1f(progPost.loc("uGrain"), p.grain);
        GLES30.glUniform1f(progPost.loc("uTime"), timeMs);
        drawQuad(progPost);
    }

    /** 只为了量亮度：同样的第二 pass，但把三种会动的东西全关掉 */
    void postLuma(int srcTex, int targetFbo, int outW, int outH) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo);
        GLES30.glViewport(0, 0, 16, 16);
        GLES30.glUseProgram(progPost.id);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex);
        GLES30.glUniform1i(progPost.loc("uTex"), 0);
        GLES30.glUniform2f(progPost.loc("uResolution"), outW, outH);
        GLES30.glUniform2f(progPost.loc("uFocus"), 0.5f, 0.5f);
        GLES30.glUniform1f(progPost.loc("uBlur"), 0f);
        GLES30.glUniform1f(progPost.loc("uVignette"), 0f);
        GLES30.glUniform1f(progPost.loc("uGrain"), 0f);
        GLES30.glUniform1f(progPost.loc("uTime"), 0f);
        drawQuad(progPost);
    }

    void ensureFbo(int w, int h) {
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

        void delete() {
            GLES30.glDeleteProgram(id);
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
