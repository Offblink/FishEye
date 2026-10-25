package org.offblink.fisheye;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * 自绘播放/暂停按钮：白底圆 + 深色三角或双竖条。
 * 不用系统 ImageButton/图标，颜色与形状都跟着这一页的暗底走。
 */
public class PlayPauseView extends View {

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean playing;

    public PlayPauseView(Context c) {
        this(c, null);
    }

    public PlayPauseView(Context c, AttributeSet a) {
        super(c, a);
        disc.setColor(0xE6FFFFFF);
        glyph.setColor(0xFF0B0C0E);
        glyph.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    void setPlaying(boolean on) {
        if (playing == on) {
            return;
        }
        playing = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float r = Math.min(w, h) / 2f;
        c.drawCircle(w / 2f, h / 2f, r, disc);
        path.reset();
        if (playing) {
            // 暂停：两根竖条
            float bw = r * 0.22f;
            float bh = r * 0.72f;
            float gap = r * 0.26f;
            float cx = w / 2f;
            float cy = h / 2f;
            path.addRect(cx - gap - bw / 2, cy - bh / 2, cx - gap + bw / 2, cy + bh / 2,
                    Path.Direction.CW);
            path.addRect(cx + gap - bw / 2, cy - bh / 2, cx + gap + bw / 2, cy + bh / 2,
                    Path.Direction.CW);
        } else {
            // 播放：三角，重心略微右移才显得居中
            float s = r * 0.78f;
            float cx = w / 2f + r * 0.06f;
            float cy = h / 2f;
            path.moveTo(cx - s * 0.42f, cy - s * 0.52f);
            path.lineTo(cx + s * 0.52f, cy);
            path.lineTo(cx - s * 0.42f, cy + s * 0.52f);
            path.close();
        }
        c.drawPath(path, glyph);
    }
}
