---
title: Direct Handwriting
layout: default
nav_order: 4
---

# Direct Handwriting (DHW)
{: .no_toc }

1. TOC
{:toc}

---

## What it is

Direct Handwriting is Sony's name for a kernel-level stylus path. When DHW is enabled and the active touch falls inside a registered "allow area" rect, the EPDC framebuffer driver paints the stroke directly to the panel — never bouncing through the Android UI thread, never going through SurfaceFlinger, never touching the app's `onTouchEvent`.

The Java side still receives the MotionEvents (so the app can build its persistent stroke model), but the *pixels* arrive faster than any Java path could possibly produce them. The perceptual latency goes from "obvious lag" (~120ms with DU alone) to "you can't tell it's not a real pen" (~30ms with DHW + DU finalize).

This is the same primitive that Sony's first-party `DigitalPaperApp` uses. Without DHW you can match the second half of Sony's pipeline (DU during, GC16 on lift) but not the first half (kernel-fast paint during).

## How the lifecycle works

DHW has two pieces of state: an **enable flag** and a list of **allow-area rectangles**. The kernel only paints strokes when the flag is on **and** the touch lands inside one of the registered rects.

`sony_draw` manages them like this:

| Event | Action |
|-------|--------|
| `surfaceChanged` (i.e. `StylusView` knows its on-screen rect) | `setAllowArea(globalRect, penWidth, rotation)` |
| Pen-down with pen tip | `DirectHandwriting.enable()` |
| Pen-down with eraser tip | `DirectHandwriting.disable()` |
| Eraser pen-up | `DirectHandwriting.enable()` again |
| `surfaceDestroyed` | `clearAllowArea()` + `disable()` |

The "disable during eraser" rule is important: the kernel doesn't know that an event with `TOOL_TYPE_ERASER` is meant to erase. If DHW is on during an eraser stroke, you'll erase from your persistent model (Java side) while *adding* a fresh black ink line where you erased (kernel side). The visible result is genuinely confusing — strokes vanish, but they're being immediately replaced with new ink from the eraser tip's coordinates. Toggling DHW off for the duration of the eraser solves this completely.

## JNI binding

The kernel-side ioctls are reached through a single shared library, `libSystemUtil.so`, that ships in `/system/lib/` on the DPT-CP1. Five entry points matter for DHW:

```java
public native int  nativeAddDhwArea(int x, int y, int w, int h,
                                    int strokeWidth, boolean isMode0);
public native int  nativeChangeDhwStrokeWidth(int id, int strokeWidth);
public native int  nativeRemoveDhwArea(int id);
public native void nativeSetDhwState(boolean status);
public native boolean nativeGetDhwState();
```

These are wrapped by `SystemUtil.EpdUtil` (an inner class) and then by `DirectHandwriting` for ergonomics:

```java
DirectHandwriting.setAllowArea(globalRect, penPx, 0);
DirectHandwriting.enable();
// ... pen events flow ...
DirectHandwriting.disable();
DirectHandwriting.clearAllowArea();
```

## JNI package-name landmine

This is the single most important non-obvious thing about this binding.

`libSystemUtil.so` registers its native method implementations in `JNI_OnLoad` by calling `RegisterNatives` against a **hardcoded class path string**. You can confirm this with `strings`:

```sh
$ strings /system/lib/libSystemUtil.so | grep dp_libraries
com/sony/infras/dp_libraries/systemutil/SystemUtil
```

This means our Java side **must** declare its native methods on the class `com.sony.infras.dp_libraries.systemutil.SystemUtil`, byte-for-byte. Rename the class, move the package, even capitalise differently — and `RegisterNatives` finds no class to attach to, `JNI_OnLoad` returns an error, and the library load fails.

This is also why the package directory `app/src/main/java/com/sony/infras/dp_libraries/systemutil/` exists in this repo despite being a "Sony" package. It's a name the binary requires.

The same `RegisterNatives` call registers all the native methods the library exports — including ones we don't use (`getScreenShot`, `nativeWriteWaveform`, `setShutdownScreenImage`, etc.). Those have to be declared on our `SystemUtil` class too, or the registration mismatches and fails. We declare them, never call them, and let the JVM treat them as dead code.

## Loading without bundling the binary

`libSystemUtil.so` is Sony's proprietary code; we don't redistribute it in this repo. Instead, `SystemUtil.java`'s static initializer loads it by absolute path from the device:

```java
private static final String[] SO_PATHS = {
        "/system/lib/libSystemUtil.so",
        "/vendor/lib/libSystemUtil.so",
};

static {
    boolean loaded = false;
    for (String path : SO_PATHS) {
        try {
            System.load(path);
            loaded = true;
            break;
        } catch (Throwable t) { /* try next */ }
    }
    sLibLoaded = loaded;
    sInstance = new SystemUtil();
}
```

This works because the DPT-CP1 is API 22. Android 7 (API 24) introduced **linker namespace isolation**, which would prevent a third-party app from `dlopen`-ing a path under `/system/lib/`. The DPT-CP1 is locked at API 22 and Sony has stopped shipping firmware updates for it, so this restriction will never come into play here.

The library's own `NEEDED` dependencies — `libc`, `libm`, `liblog`, `libnativehelper`, `libstdc++` — are all standard Android system libraries on the device's default linker path, so the load completes without further linker drama.

## Failing gracefully on non-Sony devices

`SystemUtil.isAvailable()` returns `false` if the `System.load` calls all failed (e.g. the APK is running on a Pixel, an emulator, or any non-Sony Android device for development). `DirectHandwriting` checks this flag before every call:

```java
public static void enable() {
    if (!SystemUtil.isAvailable()) return;
    try {
        SystemUtil.getEpdUtilInstance().setDhwState(true);
    } catch (Throwable t) { Log.w(TAG, "setDhwState(true) failed", t); }
}
```

Without the early return you'd get a `NoClassDefFoundError` (from the failed `<clinit>` cascading) on every stylus event — a logcat full of stack traces. With it, the app runs cleanly with DHW just being absent, and the slower `EpdHelper` fallback (plain `lockCanvas`) handles painting.

## Verifying it's working

On a DPT-CP1, after launching the app:

```sh
adb logcat | grep -E 'SystemUtil|DirectHandwriting'
```

Expected output:

```
I/SystemUtil: Loaded /system/lib/libSystemUtil.so
I/DirectHandwriting: addDhwArea Rect(0, 0 - 1404, 1872) width=2 rot=0 id=0
```

If you see "libSystemUtil.so not available on this device; DHW disabled" — either you're not on a Sony device, the firmware moved the binary (try `adb shell find /system /vendor -name 'libSystemUtil.so'`), or `dlopen` failed for an unexpected reason and the stack trace in the log will say why.
