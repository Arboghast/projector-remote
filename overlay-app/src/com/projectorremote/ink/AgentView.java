package com.projectorremote.ink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Shows Claude at work on the wall: an orange glow around the edges while it's in control that
 * swells each time it acts, a cursor that glides to whatever it's about to touch, a ripple where it
 * taps, and a caption for what it's doing. Drawn from the clock, and redrawn only while something
 * moves: a steady glow costs nothing over a playing video.
 */
final class AgentView extends View {
    private static final int ORANGE = Color.rgb(0xFF, 0x8A, 0x4C);
    private static final float BAND = 0.035f; // of the screen height
    private static final float GLOW_REST = 0.6f;
    private static final long FADE_MS = 450;
    private static final long PULSE_MS = 700;
    private static final long GLIDE_MS = 380;
    private static final long RIPPLE_MS = 550;
    private static final long CURSOR_LINGER_MS = 900;
    private static final long CAPTION_MS = 1600;

    private final Paint glow = new Paint();
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final float[] at = new float[2];
    private LinearGradient[] edges;

    private boolean on;
    private long switchedAt = -FADE_MS;
    private long pulsedAt = -PULSE_MS;

    // Positions are fractions of the screen: a point can arrive before the view is laid out.
    private float fromX, fromY, toX, toY;
    private long glideAt = -GLIDE_MS;
    private long cursorUntil;
    private float rippleX, rippleY;
    private long rippleAt = -RIPPLE_MS;

    private String caption = "";
    private long captionAt = -CAPTION_MS;

    /** Called once the fade-out has finished, so the window can be taken away. */
    Runnable onHidden = () -> { };

    AgentView(Context context) {
        super(context);
        pen.setTextAlign(Paint.Align.CENTER);
        pen.setTypeface(Typeface.DEFAULT_BOLD);
    }

    boolean isOn() {
        return on;
    }

    void show(boolean show) {
        if (show == on) return;
        long t = now();
        // Turning around mid-fade carries on from the current level instead of jumping.
        float level = since(switchedAt, t, FADE_MS);
        switchedAt = t - (long) ((1 - level) * FADE_MS);
        on = show;
        if (!show) cursorUntil = 0;
        invalidate();
    }

    /** The cursor glides from where it was to (x, y). */
    void point(float x, float y) {
        long t = now();
        boolean shown = t < cursorUntil;
        cursorAt(t);
        fromX = shown ? at[0] : x;
        fromY = shown ? at[1] : y;
        toX = x;
        toY = y;
        glideAt = t;
        cursorUntil = t + GLIDE_MS + CURSOR_LINGER_MS;
        pulse();
    }

    void tap(float x, float y) {
        rippleX = x;
        rippleY = y;
        rippleAt = now();
        cursorUntil = rippleAt + CURSOR_LINGER_MS;
        pulse();
    }

    void say(String text) {
        caption = text;
        captionAt = now();
        pulse();
    }

    /** Clears the cursor and caption at once, so a screenshot shows the wall rather than us. */
    void quiet() {
        cursorUntil = 0;
        captionAt = -CAPTION_MS;
        invalidate();
    }

    private void pulse() {
        pulsedAt = now();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float band = h * BAND;
        int clear = Color.argb(0, Color.red(ORANGE), Color.green(ORANGE), Color.blue(ORANGE));
        edges = new LinearGradient[]{
                new LinearGradient(0, 0, 0, band, ORANGE, clear, Shader.TileMode.CLAMP),
                new LinearGradient(0, h, 0, h - band, ORANGE, clear, Shader.TileMode.CLAMP),
                new LinearGradient(0, 0, band, 0, ORANGE, clear, Shader.TileMode.CLAMP),
                new LinearGradient(w, 0, w - band, 0, ORANGE, clear, Shader.TileMode.CLAMP),
        };
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long t = now();
        float level = since(switchedAt, t, FADE_MS);
        float shown = on ? ease(level) : 1 - ease(level);
        if (shown <= 0) {
            onHidden.run();
            return;
        }
        boolean moving = level < 1;
        moving |= drawGlow(canvas, t, shown);
        moving |= drawRipple(canvas, t, shown);
        moving |= drawCursor(canvas, t, shown);
        moving |= drawCaption(canvas, t, shown);
        if (moving) postInvalidateOnAnimation();
    }

