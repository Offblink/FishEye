package org.offblink.fisheye;

/**
 * 张力管线的调色参数。数值全部是「已换算好」的物理量，
 * 不保留 UI 的 0–100 百分比（换算在 {@link #fromUi} 里做一次）。
 *
 * <p>预览与成片共用这一份结构体与同一套 shader，所以取景看到什么、存下来就是什么。
 */
public final class Params {

    /** 0 = 直出，1 = 最强（内部映射到 tan 曲线上的 k） */
    public float fisheye = 0.55f;
    /** 色散强度，0–3 */
    public float chroma = 0.7f;
    /** S 曲线对比，0–1 */
    public float contrast = 0.45f;
    /** 局部对比（luma 十字锐化），0–1 */
    public float local = 0.40f;
    /** 径向缩放模糊，0–1 */
    public float blur = 0.15f;
    /** 暗角，0–1 */
    public float vignette = 0.30f;

    // 不在控件里的固定风格量：饱和度略提、颗粒压一点点，成片才不像数码直出
    public float saturation = 1.06f;
    public float exposure = 1.0f;
    public float grain = 0.030f;

    public void copyFrom(Params o) {
        fisheye = o.fisheye;
        chroma = o.chroma;
        contrast = o.contrast;
        local = o.local;
        blur = o.blur;
        vignette = o.vignette;
        saturation = o.saturation;
        exposure = o.exposure;
        grain = o.grain;
    }

    /** 每帧向目标靠一点：滑块拖过去是「滑」过去的，直方图与模糊中心都不会跳 */
    public void approach(Params target, float alpha) {
        fisheye += (target.fisheye - fisheye) * alpha;
        chroma += (target.chroma - chroma) * alpha;
        contrast += (target.contrast - contrast) * alpha;
        local += (target.local - local) * alpha;
        blur += (target.blur - blur) * alpha;
        vignette += (target.vignette - vignette) * alpha;
        saturation += (target.saturation - saturation) * alpha;
        exposure += (target.exposure - exposure) * alpha;
        grain += (target.grain - grain) * alpha;
    }

    /** UI 的 0–100 百分比 → 各参数的实际量程 */
    public static float map(String key, float pct) {
        float v = Math.max(0f, Math.min(100f, pct)) / 100f;
        switch (key) {
            case "fisheye":
                return v;
            case "chroma":
                return v * 3f;
            case "contrast":
                return v;
            case "local":
                return v;
            case "blur":
                return v;
            case "vignette":
                return v;
            default:
                return v;
        }
    }

    public void set(String key, float pct) {
        float v = map(key, pct);
        switch (key) {
            case "fisheye": fisheye = v; break;
            case "chroma": chroma = v; break;
            case "contrast": contrast = v; break;
            case "local": local = v; break;
            case "blur": blur = v; break;
            case "vignette": vignette = v; break;
            default: break;
        }
    }

    /** 三套预设，对应「越推越失控」的三档 */
    public void preset(String name) {
        fisheye = 0.55f; chroma = 0.7f; contrast = 0.45f; local = 0.40f; blur = 0.15f; vignette = 0.30f;
        saturation = 1.06f; exposure = 1.0f; grain = 0.030f;
        if ("wide".equals(name)) {
            fisheye = 0.60f; chroma = 0.45f; contrast = 0.45f; local = 0.40f; blur = 0.05f; vignette = 0.25f;
        } else if ("clash".equals(name)) {
            fisheye = 0.30f; chroma = 1.60f; contrast = 0.70f; local = 0.60f; blur = 0.22f; vignette = 0.50f;
            saturation = 1.16f; exposure = 0.98f; grain = 0.045f;
        } else if ("runaway".equals(name)) {
            fisheye = 0.82f; chroma = 2.50f; contrast = 0.85f; local = 0.75f; blur = 0.62f; vignette = 0.70f;
            saturation = 1.28f; exposure = 0.94f; grain = 0.060f;
        }
    }
}
