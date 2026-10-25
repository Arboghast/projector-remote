package com.projectorremote.ink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Shows Claude at work on the wall: an orange frame glowing all the way round while it's in
 * control, swelling each time it acts; a cursor that glides to whatever it's about to touch, with a
 * ripple where it taps; an address bar that types out a link before the page opens; a sweep when it
 * scrolls; and a caption for what it's doing. Drawn from the clock, and redrawn only while
 * something moves: a steady glow costs nothing over a playing video.
 */
final class AgentView extends View {
    private static final int ORANGE = Color.rgb(0xFF, 0x8A, 0x4C);
    private static final float BAND = 0.04f; // of the screen height
    private static final float CORNER = 0.05f; // of the screen height
    private static final int GLOW_RINGS = 28;
    private static final float GLOW_REST = 0.6f;
    private static final long FADE_MS = 450;
    private static final long PULSE_MS = 700;
    private static final long GLIDE_MS = 380;
    private static final long RIPPLE_MS = 550;
    private static final long CURSOR_LINGER_MS = 900;
    private static final long CAPTION_MS = 1600;
    // Matches AgentTools.URL_CHAR_MS and URL_TYPE_MAX_MS in the relay, which waits for the typing to finish.
    private static final long URL_CHAR_MS = 30;
    private static final long URL_TYPE_MAX_MS = 1200;
    private static final long URL_HOLD_MS = 1800;
    private static final long URL_FADE_MS = 300;
    private static final long SWEEP_MS = 650;

    private final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path chevron = new Path();
    private final float[] at = new float[2];
    private Bitmap frame;

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

    private String url = "";
    private long urlAt = Long.MIN_VALUE / 2;

    private String sweep = "down";
    private long sweepAt = -SWEEP_MS;

    /** Called once the fade-out has finished, so the window can be taken away. */
    Runnable onHidden = () -> { };

