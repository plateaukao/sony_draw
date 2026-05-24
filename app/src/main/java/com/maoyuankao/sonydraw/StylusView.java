package com.maoyuankao.sonydraw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Stylus drawing canvas, modeled after
 * com.sony.apps.digitalpaperapp.view.widget.StylusView.
 *
 * Tool is determined per-event from the stylus itself — no UI toggle:
 *   - TOOL_TYPE_ERASER  → erase (Wacom EMR flipped-tip)
 *   - BUTTON_TERTIARY   → erase (matches Sony's buttonState=4 mapping;
 *                                some Sony stylus firmware reports the
 *                                eraser end this way rather than via toolType)
 *   - otherwise         → pen
 */
public class StylusView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "StylusView";

    private static final float PEN_WIDTH_PX     = 2f;
    private static final float ERASER_RADIUS_PX = 5f;

    private RenderingThread mRendering;
    private Bitmap mStrokeBitmap;
    private Canvas mStrokeCanvas;
    private final InkStrokeEditor mEditor = new InkStrokeEditor();
    private final Rect mInvalidate = new Rect();

    private final List<Stroke> mStored = new ArrayList<>();
    private PointF mEraserPrev;
    private boolean mErasing;

    public StylusView(Context context) { super(context); init(); }
    public StylusView(Context context, AttributeSet a) { super(context, a); init(); }

    private void init() {
        getHolder().addCallback(this);
        mEditor.setPixelWidth(PEN_WIDTH_PX);
    }

    public void clear() {
        mStored.clear();
        if (mRendering != null) mRendering.clear();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
        mRendering = new RenderingThread(holder);
        mRendering.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Log.i(TAG, "surfaceChanged " + w + "x" + h);
        if (mStrokeBitmap != null) mStrokeBitmap.recycle();
        mStrokeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        mStrokeCanvas = new Canvas(mStrokeBitmap);
        mStrokeCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        mRendering.setStrokeBitmap(mStrokeBitmap, w, h);
        mRendering.invalidate(new Rect(0, 0, w, h));
        mRendering.requestFinalize();
        registerDirectHandwriteArea();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        DirectHandwriting.clearAllowArea();
        DirectHandwriting.disable();
        if (mRendering != null) {
            mRendering.exit();
            try { mRendering.join(); } catch (InterruptedException ignored) {}
            mRendering = null;
        }
        if (mStrokeBitmap != null) { mStrokeBitmap.recycle(); mStrokeBitmap = null; }
    }

    private void registerDirectHandwriteArea() {
        Rect global = new Rect();
        if (!getGlobalVisibleRect(global)) return;
        int penPx = (int) Math.ceil(mEditor.getPixelWidth());
        DirectHandwriting.setAllowArea(global, penPx, 0);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int idx = event.getActionIndex();
        int toolType = event.getToolType(idx);
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return true;
        }
        if (mRendering == null || mStrokeCanvas == null) return true;

        int action = event.getActionMasked();
        boolean erase = toolType == MotionEvent.TOOL_TYPE_ERASER
                || (event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0;

        if (erase) {
            onTouchEraser(event, action);
        } else {
            onTouchPen(event, action);
        }
        return true;
    }

    private void onTouchPen(MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            DirectHandwriting.enable();
        }
        mInvalidate.setEmpty();
        List<Stroke> finished = mEditor.addStroke(event, mStrokeCanvas, mInvalidate);
        if (!finished.isEmpty()) mStored.addAll(finished);

        if (!mInvalidate.isEmpty()) {
            mRendering.invalidate(new Rect(mInvalidate));
            mRendering.requestRender();
        }
        if (isUp(action)) {
            mRendering.requestFinalize();
        }
    }

    private void onTouchEraser(MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN || !mErasing) {
            DirectHandwriting.disable();  // kernel-fast path would draw pen strokes from
                                          // these events; off until we're back on the tip
            mEraserPrev = null;
            mErasing = true;
        }

        PointF cur = new PointF(event.getX(event.getActionIndex()),
                                event.getY(event.getActionIndex()));

        boolean removed = false;
        Rect erasedBounds = null;
        Iterator<Stroke> it = mStored.iterator();
        while (it.hasNext()) {
            Stroke s = it.next();
            if (!boundsTouchEraser(s, cur)) continue;
            if (EraseMath.strokeHit(s, mEraserPrev, cur, ERASER_RADIUS_PX)) {
                it.remove();
                removed = true;
                Rect r = new Rect();
                s.getBounds().roundOut(r);
                int pad = (int) Math.ceil(mEditor.getPixelWidth());
                r.inset(-pad, -pad);
                if (erasedBounds == null) erasedBounds = r; else erasedBounds.union(r);
            }
        }
        if (removed) {
            mStrokeCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
            InkStrokeEditor.renderAll(mStored, mStrokeCanvas, mEditor.getPixelWidth());
        }

        Rect dirty = new Rect();
        unionEraserCircle(dirty, cur);
        if (mEraserPrev != null) unionEraserCircle(dirty, mEraserPrev);
        if (erasedBounds != null) dirty.union(erasedBounds);

        mRendering.setEraserCenter(new PointF(cur.x, cur.y), ERASER_RADIUS_PX);
        mRendering.invalidate(dirty);
        mRendering.requestRender();

        mEraserPrev = cur;

        if (isUp(action)) {
            mRendering.setEraserCenter(null, 0f);
            mRendering.requestFinalize();
            mEraserPrev = null;
            mErasing = false;
            DirectHandwriting.enable();   // back to pen for the next tip-down
        }
    }

    private boolean boundsTouchEraser(Stroke s, PointF center) {
        android.graphics.RectF b = s.getBounds();
        return b.left   - ERASER_RADIUS_PX <= center.x
            && b.right  + ERASER_RADIUS_PX >= center.x
            && b.top    - ERASER_RADIUS_PX <= center.y
            && b.bottom + ERASER_RADIUS_PX >= center.y;
    }

    private void unionEraserCircle(Rect into, PointF c) {
        int pad = (int) Math.ceil(ERASER_RADIUS_PX) + 2;
        Rect r = new Rect((int) c.x - pad, (int) c.y - pad,
                          (int) c.x + pad, (int) c.y + pad);
        if (into.isEmpty()) into.set(r); else into.union(r);
    }

    private static boolean isUp(int action) {
        return action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_CANCEL
            || action == MotionEvent.ACTION_POINTER_UP;
    }
}
