package com.maoyuankao.sonydraw;

import android.graphics.PointF;

import java.util.List;

/**
 * Eraser intersection tests, ported from
 * com.sony.apps.digitalpaperapp.utils.IntersectionsUtil +
 * StylusView.isCrossEraser.
 *
 * A stroke is considered "hit" by the current eraser step when any of:
 *   - one of its points lies inside the eraser circle (radius around cur);
 *   - one of its segments crosses the eraser circle;
 *   - one of its segments crosses the eraser's *own* move segment
 *     (prev → cur). This catches fast swipes where the circle never
 *     overlaps the stroke between samples.
 */
final class EraseMath {
    private EraseMath() {}

    static boolean strokeHit(Stroke s, PointF eraserPrev, PointF eraserCur, float radius) {
        List<PointF> pts = s.getPoints();
        if (pts.isEmpty()) return false;

        float r2 = radius * radius;
        PointF prev = pts.get(0);
        if (distSq(prev, eraserCur) <= r2) return true;

        boolean eraserMoved = eraserPrev != null && distSq(eraserPrev, eraserCur) > 0f;

        for (int i = 1; i < pts.size(); i++) {
            PointF cur = pts.get(i);
            if (distSq(cur, eraserCur) <= r2) return true;
            if (segmentIntersectsCircle(prev, cur, eraserCur, radius)) return true;
            if (eraserMoved && segmentsCross(prev, cur, eraserPrev, eraserCur)) return true;
            prev = cur;
        }
        return false;
    }

    private static float distSq(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static boolean segmentIntersectsCircle(PointF p1, PointF p2, PointF c, float r) {
        float dx = p2.x - p1.x, dy = p2.y - p1.y;
        float lenSq = dx * dx + dy * dy;
        if (lenSq == 0f) return distSq(p1, c) <= r * r;
        float t = ((c.x - p1.x) * dx + (c.y - p1.y) * dy) / lenSq;
        if (t < 0f) t = 0f; else if (t > 1f) t = 1f;
        float closestX = p1.x + t * dx;
        float closestY = p1.y + t * dy;
        float ddx = closestX - c.x, ddy = closestY - c.y;
        return ddx * ddx + ddy * ddy <= r * r;
    }

    private static boolean segmentsCross(PointF a, PointF b, PointF c, PointF d) {
        return ccw(a, c, d) != ccw(b, c, d) && ccw(a, b, c) != ccw(a, b, d);
    }

    private static boolean ccw(PointF a, PointF b, PointF c) {
        return (c.y - a.y) * (b.x - a.x) > (b.y - a.y) * (c.x - a.x);
    }
}
