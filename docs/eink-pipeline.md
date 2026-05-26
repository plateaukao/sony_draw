---
title: E-ink pipeline
layout: default
nav_order: 3
---

# E-ink pipeline
{: .no_toc }

1. TOC
{:toc}

---

## The "fast then clean" strategy

Drawing on an e-ink panel is a fight between two competing demands:

- **Speed.** A stroke needs to appear under the pen with sub-50ms latency, or the lag is so jarring people stop trusting the device.
- **Fidelity.** The final ink should look like real ink — sharp edges, no ghosting, full 16-grey range.

You can't have both in a single refresh. The panel's waveform controller exposes a handful of update modes, each of which is a trade between latency, grey-depth, and how much it disturbs the surrounding pixels:

| Mode | Time | Bits | Ghosts? | Used for |
|------|------|------|---------|----------|
| **DU** (Direct Update) | ~120ms | 1 (black/white) | Yes | Mid-stroke painting |
| **A2** | ~120ms | 2 (binary-ish) | Yes | Panning / scrolling animations |
| **GC16 partial** | ~450ms | 16 grey | Clean | Stroke-end finalize |
| **GC16 full** | ~450ms + screen flash | 16 grey | Clean | First frame after surface create |

Sony's app — and `sony_draw` — uses **DU while the pen is moving, then one GC16 partial refresh on lift**. The user sees an instant 1-bit version of the stroke during writing, and ~450ms after they lift the pen the same region snaps to a clean, anti-aliased 16-grey final. Most people never consciously notice the second refresh.

## Picking the mode at the call site

`RenderingThread.drawFrame` is the one place this decision lives:

```java
if (gc16) {
    mode = mFirstFrameDone
            ? EinkMode.UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_IGNORE
            : EinkMode.UPDATE_MODE_NOWAIT_GC16_SP2;
    mFirstFrameDone = true;
} else {
    mode = EinkMode.UPDATE_MODE_NOWAIT_NOCONVERT_DU_SP1_IGNORE;
}
```

Three cases:

1. **`UPDATE_MODE_NOWAIT_NOCONVERT_DU_SP1_IGNORE`** during stroke movement. `NOWAIT` queues the update without blocking the calling thread. `NOCONVERT` skips the 8bpp → waveform conversion stage because the source bitmap is already mostly 1-bit. `SP1_IGNORE` skips a panel-private filtering stage that would otherwise add ~30ms.
2. **`UPDATE_MODE_NOWAIT_GC16_PARTIAL_SP1_IGNORE`** on lift, when we have an established working area. Partial refresh only repaints the dirty rect — no screen flash, the user just sees the strokes go from ragged 1-bit to clean grey.
3. **`UPDATE_MODE_NOWAIT_GC16_SP2`** for the very first frame after `surfaceChanged`. This *does* flash the whole screen, which we want exactly once at the start of the activity to clear any residual ink from a previous app.

All these constants live in `EinkMode.java` and are copied **verbatim** from the decompiled `EPDHelper.jar` (DPT-CP1 firmware 1.6.50.14130). Don't invent new mode integers — the kernel-side waveform table only recognises the ones Sony shipped.

## Bringing dirty regions along

Mid-stroke, `InkStrokeEditor` builds up a per-segment dirty `Rect` in screen-pixel space and unions it into `RenderingThread.mPendingDirty`. Each `requestRender()` then locks the canvas restricted to that rect:

```java
Canvas canvas = EpdHelper.lockCanvas(mHolder, dirty, mode);
```

This is doing two things:

1. **Saves time.** Repainting only the changed strip is the difference between a sub-50ms turnaround and a 200ms one.
2. **Saves contrast.** Each DU update washes out the immediate neighbourhood. The smaller the rect, the less you wash out.

`mSinceStrokeBeg` accumulates the union of every per-segment rect since the last `requestFinalize()`. That's the rectangle the GC16 partial pass repaints on lift — exactly the region we touched during the stroke and no more.

## Talking to the Sony framework

The overloads we need — `SurfaceHolder.lockCanvas(Rect, int updateMode)` and `View.invalidate(Rect, int updateMode)` — don't exist on stock Android. They're added by Sony's modified framework, in classes like `com.sony.infras.dp_libraries.EPDHelper` that ship in `/system/framework/EPDHelper.jar` on the device.

Rather than hard-link against a stub jar, `EpdHelper.java` reaches for them via reflection:

```java
sLockCanvasRectMode = holder.getClass().getMethod(
        "lockCanvas", Rect.class, int.class);
```

If the lookup throws `NoSuchMethodException`, `sLockCanvasRectMode` stays null and the call site falls back to plain `holder.lockCanvas()`. The same APK then runs on a stock Android device for development — with normal compositor refresh, which means 200-500ms strokes, fine for testing logic but not latency.

The reflection result is cached. There's no per-call overhead beyond a method invocation.

## What you'll see in logcat

The interesting tags:

```
adb logcat | grep -E 'EpdHelper|RenderingThread|StylusView'
```

On a DPT-CP1 the first line after launch tells you the reflection found everything:

```
I/EpdHelper: EPD reflection: lockCanvas(int)=true lockCanvas(Rect,int)=true invalidate(Rect,int)=true
```

On a stock device it'll be `false` for all three, with no warning — the fallback path is silent on purpose.
