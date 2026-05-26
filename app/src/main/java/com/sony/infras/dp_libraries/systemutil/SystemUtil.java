package com.sony.infras.dp_libraries.systemutil;

import android.util.Log;

import android.graphics.Rect;

/**
 * Minimal binding to libSystemUtil.so. The .so registers JNI methods against
 * this exact class path (verified with `strings libSystemUtil.so` → contains
 * "com/sony/infras/dp_libraries/systemutil/SystemUtil"), so the package and
 * the native method signatures must match the originals byte-for-byte.
 *
 * The .so itself is not bundled with the APK — it's loaded by absolute path
 * from the device's own /system/lib/. This keeps Sony's proprietary binary
 * out of the source tree, and the device's own copy is by definition the
 * right one for that firmware.
 *
 * On non-Sony Android the load fails; {@link #isAvailable()} returns false
 * and the app's caller code (see DirectHandwriting) treats the DHW path as
 * absent. The same APK then still runs (slowly) for development.
 *
 * Only the Direct Handwriting (DHW) calls used by stylus drawing are kept.
 */
public class SystemUtil {
    private static final String TAG = "SystemUtil";

    private static final String[] SO_PATHS = {
            "/system/lib/libSystemUtil.so",
            "/vendor/lib/libSystemUtil.so",
    };

    private static final boolean sLibLoaded;
    private static final SystemUtil sInstance;
    private static EpdUtil sEpd;

    public native int nativeAddDhwArea(int x, int y, int w, int h, int strokeWidth, boolean isMode0);
    public native int nativeChangeDhwStrokeWidth(int id, int strokeWidth);
    public native boolean nativeGetDhwState();
    public native int nativeRemoveDhwArea(int id);
    public native void nativeSetDhwState(boolean status);

    // Other natives the .so also registers — declared so JNI_OnLoad's
    // RegisterNatives() call still succeeds. We never call them.
    public native int getScreenShot(byte[] buf);
    public native int nativeWriteWaveform(byte[] wfData);
    public native int setShutdownScreenFlag(boolean b);
    public native int setShutdownScreenImage(byte[] data);
    public native int setStandbyScreenImage(byte[] data);

    static {
        boolean loaded = false;
        Throwable lastError = null;
        for (String path : SO_PATHS) {
            try {
                System.load(path);
                Log.i(TAG, "Loaded " + path);
                loaded = true;
                break;
            } catch (Throwable t) {
                lastError = t;
            }
        }
        if (!loaded) {
            Log.w(TAG, "libSystemUtil.so not available on this device; DHW disabled", lastError);
        }
        sLibLoaded = loaded;
        sInstance = new SystemUtil();
    }

    private SystemUtil() { }

    public static boolean isAvailable() { return sLibLoaded; }

    public static EpdUtil getEpdUtilInstance() {
        if (sEpd == null) sEpd = sInstance.new EpdUtil();
        return sEpd;
    }

    public class EpdUtil {
        public int addDhwArea(Rect rect, int strokeWidth, int rotation) {
            return nativeAddDhwArea(rect.left, rect.top, rect.width(), rect.height(),
                    strokeWidth, rotation == 0);
        }
        public int addDhwArea(Rect rect, int strokeWidth) {
            return addDhwArea(rect, strokeWidth, 0);
        }
        public void removeAllDhwArea() { nativeRemoveDhwArea(-1); }
        public int removeDhwArea(int id) { return id < 0 ? -1 : nativeRemoveDhwArea(id); }
        public int changeDhwStrokeWidth(int id, int strokeWidth) {
            return id < 0 ? -1 : nativeChangeDhwStrokeWidth(id, strokeWidth);
        }
        public void setDhwState(boolean status) { nativeSetDhwState(status); }
        public boolean getDhwState() { return nativeGetDhwState(); }
    }
}
