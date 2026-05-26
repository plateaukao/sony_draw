---
title: Porting to KOReader
layout: default
nav_order: 6
---

# Porting the stylus path to KOReader
{: .no_toc }

1. TOC
{:toc}

---

## Why

[KOReader](https://github.com/koreader/koreader) is the de-facto third-party ebook reader for e-ink devices. Its `pencil.koplugin` adds handwriting annotation on top of any document — but on the DPT-CP1 it suffers from the same latency problem stock Android apps do: every stroke triggers a slow full refresh, because nothing in KOReader knows about Sony's EPD waveform controller or DHW.

The work in [`patches/`](https://github.com/plateaukao/sony_draw/tree/main/patches) ports the same two primitives `sony_draw` uses (`EpdHelper` reflection and `DirectHandwriting`) into KOReader, so the pencil plugin draws at native latency on the DPT-CP1 with no changes to the plugin's own logic.

The full step-by-step is in [`patches/README.md`](https://github.com/plateaukao/sony_draw/blob/main/patches/README.md); this page is the why-and-how-it-fits-together overview.

## Three repos, three patches

KOReader's Android build is a stack of submodules. The fix has to touch three layers:

```
┌──────────────────────────────────────────────────────────┐
│ pencil.koplugin (3rd-party)                              │
│   patches/pencil-sony-android.patch                      │
│   • calls android.stylusDhw* around stylus lifecycle     │
└──────────────────────────────────────────────────────────┘
                          ▲
                          │ Lua FFI
                          ▼
┌──────────────────────────────────────────────────────────┐
│ koreader-base (submodule)                                │
│   commit 250ae579: input_android.lua                     │
│   • AMotionEvent_getToolType → ABS_MT_TOOL_TYPE          │
│     (so the pencil plugin's tool-based eraser            │
│     detection works on Android the same way it           │
│     already does on Kobo)                                │
└──────────────────────────────────────────────────────────┘
                          ▲
                          │ JNI
                          ▼
┌──────────────────────────────────────────────────────────┐
│ android-luajit-launcher (submodule)                      │
│   commit f744b56: SonyDhw.kt, SonyEPDController.kt,      │
│   EPDFactory routing, JNI bridge for stylusDhw*          │
│   • Kotlin equivalents of sony_draw's EpdHelper and      │
│     DirectHandwriting                                    │
└──────────────────────────────────────────────────────────┘
```

The pieces line up one-to-one with `sony_draw`'s Java:

| `sony_draw` (this repo) | KOReader equivalent |
|---|---|
| `EpdHelper.java` (reflection into `lockCanvas(Rect,int)`) | `SonyEPDController.kt` (reflection into `View.invalidate(Rect,int)`) |
| `EinkMode.java` constants | `EPDController.java` constants in launcher |
| `DirectHandwriting.java` | `SonyDhw.kt` |
| `SystemUtil` JNI binding | same — copy-paste, same package path landmine applies |

## What the koreader-base patch is fixing

KOReader handles stylus events on Kobo through the kernel's input device, where `EV_ABS / ABS_MT_TOOL_TYPE` distinguishes pen from eraser. The pencil plugin checks `slot.tool` to decide whether to draw or erase.

On Android there's no `EV_ABS / ABS_MT_TOOL_TYPE` — instead the equivalent information is on each `AMotionEvent`'s pointer, retrievable via `AMotionEvent_getToolType()`. KOReader's Android input adapter (`ffi/input_android.lua`) wasn't pulling that field, so `slot.tool` was always unset, and the plugin's eraser detection never fired.

The koreader-base commit calls `AMotionEvent_getToolType()` per pointer and synthesizes a fake `ABS_MT_TOOL_TYPE` event before the position events. The translation matches the Elan-panel convention:

- Android `TOOL_TYPE_STYLUS` (2) → ABS value 1 (pen)
- Android `TOOL_TYPE_ERASER` (4) → ABS value 2 (eraser)
- everything else → 0 (finger)

After that, every Kobo-side codepath that consumes `slot.tool` works unchanged on Android.

## What the launcher patch is adding

`android-luajit-launcher` is KOReader's Android shell — it owns the Activity, the SurfaceView, and the JNI bridge to the Lua runtime. The patch adds:

- **`device/sony/SonyDhw.kt`** — the Kotlin twin of this repo's `DirectHandwriting.java`. Reflection-based, fail-soft.
- **`device/epd/sony/SonyEPDController.kt`** — implements KOReader's `EPDInterface` using `View.invalidate(Rect, int)` (the overload the DPT-CP1's framework exposes). The previous routing for `DeviceInfo.Id.SONY_CP1` pointed at the Nook/NTX controller, which uses a different invalidate signature and never worked.
- **Five JNI-exposed methods** (`stylusDhwAvailable`, `stylusDhwEnable`, `stylusDhwDisable`, `stylusDhwSetArea`, `stylusDhwClearArea`) plus matching `android.lua` FFI cdefs.

The plugin patch then just calls those from Lua at the right moments:

```lua
function PencilLoader:setupStylusCallback()
    -- ...existing setup...
    if android and android.stylusDhwAvailable and android.stylusDhwAvailable() then
        setupSonyDhw()
    end
end
```

Gated on `android.stylusDhwAvailable()`, so the same patched plugin still runs unchanged on Kobo, Kindle, and SDL builds.

## Verification

After install, with KOReader running on the DPT-CP1:

```sh
adb logcat | grep -E 'SonyDhw|EPD|Pencil'
```

Expected on plugin enable:

```
I/SonyDhw: SonyDhw available
I/EPD: Using Sony DPT (EPDHelper) driver
I/SonyDhw: addDhwArea Rect(...) penPx=... rot=0
I/Pencil: Sony DHW enabled screen=1404 x 1872 pen=3 rot=0
```

End-to-end latency drops from "annoying" to "indistinguishable from sony_draw."

## Caveats

- The two submodule commits (in `android-luajit-launcher` and `koreader-base`) live on **detached HEAD** in your local clone. KOReader's superproject pins specific revisions, so to make the build stick you either push the commits to a fork and bump the submodule SHA, or accept that a `git submodule update` will clobber them.
- Rotation is read once at `setupSonyDhw()`. Rotating mid-session needs a tear-down + re-setup. Out of scope for the initial port.
- `libSystemUtil.so` is **armeabi-v7a only** on the DPT-CP1. The launcher's `abiFilters` doesn't need to change because Sony only shipped 32-bit ARM.
