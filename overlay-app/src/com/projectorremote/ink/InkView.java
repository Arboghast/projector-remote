package com.projectorremote.ink;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transparent layer that draws strokes over whatever app is on screen; it never takes touches. */
final class InkView extends View {
    private static final class Stroke {
        final int color;
        final float size; // fraction of screen height
        final List<float[]> points = new ArrayList<>();

        Stroke(int color, float size) {
            this.color = color;
            this.size = size;
        }
    }

    private final Map<String, Stroke> strokes = new LinkedHashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    InkView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void add(String id, String color, float size, List<float[]> points) {
        Stroke s = strokes.get(id);
        if (s == null) {
            s = new Stroke(parseColor(color), size);
            strokes.put(id, s);
        }
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
            paint.setColor(s.color);
            paint.setStrokeWidth(Math.max(2f, s.size * h));
            List<float[]> p = s.points;
            if (p.isEmpty()) continue;
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
    }

    private static int parseColor(String c) {
        try {
            return Color.parseColor(c);
        } catch (IllegalArgumentException e) {
            return Color.YELLOW;
        }
    }
}
