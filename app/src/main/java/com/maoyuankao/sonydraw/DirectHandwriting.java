package com.maoyuankao.sonydraw;

import android.graphics.Rect;
import android.util.Log;

import com.sony.infras.dp_libraries.systemutil.SystemUtil;

/**
 * Thin wrapper over SystemUtil.EpdUtil for Sony Direct Handwriting (DHW).
 *
 * DHW is the kernel-level low-latency stylus path: once enabled, the
 * framebuffer driver itself renders strokes inside the registered
 * "allow areas" without bouncing the events through the Android UI
 * thread/compositor. The Java side still receives the MotionEvents
 * (so we can build the persistent Stroke), but the pixels appear
 * with near-zero perceptual lag.
 *
 * Equivalent to com.sony.apps.digitalpaperapp.utils.directhandwriting
 * .DirectHandwritingUtil from the DigitalPaperApp.
 */
public final class DirectHandwriting {
    private static final String TAG = "DirectHandwriting";

    private static int sCurrentAreaId = -1;

    private DirectHandwriting() {}

    public static boolean isAvailable() { return SystemUtil.isAvailable(); }

    public static void enable() {
        if (!SystemUtil.isAvailable()) return;
        try {
            SystemUtil.getEpdUtilInstance().setDhwState(true);
        } catch (Throwable t) { Log.w(TAG, "setDhwState(true) failed", t); }
    }

    public static void disable() {
        if (!SystemUtil.isAvailable()) return;
        try {
            SystemUtil.getEpdUtilInstance().setDhwState(false);
        } catch (Throwable t) { Log.w(TAG, "setDhwState(false) failed", t); }
    }

    /** rect must be in absolute screen coordinates (use View.getGlobalVisibleRect). */
    public static void setAllowArea(Rect rect, int strokePixelWidth, int rotationDeg) {
        if (rect == null || rect.isEmpty()) return;
        if (!SystemUtil.isAvailable()) return;
        int penWidth = Math.max(1, strokePixelWidth);
        int rotation = rotationDeg == 90 ? 1 : 0;
        try {
            SystemUtil.EpdUtil epd = SystemUtil.getEpdUtilInstance();
            epd.removeAllDhwArea();
            sCurrentAreaId = epd.addDhwArea(rect, penWidth, rotation);
            Log.i(TAG, "addDhwArea " + rect + " width=" + penWidth + " rot=" + rotation
                    + " id=" + sCurrentAreaId);
        } catch (Throwable t) { Log.w(TAG, "addDhwArea failed", t); }
    }

    public static void clearAllowArea() {
        if (!SystemUtil.isAvailable()) return;
        try {
            SystemUtil.getEpdUtilInstance().removeAllDhwArea();
            sCurrentAreaId = -1;
        } catch (Throwable t) { Log.w(TAG, "removeAllDhwArea failed", t); }
    }
}
