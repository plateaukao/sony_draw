# Bringing sony_draw's stylus path to KOReader's pencil.koplugin

End result: on a Sony DPT-CP1 the pencil.koplugin draws and erases at the
same speed as the standalone `sony_draw` app — kernel-level Direct
Handwriting (DHW) for pen, DU refresh during the stroke, GC16 partial
refresh on lift.

The work spans **three repos**. Two are committed; one is a patch file in
this directory.

## Layout

| Repo (in your tree) | Status | Commit |
|---|---|---|
| `~/src/koreader/platform/android/luajit-launcher` (submodule) | committed locally | `f744b56` "Sony DPT EPD waveform + Direct Handwriting support" |
| `~/src/koreader/base` (submodule) | committed locally | `250ae579` "input_android: emit ABS_MT_TOOL_TYPE per pointer" |
| `pencil.koplugin` (3rd-party, not in your tree) | patch only | `pencil-sony-android.patch` (this directory) |

## What each piece does

### android-luajit-launcher (`f744b56`)

Adds Sony-specific EPD + DHW infrastructure:

- `app/src/main/jniLibs/armeabi-v7a/libSystemUtil.so` — pulled from the DPT-CP1's `/system/lib/`. The shared library that talks to the EPDC framebuffer driver's DHW ioctls.
- `app/src/main/java/com/sony/infras/dp_libraries/systemutil/SystemUtil.java` — the JNI binding stub. The package path matches the string `libSystemUtil.so` passes to `jniRegisterNativeMethods` (verified with `strings` on the .so), so `JNI_OnLoad` succeeds.
- `device/sony/SonyDhw.kt` — reflection-based wrapper over `SystemUtil.getEpdUtilInstance()`. Lazy and fail-soft so the launcher still compiles for non-Sony Android.
- `device/epd/sony/SonyEPDController.kt` — implements `EPDInterface` using `View.invalidate(Rect, int updateMode)` (the overload Sony's modified framework exposes; verified in decompiled `EPDHelper.java`). Falls back to NTX-style `postInvalidateDelayed` if not present.
- `EPDFactory.kt` — routes `DeviceInfo.Id.SONY_CP1` to the new controller (was Nook/NTX, which uses the wrong invalidate overload for DPT).
- `LuaInterface.kt` + `MainActivity.kt` — five new JNI-exposed methods:
  ```
  stylusDhwAvailable() : Boolean
  stylusDhwEnable() / stylusDhwDisable()
  stylusDhwSetArea(x, y, w, h, penWidthPx, rotationDeg)
  stylusDhwClearArea()
  ```
- `assets/android.lua` — the matching `android.stylusDhw*` Lua functions, plus FFI cdefs for `AMotionEvent_getToolType`, `AMotionEvent_getButtonState`, and `AMOTION_EVENT_TOOL_TYPE_*` constants (needed by koreader-base).

### koreader-base (`250ae579`)

`ffi/input_android.lua` now calls `AMotionEvent_getToolType` per pointer and synthesizes an `ABS_MT_TOOL_TYPE` event before the position events. Translation matches the Elan-panel convention KOReader and the pencil plugin already share:

- Android `TOOL_TYPE_STYLUS` (2) → ABS event value 1 (pen)
- Android `TOOL_TYPE_ERASER` (4) → ABS event value 2 (eraser)
- everything else → 0 (finger)

After this, `frontend/device/input.lua` sets `slot.tool` on Android the same way it does on Kobo. The pencil plugin's `routeStylusEvents` (and its tool-based eraser detection) now works without further changes.

### pencil-sony-android.patch

Adds DHW activation around the plugin's stylus callback lifecycle:

- `setupStylusCallback()` → after registering, calls `setupSonyDhw()` which (only on Sony Android) registers the screen rect as the allow-area and enables DHW.
- `teardownStylusCallback()` → calls `teardownSonyDhw()` to clear the area and disable DHW.

All gated on `android.stylusDhwAvailable()`, so the patched plugin still runs on Kobo / Kindle / SDL unchanged.

## Build & install

You need a working KOReader Android build environment (Android SDK + NDK + Java 17). If you don't already build KOReader:

```sh
cd ~/src/koreader
./kodev fetch-thirdparty   # pulls all submodules
./kodev build android      # outputs apk under build/
adb install -r build/.../koreader-debug.apk
```

The two submodule commits are local-only (`HEAD` is detached). After the build verifies, push them to forks or pin the submodule SHAs in the koreader superproject.

### Plugin install

```sh
# clone pencil.koplugin fresh and apply the patch
git clone https://github.com/mysticknits/pencil.koplugin /tmp/pencil
cd /tmp/pencil
git apply ~/src/sony_draw/patches/pencil-sony-android.patch

# install on the device (KOReader on Android typically sees plugins from
# the koreader install dir under /sdcard)
adb push pencil.koplugin /sdcard/koreader/plugins/
adb push input.lua /sdcard/koreader/frontend/device/input.lua
```

(The plugin's README explains the `input.lua` replacement; the patch leaves it untouched because nothing in it is Kobo-incompatible on Android once `slot.tool` is set.)

## Verifying performance

After install, with KOReader running on the DPT-CP1:

```sh
adb logcat | grep -E 'SonyDhw|EPD|Pencil'
```

Expected on plugin enable:
```
I/SonyDhw: SonyDhw available
I/EPD: Using Sony DPT (EPDHelper) driver
I/SonyDhw: addDhwArea Rect(...) penPx=... rot=0
I/Pencil: Sony DHW enabled screen= 1404 x 1872 pen= 3 rot= 0
```

The kernel paints stylus strokes inside the DHW rect with the same sub-50ms latency as `sony_draw`. The plugin still receives MotionEvents (so persistent stroke storage + undo + bookmark grouping all work); on pen-up, `Screen:refreshFast` triggers a DU partial via `SonyEPDController` and the persisted ink overwrites the kernel's temporary pixels.

## Caveats

- The android-luajit-launcher commit is on a **detached HEAD** because the koreader superproject pins a specific revision. Two ways to handle that:
  1. Push the commit to a fork of the launcher, then bump the submodule SHA in the koreader superproject to that fork's commit.
  2. Just keep the local commit; build will pick it up. But submodule update would clobber it.
- Same applies to the koreader-base commit.
- `libSystemUtil.so` is **armeabi-v7a only** (matches the DPT-CP1's CPU). The launcher's `abiFilters` doesn't need to change because Sony only ships this device on 32-bit ARM.
- Rotation handling: `setupSonyDhw` reads `Screen:getRotationMode()` once on registration. If the user rotates the device mid-session, `teardownSonyDhw` + `setupSonyDhw` would need to be re-run. Out of scope for v1.
