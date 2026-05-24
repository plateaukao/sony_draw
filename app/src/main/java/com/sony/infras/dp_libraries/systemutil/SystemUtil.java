package com.sony.infras.dp_libraries.systemutil;

import android.graphics.Rect;

/**
 * Minimal binding to libSystemUtil.so. The .so registers JNI methods against
 * this exact class path (verified with `strings libSystemUtil.so` → contains
 * "com/sony/infras/dp_libraries/systemutil/SystemUtil"), so the package and
 * the native method signatures must match the originals byte-for-byte.
 *
 * Only the Direct Handwriting (DHW) calls used by stylus drawing are kept.
 */
public class SystemUtil {

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
        System.loadLibrary("SystemUtil");
        sInstance = new SystemUtil();
    }

    private SystemUtil() { }

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
