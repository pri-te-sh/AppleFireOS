package com.applefireos.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The tvOS 27 glass platter under the top row: a frosted copy of the artwork behind it (a tiny
 * blurred copy, stretched), a soft white sheen and a lit top edge. Also used for frosted panels.
 */
final class DockView extends View {
    private final float radius;
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap blurred;
    /** Where this view sits on the stage, so the frost lines up with the picture behind it. */
    private float stageX, stageY;
    private float frostAlpha = 0.5f;

    DockView(Context c, float radius) {
        super(c);
        this.radius = radius;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f);
        base.setColor(0x14FFFFFF);
        Ui.rounded(this, radius);
    }

    /** The tint over the frost: light for the dock, darker for panels with text on them. */
    void setBase(int color) {
        base.setColor(color);
        invalidate();
    }

    void setFrostAlpha(float a) {
        frostAlpha = a;
        invalidate();
    }

    /** A small blurred copy of the whole stage behind the glass (null: nothing to show through). */
    void setBackdrop(Bitmap small) {
        blurred = small;
        frost.setShader(small == null ? null : new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        place(stageX, stageY);
    }

    void place(float x, float y) {
        stageX = x;
        stageY = y;
        if (blurred != null) {
            float k = Ui.STAGE_W / (float) blurred.getWidth();
            m.setScale(k, k);
            m.postTranslate(-x, -y);
            frost.getShader().setLocalMatrix(m);
        }
        invalidate();
    }

    /** Takes no room of its own: a glass panel sizes to its content, and this fills it. */
    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(0, w), resolveSize(0, h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        sheen.setShader(new LinearGradient(0, 0, 0, h, new int[] { 0x29FFFFFF, 0x0DFFFFFF, 0x17FFFFFF },
                new float[] { 0, 0.4f, 1 }, Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(0, 0, 0, h, new int[] { 0x80FFFFFF, 0x14FFFFFF, 0x1FFFFFFF },
                new float[] { 0, 0.5f, 1 }, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        if (blurred != null) {
            frost.setAlpha(Math.round(255 * frostAlpha));
            c.drawRoundRect(box, radius, radius, frost);
        }
        c.drawRoundRect(box, radius, radius, base);
        c.drawRoundRect(box, radius, radius, sheen);
        box.inset(0.75f, 0.75f);
        c.drawRoundRect(box, radius, radius, edge);
    }

    /** Shrinks and blurs a picture for frosted glass (run off the main thread). */
    static Bitmap frostOf(Bitmap slide) {
        Bitmap small = Bitmap.createScaledBitmap(slide, 160, 90, true);
        if (small == slide) small = small.copy(Bitmap.Config.ARGB_8888, true);
        return Ui.blur(small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true), 5);
    }
}
