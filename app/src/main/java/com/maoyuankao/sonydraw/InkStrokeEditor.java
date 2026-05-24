package com.maoyuankao.sonydraw;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a MotionEvent stream into a Stroke and rasterises each segment
 * to a Canvas as a tapered quad-with-circle (so adjacent dabs join cleanly).
 *
 * The polygon construction is the same one DigitalPaperApp's
 * InkStrokeEditor.drawPath uses: a quadrilateral whose half-widths are the
 * two endpoint radii, capped at each end with a filled circle. This avoids
 * the gaps you'd get from drawLine() when the pen moves faster than the
 * stroke is wide.
 */
public class InkStrokeEditor implements StrokeDetector.OnStrokeListener {
    private static final float MIN_STROKE_WIDTH = 1.0f;

    private final StrokeDetector mDetector = new StrokeDetector(this);
    private final Paint mPaint = new Paint();
    private final Path mPath = new Path();
    private final RectF mDirty = new RectF();
    private final List<Stroke> mFinished = new ArrayList<>();

    private Canvas mCanvas;
    private Rect mInvalidate;
    private Stroke mCurrent;
    private PointF mPrev;
    private float mStrokeWidth = MIN_STROKE_WIDTH;
    private float mPrevStrokeWidth = MIN_STROKE_WIDTH;
    private float mPixelWidth = 4f;   // pen thickness in screen pixels

    public InkStrokeEditor() {
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(0xFF000000);
        mPaint.setAntiAlias(false);   // EPD DU mode is 1-bit; AA is wasted
        mPaint.setDither(false);
    }

    public void setPixelWidth(float w) { mPixelWidth = Math.max(MIN_STROKE_WIDTH, w); }
    public float getPixelWidth() { return mPixelWidth; }

    /** @return finished strokes since the previous call (typically one on ACTION_UP). */
    public List<Stroke> addStroke(MotionEvent e, Canvas canvas, Rect invalidate) {
        mCanvas = canvas;
        mInvalidate = invalidate;
        mFinished.clear();
        mDetector.onTouchEvent(e);
        return mFinished;
    }

    @Override public void onStrokeBegin(PointF p, float pressure, long t) {
        mCurrent = new Stroke();
        mStrokeWidth = mPixelWidth;
        mPrevStrokeWidth = mStrokeWidth;
        drawSegment(p, p);
        mCurrent.addPoint(p.x, p.y);
        mPrev = p;
    }

    @Override public void onStrokeMove(PointF p, float pressure, long t) {
        mStrokeWidth = mPixelWidth;
        drawSegment(mPrev, p);
        mCurrent.addPoint(p.x, p.y);
        mPrev = p;
        mPrevStrokeWidth = mStrokeWidth;
    }

    @Override public void onStrokeEnd(PointF p, float pressure, long t) {
        mStrokeWidth = mPixelWidth;
        drawSegment(mPrev, p);
        mCurrent.addPoint(p.x, p.y);
        mFinished.add(mCurrent);
        mCurrent = null;
        mPrev = null;
        mPrevStrokeWidth = MIN_STROKE_WIDTH;
    }

    private void drawSegment(PointF start, PointF end) {
        float r1 = mPrevStrokeWidth / 2f;
        float r2 = mStrokeWidth / 2f;
        float radius = (float) Math.ceil(Math.max(r1, r2));

        mDirty.set(start.x, start.y, start.x, start.y);
        mDirty.union(end.x, end.y);
        mDirty.inset(-radius, -radius);
        mInvalidate.union((int) Math.floor(mDirty.left),  (int) Math.floor(mDirty.top),
                          (int) Math.ceil (mDirty.right), (int) Math.ceil (mDirty.bottom));

        mCanvas.save();
        mCanvas.clipRect(mDirty, Region.Op.REPLACE);
        if (start.x == end.x && start.y == end.y) {
            mPath.addCircle(start.x, start.y, r2, Path.Direction.CW);
        } else {
            float len = PointF.length(start.x - end.x, start.y - end.y);
            float dx1 = ((end.x - start.x) * r1) / len;
            float dy1 = ((end.y - start.y) * r1) / len;
            float dx2 = ((end.x - start.x) * r2) / len;
            float dy2 = ((end.y - start.y) * r2) / len;
            mPath.moveTo(start.x + dy1, start.y - dx1);
            mPath.lineTo(end.x   + dy2, end.y   - dx2);
            mPath.lineTo(end.x   - dy2, end.y   + dx2);
            mPath.lineTo(start.x - dy1, start.y + dx1);
            mPath.close();
            mPath.addCircle(start.x, start.y, r1, Path.Direction.CW);
            mPath.addCircle(end.x,   end.y,   r2, Path.Direction.CW);
        }
        mCanvas.drawPath(mPath, mPaint);
        mPath.reset();
        mCanvas.restore();
    }
}
