package com.maoyuankao.sonydraw;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Stylus drawing canvas, modeled after
 * com.sony.apps.digitalpaperapp.view.widget.StylusView.
 *
 * Lifecycle:
 *   surfaceCreated  → start RenderingThread, paint background
 *   surfaceChanged  → allocate the off-screen stroke bitmap
 *   onTouchEvent    → filter to TOOL_TYPE_STYLUS, hand the event to
 *                     InkStrokeEditor which rasterises the segment into the
 *                     stroke bitmap, then ask the RenderingThread to push
 *                     the dirty rect to the EPD (DU during the stroke,
 *                     GC16 partial on pen-up)
 *
 * DirectHandwriting is enabled on ACTION_DOWN and the registered allow-area
 * is this view's global rect.
 */
public class StylusView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "StylusView";

    private RenderingThread mRendering;
    private Bitmap mStrokeBitmap;
    private Canvas mStrokeCanvas;
    private final InkStrokeEditor mEditor = new InkStrokeEditor();
    private final Rect mInvalidate = new Rect();

    public StylusView(Context context) { super(context); init(); }
    public StylusView(Context context, AttributeSet a) { super(context, a); init(); }

    private void init() {
        getHolder().addCallback(this);
        mEditor.setPixelWidth(2f);
    }

    public void clear() {
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
        mRendering.requestFinalize();    // paint initial white background
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
        // DPT-CP1 ships portrait; rotation 0 here covers the common case.
        DirectHandwriting.setAllowArea(global, penPx, 0);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int idx = event.getActionIndex();
        if (event.getToolType(idx) != MotionEvent.TOOL_TYPE_STYLUS) {
            // ignore finger/palm — exactly what Sony's StylusView does
            return true;
        }
        if (mRendering == null || mStrokeCanvas == null) return true;

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            DirectHandwriting.enable();
        }

        mInvalidate.setEmpty();
        mEditor.addStroke(event, mStrokeCanvas, mInvalidate);
        if (!mInvalidate.isEmpty()) {
            mRendering.invalidate(new Rect(mInvalidate));
            mRendering.requestRender();
        }

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_UP) {
            mRendering.requestFinalize();
        }
        return true;
    }
}