    AgentView(Context context) {
        super(context);
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

    /** An address bar drops in and types the link out, as a person would before the page opens. */
    void url(String link) {
        url = link;
        urlAt = now();
        pulse();
    }

    /** Chevrons sweep the way the page is moving. */
    void scroll(String direction) {
        sweep = direction;
        sweepAt = now();
        pulse();
    }

    /** Clears the cursor and captions at once, so a screenshot shows the wall rather than us. */
    void quiet() {
        cursorUntil = 0;
        captionAt = -CAPTION_MS;
        urlAt = Long.MIN_VALUE / 2;
        sweepAt = -SWEEP_MS;
        invalidate();
    }

    private void pulse() {
        pulsedAt = now();
        invalidate();
    }

    /**
     * The glow is one rounded frame, drawn once as rings that fade inward and then only faded as a
     * whole: continuous all the way round, and nothing to redraw while it rests.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (frame != null) frame.recycle();
        if (w == 0 || h == 0) return;
        frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(frame);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        float band = h * BAND;
        float step = band / GLOW_RINGS;
        ring.setStrokeWidth(step * 1.6f);
        for (int i = 0; i < GLOW_RINGS; i++) {
            float inset = step * (i + 0.5f);
            float fall = 1 - i / (float) GLOW_RINGS;
            ring.setColor(withAlpha(ORANGE, fall * fall * 0.55f));
            float r = Math.max(h * CORNER - inset * 0.5f, step);
            c.drawRoundRect(inset, inset, w - inset, h - inset, r, r, ring);
        }
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
        moving |= drawSweep(canvas, t, shown);
        moving |= drawRipple(canvas, t, shown);
        moving |= drawCursor(canvas, t, shown);
        moving |= drawUrl(canvas, t, shown);
        moving |= drawCaption(canvas, t, shown);
        if (moving) postInvalidateOnAnimation();
    }

    // Each draw returns whether it's still changing, so a still frame stops the redraws.

    private boolean drawGlow(Canvas canvas, long t, float shown) {
        if (frame == null) return false;
        float p = since(pulsedAt, t, PULSE_MS);
        glow.setAlpha((int) (255 * shown * Math.min(1f, GLOW_REST + 0.4f * (1 - p))));
        canvas.drawBitmap(frame, 0, 0, glow);
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

    private boolean drawSweep(Canvas canvas, long t, float shown) {
        float p = since(sweepAt, t, SWEEP_MS);
        if (p >= 1) return false;
        boolean vertical = sweep.equals("up") || sweep.equals("down");
        int sign = sweep.equals("down") || sweep.equals("right") ? 1 : -1;
        float w = getWidth(), h = getHeight(), size = h * 0.03f;
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(size * 0.35f);
        pen.setStrokeCap(Paint.Cap.ROUND);
        pen.setStrokeJoin(Paint.Join.ROUND);
        // Three chevrons, each lit in turn as the sweep passes, along the edge the page moves past.
        for (int i = 0; i < 3; i++) {
            float lit = 1 - Math.min(1f, Math.abs(p * 3 - i - 0.5f));
            float d = (i - 1) * size * 1.6f * sign;
            float cx = vertical ? w - h * 0.08f : w / 2f + d;
            float cy = vertical ? h / 2f + d : h - h * 0.1f;
            chevron.reset();
            if (vertical) {
                chevron.moveTo(cx - size, cy - size * 0.5f * sign);
                chevron.lineTo(cx, cy + size * 0.5f * sign);
                chevron.lineTo(cx + size, cy - size * 0.5f * sign);
            } else {
                chevron.moveTo(cx - size * 0.5f * sign, cy - size);
                chevron.lineTo(cx + size * 0.5f * sign, cy);
                chevron.lineTo(cx - size * 0.5f * sign, cy + size);
            }
            pen.setColor(withAlpha(ORANGE, shown * (0.25f + 0.75f * lit) * (1 - p * 0.5f)));
            canvas.drawPath(chevron, pen);
        }
        pen.setStrokeCap(Paint.Cap.BUTT);
        return true;
    }

    private boolean drawUrl(Canvas canvas, long t, float shown) {
        long typeMs = Math.min(url.length() * URL_CHAR_MS, URL_TYPE_MAX_MS);
        long age = t - urlAt;
        long life = typeMs + URL_HOLD_MS + URL_FADE_MS;
        if (age < 0 || age >= life || url.isEmpty()) return false;
        float fade = shown * Math.min(1f, age / 150f) * (age > life - URL_FADE_MS ? (life - age) / (float) URL_FADE_MS : 1);
        int typed = age >= typeMs ? url.length() : (int) (url.length() * age / (float) typeMs);
        float h = getHeight(), w = getWidth();
        float size = h * 0.032f;
        box.set(w * 0.2f, h * 0.06f, w * 0.8f, h * 0.06f + size * 2.4f);
        pen.setStyle(Paint.Style.FILL);
        pen.setColor(Color.argb((int) (240 * fade), 250, 250, 252));
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, pen);
        pen.setStyle(Paint.Style.STROKE);
        pen.setStrokeWidth(h * 0.004f);
        pen.setColor(withAlpha(ORANGE, fade));
        canvas.drawRoundRect(box, box.height() / 2, box.height() / 2, pen);
        // The end of a long link is what's being typed, so it's the end that stays in view.
        pen.setStyle(Paint.Style.FILL);
        pen.setTextSize(size);
        pen.setTextAlign(Paint.Align.LEFT);
        pen.setColor(Color.argb((int) (255 * fade), 30, 30, 34));
        String text = url.substring(0, typed);
        float room = box.width() - size * 2.2f;
        while (text.length() > 1 && pen.measureText(text) > room) text = text.substring(1);
        float x = box.left + size * 1.1f, y = box.centerY() + size * 0.35f;
        canvas.drawText(text, x, y, pen);
        if (age < typeMs || (age / 450) % 2 == 0) {
            float caret = x + pen.measureText(text) + size * 0.15f;
            pen.setColor(withAlpha(ORANGE, fade));
            canvas.drawRect(caret, y - size * 0.85f, caret + size * 0.1f, y + size * 0.2f, pen);
        }
        return true;
    }

    private boolean drawCaption(Canvas canvas, long t, float shown) {
        float p = since(captionAt, t, CAPTION_MS);
        if (p >= 1 || caption.isEmpty()) return false;
        float fade = shown * (p > 0.8f ? (1 - p) / 0.2f : 1);
        float size = getHeight() * 0.04f;
        pen.setTextSize(size);
        pen.setTextAlign(Paint.Align.CENTER);
        pen.setStyle(Paint.Style.FILL);
        float textW = pen.measureText(caption);
        float cx = getWidth() / 2f, cy = getHeight() * 0.88f;
        box.set(cx - textW / 2 - size, cy - size * 1.15f, cx + textW / 2 + size, cy + size * 0.6f);
        pen.setColor(Color.argb((int) (235 * fade), 16, 16, 18));
        canvas.drawRoundRect(box, size, size, pen);
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
