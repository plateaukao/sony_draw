package com.maoyuankao.sonydraw;

import android.graphics.PointF;

import java.util.ArrayList;
import java.util.List;

/** A single pen-down → pen-up stroke. Equivalent of com.sony.capas.documentmanager.Stroke. */
public class Stroke {
    private final List<PointF> mPoints = new ArrayList<>();

    public void addPoint(float x, float y) { mPoints.add(new PointF(x, y)); }
    public List<PointF> getPoints() { return mPoints; }
    public int size() { return mPoints.size(); }
}
