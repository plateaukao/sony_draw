package com.maoyuankao.sonydraw;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;

/**
 * Background rendering thread that pushes pixels to the SurfaceView in EPD
 * waveform modes. Strategy mirrors DigitalPaperApp.RenderingThread:
 *
 *   - while the pen is moving, lock the canvas in DU mode (fast, mono) and
 *     composite the stroke bitmap on top of a white background, restricted
 *     to the union of stroke deltas
 *   - after ACTION_UP, do one GC16 partial refresh over the total touched
 *     area so the result is clean 16-grey
 *
 * The "stroke bitmap" is owned by StylusView; the editor draws into its
 * Canvas while this thread reads from it.
 */
public class RenderingThread extends Thread {
    private static final String TAG = "RenderingThread";

    private final SurfaceHolder mHolder;
    private Bitmap mStrokeBitmap;
    private int mWidth, mHeight;

    private final Rect mPendingDirty   = new Rect();
    private final Rect mInFlightDirty  = new Rect();
    private final Rect mSinceStrokeBeg = new Rect();

    private boolean mExit;
    private boolean mRenderRequested;
    private boolean mFinalize;
    private boolean mFirstFrameDone;

    private PointF mEraserCenter;
    private float mEraserRadius;
    private final Paint mEraserPaint = new Paint();

    {
        mEraserPaint.setStyle(Paint.Style.STROKE);
        mEraserPaint.setStrokeWidth(2f);
        mEraserPaint.setStrokeCap(Paint.Cap.ROUND);
        mEraserPaint.setStrokeJoin(Paint.Join.ROUND);
        mEraserPaint.setColor(0xFF000000);
        mEraserPaint.setAntiAlias(false);
    }

    public RenderingThread(SurfaceHolder holder) { mHolder = holder; }

    public synchronized void setStrokeBitmap(Bitmap b, int w, int h) {
        mStrokeBitmap = b;
        mWidth = w;
        mHeight = h;
    }

    public synchronized void invalidate(Rect r) {
        if (r != null && !r.isEmpty()) mPendingDirty.union(r);
    }

    public synchronized void setEraserCenter(PointF center, float radius) {
        mEraserCenter = center;
        mEraserRadius = radius;
    }

    public synchronized void requestRender() {
        mRenderRequested = true;
        notify();
    }

    public synchronized void requestFinalize() {
        mFinalize = true;
        notify();
    }

    public synchronized void clear() {
        if (mStrokeBitmap != null) {
            new Canvas(mStrokeBitmap).drawColor(0, PorterDuff.Mode.CLEAR);
        }
        mPendingDirty.set(0, 0, mWidth, mHeight);
        mSinceStrokeBeg.set(0, 0, mWidth, mHeight);
        mFinalize = true;
        notify();
    }

    public synchronized void exit() {
        mExit = true;
        notify();
    }

    @Override public void run() {
        while (true) {
            Rect dirty;
            boolean finalize;
            synchronized (this) {
                while (!mExit && !mRenderRequested && !mFinalize) {
                    try { wait(); } catch (InterruptedException ignored) { return; }
                }
                if (mExit) return;
                dirty = new Rect(mPendingDirty);
                mInFlightDirty.set(mPendingDirty);
                mSinceStrokeBeg.union(mPendingDirty);
                mPendingDirty.setEmpty();
                mRenderRequested = false;
                finalize = mFinalize;
                mFinalize = false;
            }

            if (finalize) {
                Rect finalDirty = mSinceStrokeBeg.isEmpty() ? new Rect(0, 0, mWidth, mHeight)
                                                            : new Rect(mSinceStrokeBeg);
                drawFrame(finalDirty, /*gc16=*/ true);
                synchronized (this) { mSinceStrokeBeg.setEmpty(); }
            } else if (!dirty.isEmpty()) {
                drawFrame(dirty, /*gc16=*/ false);
            }
        }
    }

    private void drawFrame(Rect dirty, boolean gc16) {
        int mode;
        if (gc16) {
            // GC16 partial; first frame after surfaceChanged gets a full flash.
            mode = mFirstFrameDone
                    ? EinkMode.UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_IGNORE
                    : EinkMode.UPDATE_MODE_NOWAIT_GC16_SP2;
            mFirstFrameDone = true;
        } else {
            mode = EinkMode.UPDATE_MODE_NOWAIT_NOCONVERT_DU_SP1_IGNORE;
        }
        Canvas canvas = EpdHelper.lockCanvas(mHolder, dirty, mode);
        if (canvas == null) {
            Log.w(TAG, "lockCanvas returned null (mode=" + mode + ")");
            return;
        }
        try {
            canvas.drawColor(-1);           // white background
            Bitmap bm;
            PointF ec;
            float er;
            synchronized (this) { bm = mStrokeBitmap; ec = mEraserCenter; er = mEraserRadius; }
            if (bm != null && !bm.isRecycled()) {
                canvas.drawBitmap(bm, 0, 0, null);
            }
            if (ec != null && er > 0f) {
                canvas.drawCircle(ec.x, ec.y, er, mEraserPaint);
            }
        } finally {
            mHolder.unlockCanvasAndPost(canvas);
        }
    }
}
