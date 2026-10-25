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

    static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
