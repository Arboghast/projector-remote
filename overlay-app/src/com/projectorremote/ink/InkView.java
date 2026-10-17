package com.projectorremote.ink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transparent layer that draws strokes over whatever app is on screen; it never takes touches.
 * Mirrors the phone's drawing (remote-web/src/dark/inkShapes.ts): freehand pen and eraser, and
 * two-point shapes (arrow, rect, ellipse) whose corners are replaced as the finger moves.
 */
final class InkView extends View {
    private static final float HEAD_PER_WIDTH = 4f;
    private static final float HEAD_MIN = 0.03f;
    private static final double HEAD_ANGLE = Math.PI / 7;

    private static final class Stroke {
        final int color;
        final float size; // fraction of screen height
        final String tool;
        final List<float[]> points = new ArrayList<>();

        Stroke(int color, float size, String tool) {
            this.color = color;
            this.size = size;
            this.tool = tool;
        }

        boolean isShape() {
            return tool.equals("arrow") || tool.equals("rect") || tool.equals("ellipse");
        }
    }

    private final Map<String, Stroke> strokes = new LinkedHashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode clear = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
    private final Path path = new Path();
    private final RectF box = new RectF();

    InkView(Context context) {
        super(context);
        // The eraser clears pixels; that only stays within our own layer with one of its own.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void add(String id, String color, float size, String tool, List<float[]> points) {
        Stroke s = strokes.get(id);
        if (s == null) {
            s = new Stroke(parseColor(color), size, tool);
            strokes.put(id, s);
        }
        if (s.isShape()) s.points.clear();
        s.points.addAll(points);
        invalidate();
    }

    void undo() {
        String last = null;
        for (String id : strokes.keySet()) last = id;
        if (last != null) strokes.remove(last);
        invalidate();
    }

    void clear() {
        strokes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        for (Stroke s : strokes.values()) {
            if (s.points.isEmpty()) continue;
            paint.setColor(s.color);
            paint.setXfermode(s.tool.equals("erase") ? clear : null);
            paint.setStrokeWidth(Math.max(2f, s.size * h));
            if (s.isShape()) {
                if (s.points.size() >= 2) drawShape(canvas, s, w, h);
            } else {
                drawFreehand(canvas, s.points, w, h);
            }
        }
        paint.setXfermode(null);
    }

    private void drawFreehand(Canvas canvas, List<float[]> p, float w, float h) {
        path.reset();
        path.moveTo(p.get(0)[0] * w, p.get(0)[1] * h);
        if (p.size() == 1) {
            path.lineTo(p.get(0)[0] * w + 0.1f, p.get(0)[1] * h);
        }
        // Curve through midpoints so the line flows instead of zig-zagging between samples.
        for (int i = 1; i < p.size() - 1; i++) {
            float mx = (p.get(i)[0] + p.get(i + 1)[0]) / 2 * w;
            float my = (p.get(i)[1] + p.get(i + 1)[1]) / 2 * h;
            path.quadTo(p.get(i)[0] * w, p.get(i)[1] * h, mx, my);
        }
        if (p.size() > 1) {
            float[] last = p.get(p.size() - 1);
            path.lineTo(last[0] * w, last[1] * h);
        }
        canvas.drawPath(path, paint);
    }

    private void drawShape(Canvas canvas, Stroke s, float w, float h) {
        float ax = s.points.get(0)[0] * w, ay = s.points.get(0)[1] * h;
        float bx = s.points.get(1)[0] * w, by = s.points.get(1)[1] * h;
        box.set(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        switch (s.tool) {
            case "rect":
                canvas.drawRect(box, paint);
                break;
            case "ellipse":
                canvas.drawOval(box, paint);
                break;
            default: {
                double angle = Math.atan2(by - ay, bx - ax);
                float head = Math.max(paint.getStrokeWidth() * HEAD_PER_WIDTH, HEAD_MIN * h);
                path.reset();
                path.moveTo(ax, ay);
                path.lineTo(bx, by);
                path.moveTo((float) (bx - head * Math.cos(angle - HEAD_ANGLE)), (float) (by - head * Math.sin(angle - HEAD_ANGLE)));
                path.lineTo(bx, by);
                path.lineTo((float) (bx - head * Math.cos(angle + HEAD_ANGLE)), (float) (by - head * Math.sin(angle + HEAD_ANGLE)));
                canvas.drawPath(path, paint);
            }
        }
    }

    private static int parseColor(String c) {
        try {
            return Color.parseColor(c);
        } catch (IllegalArgumentException e) {
            return Color.YELLOW;
        }
    }
}
