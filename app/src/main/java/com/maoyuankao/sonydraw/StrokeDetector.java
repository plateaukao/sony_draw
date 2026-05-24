package com.maoyuankao.sonydraw;

import android.graphics.PointF;
import android.view.MotionEvent;

/**
 * Splits a stylus MotionEvent stream into Begin/Move/End callbacks and
 * walks the historical batch so we don't drop sub-frame points.
 * Ported from com.sony.apps.digitalpaperapp.utils.StrokeDetector.
 */
public class StrokeDetector {
    public interface OnStrokeListener {
        void onStrokeBegin(PointF p, float pressure, long t);
        void onStrokeMove(PointF p, float pressure, long t);
        void onStrokeEnd(PointF p, float pressure, long t);
    }

    private final OnStrokeListener mListener;
    private final PointF mOld = new PointF(-1f, -1f);
    private int mActionId;
    private boolean mMoving;

    public StrokeDetector(OnStrokeListener l) { mListener = l; }

    public void onTouchEvent(MotionEvent e) {
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        int action = e.getActionMasked();
        boolean isUp = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                mMoving = false;
                mActionId = id;
                mOld.set(-1f, -1f);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_UP:
                isUp = true;
                break;
            case MotionEvent.ACTION_MOVE:
                break;
            default:
                return;
        }
        if (mActionId != id) return;

        // Replay historical samples first (sub-frame batched events).
        int hist = e.getHistorySize();
        for (int i = 0; i < hist; i++) {
            emit(e.getHistoricalX(idx, i), e.getHistoricalY(idx, i),
                 e.getHistoricalPressure(idx, i), e.getHistoricalEventTime(i), false);
        }
        emit(e.getX(idx), e.getY(idx), e.getPressure(idx), e.getEventTime(), isUp);
    }

    private void emit(float x, float y, float pressure, long t, boolean isUp) {
        if (!isUp && mOld.x == x && mOld.y == y) return;
        PointF p = new PointF(x, y);
        if (!mMoving) {
            mMoving = true;
            if (!isUp) mListener.onStrokeBegin(p, pressure, t);
            else       mListener.onStrokeEnd(p, pressure, t);
        } else if (isUp) {
            mListener.onStrokeEnd(p, pressure, t);
        } else {
            mListener.onStrokeMove(p, pressure, t);
        }
        mOld.set(x, y);
    }
}