    // Each draw returns whether it's still changing, so a still frame stops the redraws.

    private boolean drawGlow(Canvas canvas, long t, float shown) {
        if (edges == null) return false;
        float p = since(pulsedAt, t, PULSE_MS);
        glow.setAlpha((int) (255 * shown * Math.min(1f, GLOW_REST + 0.4f * (1 - p))));
        int w = getWidth(), h = getHeight();
        float band = h * BAND;
        glow.setShader(edges[0]); canvas.drawRect(0, 0, w, band, glow);
        glow.setShader(edges[1]); canvas.drawRect(0, h - band, w, h, glow);
        // The sides stop short of the corners, which the top and bottom already cover.
        glow.setShader(edges[2]); canvas.drawRect(0, band, band, h - band, glow);
        glow.setShader(edges[3]); canvas.drawRect(w - band, band, w, h - band, glow);
        return p < 1;
    }

    private boolean drawCursor(Canvas canvas, long t, float shown) {
        if (t >= cursorUntil) return false;
        cursorAt(t);
        float x = at[0] * getWidth(), y = at[1] * getHeight();
        float r = getHeight() * 0.022f;
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(Color.argb((int) (90 * shown), 0, 0, 0));
        canvas.drawCircle(x, y + r * 0.25f, r * 1.35f, pen);
        pen.setColor(withAlpha(ORANGE, shown));
        canvas.drawCircle(x, y, r, pen);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(r * 0.3f);
        pen.setColor(withAlpha(Color.WHITE, shown));
        canvas.drawCircle(x, y, r, pen);
        return true;
    }

    private boolean drawRipple(Canvas canvas, long t, float shown) {
        float p = since(rippleAt, t, RIPPLE_MS);
        if (p >= 1) return false;
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(getHeight() * 0.007f);
        pen.setColor(withAlpha(ORANGE, shown * (1 - p)));
        canvas.drawCircle(rippleX * getWidth(), rippleY * getHeight(), getHeight() * (0.02f + 0.06f * ease(p)), pen);
        return true;
    }

    private boolean drawCaption(Canvas canvas, long t, float shown) {
        float p = since(captionAt, t, CAPTION_MS);
        if (p >= 1 || caption.isEmpty()) return false;
        float fade = shown * (p > 0.8f ? (1 - p) / 0.2f : 1);
        float size = getHeight() * 0.04f;
        pen.setTextSize(size);
        pen.setStyle(Paint.Style.FILL);
        float textW = pen.measureText(caption);
        float cx = getWidth() / 2f, cy = getHeight() * 0.88f;
        pill.set(cx - textW / 2 - size, cy - size * 1.15f, cx + textW / 2 + size, cy + size * 0.6f);
        pen.setColor(Color.argb((int) (235 * fade), 16, 16, 18));
        canvas.drawRoundRect(pill, size, size, pen);
        pen.setColor(withAlpha(Color.WHITE, fade));
        canvas.drawText(caption, cx, cy, pen);
        return true;
    }

    private void cursorAt(long t) {
        float p = ease(since(glideAt, t, GLIDE_MS));
        at[0] = fromX + (toX - fromX) * p;
        at[1] = fromY + (toY - fromY) * p;
    }

    private static float since(long start, long t, long duration) {
        return Math.min(1f, Math.max(0f, (t - start) / (float) duration));
    }

    private static float ease(float p) {
        return 1 - (1 - p) * (1 - p) * (1 - p);
    }

    private static int withAlpha(int color, float alpha) {
        return Color.argb((int) (255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
