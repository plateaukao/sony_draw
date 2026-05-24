package com.maoyuankao.sonydraw;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;

import java.lang.reflect.Method;

/**
 * Reflection wrapper for Sony's extended Android framework APIs:
 *   - SurfaceHolderEink.lockCanvas(int updateMode)
 *   - SurfaceHolderEink.lockCanvas(Rect dirty, int updateMode)
 *   - View.invalidate(Rect, int updateMode)
 *
 * On stock Android these overloads don't exist; on a Sony DPT device the
 * SurfaceHolder concrete implementation has them and reflection finds them.
 * Falls back to plain lockCanvas() on non-Sony devices so the same APK runs
 * (with slow refresh) on a normal Android device for development.
 *
 * Equivalent to com.sony.infras.dp_libraries.EPDHelper in /system/framework.
 */
public final class EpdHelper {
    private static final String TAG = "EpdHelper";

    private static Method sLockCanvasMode;
    private static Method sLockCanvasRectMode;
    private static Method sInvalidateRectMode;
    private static boolean sResolved;

    private EpdHelper() {}

    private static synchronized void resolve(SurfaceHolder holder, View anyView) {
        if (sResolved) return;
        sResolved = true;
        if (holder != null) {
            try {
                sLockCanvasMode = holder.getClass().getMethod("lockCanvas", int.class);
            } catch (NoSuchMethodException ignored) { }
            try {
                sLockCanvasRectMode = holder.getClass().getMethod("lockCanvas", Rect.class, int.class);
            } catch (NoSuchMethodException ignored) { }
        }
        if (anyView != null) {
            try {
                sInvalidateRectMode = View.class.getMethod("invalidate", Rect.class, int.class);
            } catch (NoSuchMethodException ignored) { }
        }
        Log.i(TAG, "EPD reflection: lockCanvas(int)=" + (sLockCanvasMode != null)
                + " lockCanvas(Rect,int)=" + (sLockCanvasRectMode != null)
                + " invalidate(Rect,int)=" + (sInvalidateRectMode != null));
    }

    public static Canvas lockCanvas(SurfaceHolder holder, int updateMode) {
        resolve(holder, null);
        if (sLockCanvasMode != null) {
            try {
                return (Canvas) sLockCanvasMode.invoke(holder, updateMode);
            } catch (Exception e) {
                Log.w(TAG, "lockCanvas(int) reflection failed, falling back", e);
            }
        }
        return holder.lockCanvas();
    }

    public static Canvas lockCanvas(SurfaceHolder holder, Rect inOutDirty, int updateMode) {
        resolve(holder, null);
        if (sLockCanvasRectMode != null) {
            try {
                return (Canvas) sLockCanvasRectMode.invoke(holder, inOutDirty, updateMode);
            } catch (Exception e) {
                Log.w(TAG, "lockCanvas(Rect,int) reflection failed, falling back", e);
            }
        }
        return holder.lockCanvas(inOutDirty);
    }

    public static void invalidate(View view, Rect dirty, int updateMode) {
        resolve(null, view);
        if (sInvalidateRectMode != null) {
            try {
                sInvalidateRectMode.invoke(view, dirty, updateMode);
                return;
            } catch (Exception e) {
                Log.w(TAG, "invalidate(Rect,int) reflection failed, falling back", e);
            }
        }
        view.invalidate(dirty);
    }
}
