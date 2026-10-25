package org.offblink.fisheye;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** 自绘的提示条：不用系统 Toast，颜色跟随当前界面明暗 */
final class Ui {

    private static TextView current;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Ui() {
    }

    static void toast(Context ctx, String msg) {
        if (!(ctx instanceof android.app.Activity)) {
            return;
        }
        final android.app.Activity act = (android.app.Activity) ctx;
        ViewGroup root = act.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        if (current != null && current.getParent() == root) {
            root.removeView(current);
        }
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextSize(13f);
        tv.setTextColor(Color.WHITE);
        tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6000000);
        bg.setCornerRadius(dp(ctx, 22));
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        lp.bottomMargin = dp(ctx, 210);
        root.addView(tv, lp);
        current = tv;
        tv.setAlpha(0f);
        tv.animate().alpha(1f).setDuration(120).start();
        MAIN.postDelayed(() -> {
            if (tv.getParent() != null) {
                tv.animate().alpha(0f).setDuration(180)
                        .withEndAction(() -> {
                            if (tv.getParent() != null) {
                                root.removeView(tv);
                            }
                        }).start();
            }
        }, 1400);
    }

    // ------------------------------------------------------------ 确认框（自绘）

    /** 透明窗 + 点外面可关 + 居中，宽幅留边 */
    private static android.app.Dialog baseDialog(android.app.Activity a) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        d.getWindow().setDimAmount(0.42f);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        android.view.Window w = d.getWindow();
        int side = dp(a, 26);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.CENTER);
        w.getDecorView().setPadding(side, 0, side, 0);
        return d;
    }

    /** 自绘确认框：标题 + 正文 + 取消/确定（确定用 okColor），点外面关掉 */
    static android.app.Dialog confirm(android.app.Activity a, String title, String msg,
                                      String okLabel, int okColor, Runnable onOk) {
        android.app.Dialog d = baseDialog(a);
        d.setContentView(R.layout.dialog_confirm);
        ((android.widget.TextView) d.findViewById(R.id.dc_title)).setText(title);
        ((android.widget.TextView) d.findViewById(R.id.dc_msg)).setText(msg);
        android.widget.TextView ok = d.findViewById(R.id.dc_ok);
        ok.setText(okLabel);
        ok.setTextColor(okColor);
        ok.setOnClickListener(v -> {
            d.dismiss();
            if (onOk != null) {
                onOk.run();
            }
        });
        d.findViewById(R.id.dc_cancel).setOnClickListener(v -> d.dismiss());
        d.show();
        return d;
    }

    // ------------------------------------------------------------ 缩放切页

    /** 进缩放切页前拍的旧页快照，直接当新页窗口背景（透明窗会渲染成黑底，所以不用） */
    private static android.graphics.Bitmap pageSnap;

    /**
     * 在 startActivity 之前调用：把当前页整帧截下来当新页背景。
     * 用 PixelCopy 从窗口 surface 取，WebView 这类 GPU 内容也截得到；
     * decor.draw 对它们是瞎的，会截成透明 → 黑底。
     */
    static void snapshot(android.app.Activity a, Runnable then) {
        View decor = a.getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            then.run();
            return;
        }
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
        // 先打底色：万一 PixelCopy 失败，露出的也是页面底色，绝不黑
        new android.graphics.Canvas(b).drawColor(0xFF000000);
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            android.view.PixelCopy.request(a.getWindow(), b, result -> {
                if (result != android.view.PixelCopy.SUCCESS) {
                    decor.draw(new android.graphics.Canvas(b));
                }
                pageSnap = b;
                main.post(then);
            }, main);
        } catch (RuntimeException e) {
            decor.draw(new android.graphics.Canvas(b));
            pageSnap = b;
            then.run();
        }
    }

    /** view 在屏幕上的矩形 [x, y, w, h]：缩放切页的源/目标位置 */
    static int[] rectOnScreen(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new int[]{loc[0], loc[1], v.getWidth(), v.getHeight()};
    }

    /**
     * 缩放进页：新页从 {@code rect}（屏幕坐标）放大到全屏。
     * 窗口背景 = 旧页快照，源位置之外看到的就是旧页本身。
     * 调用方要先 {@code overridePendingTransition(0,0)} 关掉默认平移。
     */
    static void zoomIn(android.app.Activity a, View root, int[] rect) {
        if (rect == null) {
            return;
        }
        if (pageSnap != null && !pageSnap.isRecycled()) {
            a.getWindow().setBackgroundDrawable(new android.graphics.drawable.BitmapDrawable(
                    a.getResources(), pageSnap));
        } else {
            a.getWindow().setBackgroundDrawableResource(R.color.bg);
        }
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        root.setPivotX(0f);
        root.setPivotY(0f);
        root.setScaleX(rect[2] / (float) dm.widthPixels);
        root.setScaleY(rect[3] / (float) dm.heightPixels);
        root.setTranslationX(rect[0]);
        root.setTranslationY(rect[1]);
        root.post(() -> root.animate()
                .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(280)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                        a, android.R.interpolator.fast_out_slow_in))
                .start());
    }

    /** 缩放返回：整页缩回 {@code rect} 再 finish（进页的反动作） */
    static void shrinkFinish(android.app.Activity a, View root, int[] rect) {
        android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
        root.animate()
                .scaleX(rect[2] / (float) dm.widthPixels)
                .scaleY(rect[3] / (float) dm.heightPixels)
                .translationX(rect[0]).translationY(rect[1])
                .setDuration(240)
                .setInterpolator(android.view.animation.AnimationUtils.loadInterpolator(
                        a, android.R.interpolator.fast_out_slow_in))
                .withEndAction(() -> {
                    a.finish();
                    a.overridePendingTransition(0, 0);
                })
                .start();
    }

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
