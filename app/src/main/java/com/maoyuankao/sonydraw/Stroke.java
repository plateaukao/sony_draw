package com.maoyuankao.sonydraw;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** A single pen-down → pen-up stroke. Equivalent of com.sony.capas.documentmanager.Stroke. */
public class Stroke {
    private final List<PointF> mPoints = new ArrayList<>();
    private final RectF mBounds = new RectF();

    public void addPoint(float x, float y) {
        mPoints.add(new PointF(x, y));
        if (mPoints.size() == 1) {
            mBounds.set(x, y, x, y);
        } else {
            mBounds.union(x, y);
        }
    }

    public List<PointF> getPoints() { return mPoints; }
    public int size() { return mPoints.size(); }
    public RectF getBounds() { return mBounds; }
}
