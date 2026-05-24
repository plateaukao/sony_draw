package com.maoyuankao.sonydraw;

/**
 * EPD waveform/update mode constants used with EPDHelper.lockCanvas().
 * Verbatim from decompiled /system/framework/EPDHelper.jar →
 * com.sony.infras.dp_libraries.EinkMode (DPT-CP1 firmware 1.6.50.14130).
 *
 *  DU      = ~120ms, 1-bit, ghosts — used WHILE stroking
 *  A2      = ~120ms, 2-bit, ghosts — used WHILE panning
 *  GC16    = ~450ms, 16-grey, clean — used at stroke-end / image refresh
 *  *_SP1   = skip SP1 stage
 *  *_SP2   = skip SP2 stage
 *  *_FULL  = full-screen flash
 */
public final class EinkMode {
    public static final int UPDATE_MODE_NOWAIT_DU                       = 1;
    public static final int UPDATE_MODE_NOWAIT_GC16_PARTIAL             = 2;
    public static final int UPDATE_MODE_UI_DEFAULT                      = 2;
    public static final int UPDATE_MODE_NOWAIT_GC16                     = 34;
    public static final int UPDATE_MODE_NOWAIT_CONVERT_DU               = 1025;
    public static final int UPDATE_MODE_CONVERT_A2_PARTIAL              = 1028;
    public static final int UPDATE_MODE_NOWAIT_GC16_SP2                 = 8226;
    public static final int UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_SP2     = 12290;
    public static final int UPDATE_MODE_NOWAIT_GC16_FULL_SP1_SP2        = 12322;
    public static final int UPDATE_MODE_NOWAIT_NOCONVERT_DU_SP1_IGNORE  = 16385;
    public static final int UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_IGNORE  = 16386;
    public static final int UPDATE_MODE_NOWAIT_CONVERT_DU_SP1_IGNORE    = 17409;
    public static final int UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP2_IGNORE  = 24578;

    private EinkMode() {}
}
