# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-Activity Android stylus drawing app targeted at the **Sony DPT-CP1** e-ink reader. It is a clean-room reimplementation of the drawing pipeline from Sony's `DigitalPaperApp` (decompiled sources under `reference/`, gitignored), using only the public stylus API plus reflection into the Sony-specific framework extensions.

The repo also contains, under `patches/`, a port of the same stylus path into KOReader's `pencil.koplugin` — see `patches/README.md` for the cross-repo context.

## Build / run

Standard Gradle wrapper. The device-targeting choices below are intentional and must not be "modernised" without thought:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.maoyuankao.sonydraw/.MainActivity
adb logcat | grep -E 'StylusView|RenderingThread|DirectHandwriting|EpdHelper'
```

- `minSdk 22 / targetSdk 22` — DPT-CP1 firmware is Android 5.1.1 (API 22). **Do not** bump `targetSdk`; runtime-permission and SAF behaviour changes will break the device, and linker namespace isolation (API 24+) would block the `/system/lib/` load described below.
- **No bundled native code, no `abiFilters`.** `libSystemUtil.so` is loaded by absolute path from the device's own `/system/lib/libSystemUtil.so` at runtime (see `SystemUtil.java`'s static initializer). The APK contains no JNI libs.
- `compileSdk 33` is fine because we only call APIs available on the device.
- No tests, no lint config, no CI. Verification is "build, install, draw on the device."

The same APK runs on a generic Android device for development, but EPD reflection will fail soft (see `EpdHelper`) and the result will be slow with normal compositing — useful for logic checks, not for latency work.

## Big-picture architecture

The drawing pipeline is a four-layer pull-apart of Sony's `DigitalPaperApp.StylusView`. Read these files together — none of them makes sense in isolation:

```
MotionEvent
    │
    ▼
StylusView ── per-event tool dispatch (pen vs eraser)
    │
    ├─► InkStrokeEditor ── rasterise tapered quad+circle into stroke Bitmap
    │       └─► StrokeDetector (replays historical sub-frame samples)
    │       └─► Stroke (persisted point list + bounds, for redraw + erase hit-test)
    │
    └─► EraseMath ── circle/segment/segment-cross hit-test against persisted strokes
            │
            ▼
       (re-rasterise surviving strokes via InkStrokeEditor.renderAll)
            │
            ▼
       RenderingThread ── owns the SurfaceView frame loop
            │
            ▼
       EpdHelper.lockCanvas(holder, dirty, EinkMode.*) ── reflection into Sony framework
```

Two parallel-but-independent "fast paths" feed the EPD:

1. **`RenderingThread`** locks the SurfaceView canvas in DU waveform mode (`EinkMode.UPDATE_MODE_NOWAIT_NOCONVERT_DU_SP1_IGNORE`) while the pen is moving, and switches to GC16 partial on lift (`EinkMode.UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_IGNORE`) to clean up the ghosting. The first GC16 after `surfaceChanged` is a full-screen flash (`UPDATE_MODE_NOWAIT_GC16_SP2`). Mode constants are verbatim from the decompiled `EPDHelper.jar` — don't invent new ones.
2. **`DirectHandwriting`** (DHW) — kernel-level low-latency stylus path. Once enabled with a registered allow-area rect, the framebuffer driver paints stylus strokes directly without going through the Android UI thread. The Java side still receives the MotionEvents (we still need them to build the persistent `Stroke`). The DU/GC16 paint from `RenderingThread` then overwrites those temporary kernel pixels with our anti-aliased version on lift.

## Things that look weird but are load-bearing

- **`com/sony/infras/dp_libraries/systemutil/SystemUtil`** — exact package + class name is required. `libSystemUtil.so`'s `JNI_OnLoad` calls `RegisterNatives` against that fully-qualified path (confirm with `strings /system/lib/libSystemUtil.so` on the device, or against any pulled copy). Rename the class or move the package and JNI registration fails on device. The "extra" natives in `SystemUtil.java` (`getScreenShot`, `nativeWriteWaveform`, etc.) are declared only so `RegisterNatives` succeeds; we never call them.
- **`System.load(absolute path)` is deliberate** — `System.loadLibrary("SystemUtil")` would look in `nativeLibraryDir`, where we no longer ship anything. The absolute-path load works because the DPT-CP1 is API 22, before Android 7's linker namespace isolation. `SystemUtil.isAvailable()` returns `false` on non-Sony devices so callers can no-op cleanly.
- **Eraser detection is stylus-side, not a UI toggle.** `StylusView.onTouchEvent` checks `TOOL_TYPE_ERASER` (Wacom EMR flipped tip) **and** `BUTTON_TERTIARY` (some Sony firmware reports the eraser end this way). If you change either condition, test with both the actual flipped stylus and any device where firmware uses the button-state mapping.
- **DHW must be disabled during erase strokes** (`StylusView.onTouchEraser`). The kernel DHW path doesn't know about "eraser" — it would paint the eraser motion as ink. We toggle DHW off on the first eraser event and back on at lift.
- **`Region.Op.REPLACE` in `InkStrokeEditor.drawSegment`** — this is deprecated on stock Android but works on Sony's device, and is needed to clip each segment paint to just the dirty rect (otherwise the eraser update region grows unboundedly). Leave it.
- **`mPaint.setAntiAlias(false)` everywhere.** EPD DU mode is 1-bit; AA produces dithered greys that the next DU update can't redraw cleanly. Don't "improve" the rendering by turning AA on.
- **`EpdHelper` reflection swallows `NoSuchMethodException` and falls back** to plain `lockCanvas()` / `invalidate(Rect)`. This is the only thing that lets the APK install on a non-Sony device for development. Don't add a hard dependency on the Sony overloads.

## Reference material

`reference/` (gitignored) holds the decompiled Sony app + framework JAR + baksmali tooling. When something in this app's design looks arbitrary, the answer is usually in `reference/decompiled/DigitalPaperApp/sources/com/sony/apps/digitalpaperapp/` — match the package path against the Java class names referenced in our javadoc comments.
