package org.offblink.fisheye;

/**
 * 张力管线的 GLSL 源码。预览（相机外部纹理）与成片（Bitmap 转 2D 纹理）
 * 只差一个宏和一个采样器，其余逐字相同——这就是「取景即成品」的实现基础。
 *
 * <p>注意：{@code #extension} 必须紧跟 {@code #version}，出现在任何声明之前。
 * 少了这一行，模拟器的 SwiftShader 会睁一只眼闭一只眼，
 * 但真机（Mali / Adreno）会直接判定 {@code samplerExternalOES} 未声明而编译失败。
 */
final class Shaders {

    private Shaders() {
    }

    static final String VERTEX = "#version 300 es\n"
            + "in vec2 aPos;\n"
            + "out vec2 vUV;\n"
            + "void main() {\n"
            + "  vUV = aPos * 0.5 + 0.5;\n"
            + "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
            + "}\n";

    private static final String VERSION = "#version 300 es\n";

    /** 相机外部纹理（OES）必须声明的扩展；一行都不能少，且必须排在所有声明之前 */
    private static final String EXTERNAL_EXT =
            "#extension GL_OES_EGL_image_external_essl3 : require\n";

    private static final String HEAD = "precision highp float;\n"
            + "in vec2 vUV;\n"
            + "out vec4 oColor;\n"
            + "uniform vec2  uScale;\n"
            + "uniform float uRot;\n"
            + "uniform float uFlipY;\n"
            + "uniform float uAspect;\n"
            + "uniform float uFish;\n"
            + "uniform float uChroma;\n"
            + "uniform float uExposure;\n"
            + "uniform float uContrast;\n"
            + "uniform float uSaturation;\n"
            + "uniform float uLocal;\n"
            + "uniform vec2  uTexel;\n"
            + "const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);\n";

    private static final String SAMPLER_EXTERNAL =
            "uniform samplerExternalOES uTex;\n"
            + "vec3 sampleSrc(vec2 uv) { return texture(uTex, uv).rgb; }\n";

    private static final String SAMPLER_2D =
            "uniform sampler2D uTex;\n"
            + "vec3 sampleSrc(vec2 uv) { return texture(uTex, uv).rgb; }\n";

    /** 输出像素 → 源纹理坐标：竖翻（纹理约定）→ 旋转（方向）→ 缩放（cover 裁切） */
    private static final String BODY = ""
            + "vec2 mapUv(vec2 uv) {\n"
            + "  vec2 p = uv - 0.5;\n"
            + "  if (uFlipY > 0.5) p.y = -p.y;\n"
            + "  float c = cos(uRot), s = sin(uRot);\n"
            + "  p = vec2(c * p.x - s * p.y, s * p.x + c * p.y);\n"
            + "  p *= uScale;\n"
            + "  return p + 0.5;\n"
            + "}\n"
            // 等距鱼眼：输出半径 r → 源半径 tan(r·k)/tan(rmax·k)·rmax。
            // k→0 时退化成线性（直出），所以强度旋钮是连续的，不会跳变。
            + "vec2 fisheye(vec2 uv) {\n"
            + "  if (uFish < 0.001) return uv;\n"
            + "  vec2 p = (uv - 0.5) * vec2(uAspect, 1.0);\n"
            + "  float r = length(p);\n"
            + "  float rmax = length(vec2(0.5 * uAspect, 0.5));\n"
            + "  float k = mix(0.0015, 2.2, uFish);\n"
            + "  float rs = tan(min(r * k, 1.55)) / max(tan(rmax * k), 1e-4) * rmax;\n"
            + "  vec2 dir = r > 1e-5 ? p / r : vec2(0.0);\n"
            + "  p = dir * rs;\n"
            + "  return p / vec2(uAspect, 1.0) + 0.5;\n"
            + "}\n"
            // 色散：R/B 沿半径反向错开，像光穿过棱镜
            + "vec3 disperse(vec2 uv) {\n"
            + "  vec2 d = uv - 0.5;\n"
            + "  float c = uChroma * 0.012;\n"
            + "  return vec3(sampleSrc(0.5 + d * (1.0 + c)).r,\n"
            + "              sampleSrc(uv).g,\n"
            + "              sampleSrc(0.5 + d * (1.0 - c)).b);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec2 uv = fisheye(mapUv(vUV));\n"
            + "  vec3 col = disperse(uv);\n"
            + "  col *= uExposure;\n"
            + "  float l = dot(col, LUMA);\n"
            + "  vec3 nb = sampleSrc(uv + vec2(uTexel.x, 0.0) * 1.5).rgb\n"
            + "          + sampleSrc(uv - vec2(uTexel.x, 0.0) * 1.5).rgb\n"
            + "          + sampleSrc(uv + vec2(0.0, uTexel.y) * 1.5).rgb\n"
            + "          + sampleSrc(uv - vec2(0.0, uTexel.y) * 1.5).rgb;\n"
            + "  float lb = dot(nb * 0.25, LUMA);\n"
            + "  col += (l - lb) * uLocal * 1.6;\n"
            + "  col = clamp(col, 0.0, 1.0);\n"
            + "  col = mix(col, smoothstep(vec3(0.0), vec3(1.0), col), uContrast);\n"
            + "  float l2 = dot(col, LUMA);\n"
            + "  col = mix(vec3(l2), col, uSaturation);\n"
            + "  oColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n"
            + "}\n";

    static final String MAIN_EXTERNAL = VERSION + EXTERNAL_EXT + HEAD + SAMPLER_EXTERNAL + BODY;
    static final String MAIN_2D = VERSION + HEAD + SAMPLER_2D + BODY;

    /** 第二 pass：径向缩放模糊 + 暗角 + 颗粒（需要邻域采样，只能单独一趟） */
    static final String POST = VERSION
            + "precision highp float;\n"
            + "in vec2 vUV;\n"
            + "out vec4 oColor;\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2  uResolution;\n"
            + "uniform vec2  uFocus;\n"
            + "uniform float uBlur;\n"
            + "uniform float uVignette;\n"
            + "uniform float uGrain;\n"
            + "uniform float uTime;\n"
            + "float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
            + "void main() {\n"
            + "  vec3 col = texture(uTex, vUV).rgb;\n"
            + "  if (uBlur > 0.001) {\n"
            + "    const int N = 14;\n"
            + "    vec3 sum = vec3(0.0);\n"
            + "    float wsum = 0.0;\n"
            + "    for (int i = 0; i < N; i++) {\n"
            + "      float t = float(i) / float(N - 1);\n"
            + "      vec2 suv = uFocus + (vUV - uFocus) * (1.0 - 0.10 * t);\n"
            + "      float w = 1.0 - 0.65 * t;\n"
            + "      sum += texture(uTex, suv).rgb * w;\n"
            + "      wsum += w;\n"
            + "    }\n"
            + "    col = mix(col, sum / wsum, clamp(uBlur, 0.0, 1.0));\n"
            + "  }\n"
            + "  float d = length((vUV - 0.5) * vec2(uResolution.x / uResolution.y, 1.0));\n"
            + "  col *= 1.0 - uVignette * smoothstep(0.35, 1.05, d);\n"
            + "  col += (hash(vUV * uResolution + uTime) - 0.5) * uGrain;\n"
            + "  oColor = vec4(clamp(col, 0.0, 1.0), 1.0);\n"
            + "}\n";
}
