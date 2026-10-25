package org.offblink.fisheye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 自绘进度条：底轨 + 已播段 + 圆头。
 *
 * <p>拖动时每一帧都回调位置（0..1），界面据此边拖边把画面 seek 到对应帧——
 * 所以松手之前就能看到画面，而不是等抬手才跳。
 */
public class ScrubBar extends View {

    interface Listener {
        void onScrubStart();

        void onScrub(float fraction);

        void onScrubEnd(float fraction);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    /** 0..1 */
    private float fraction;
    private boolean dragging;
    private Listener listener;

    public ScrubBar(Context c) {
        this(c, null);
    }

    public ScrubBar(Context c, AttributeSet a) {
        super(c, a);
        density = c.getResources().getDisplayMetrics().density;
        trackPaint.setColor(0x40FFFFFF);
        playedPaint.setColor(c.getColor(R.color.accent));
        knobPaint.setColor(0xFFFFFFFF);
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setFraction(float f) {
        if (dragging) {
            // 拖动中以手指为准，别被播放进度拉回去
            return;
        }
        fraction = clamp(f);
        invalidate();
    }

    private static float clamp(float f) {
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    private float pad() {
        return 7f * density;
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = 3f * density;
        float cy = getHeight() / 2f;
        float left = pad();
        float right = getWidth() - pad();
        trackPaint.setStrokeWidth(h);
        c.drawLine(left, cy, right, cy, trackPaint);
        float x = left + (right - left) * fraction;
        playedPaint.setStrokeWidth(h);
        c.drawLine(left, cy, x, cy, playedPaint);
        c.drawCircle(x, cy, 5.5f * density, knobPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                dragging = true;
                if (listener != null) {
                    listener.onScrubStart();
                }
                update(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    update(e.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return true;
                }
                update(e.getX());
                dragging = false;
                if (listener != null) {
                    listener.onScrubEnd(fraction);
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void update(float x) {
        float left = pad();
        float right = getWidth() - pad();
        fraction = clamp((x - left) / Math.max(1f, right - left));
        invalidate();
        if (listener != null) {
            listener.onScrub(fraction);
        }
    }
}
