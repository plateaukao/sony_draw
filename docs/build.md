---
title: Build & install
layout: default
nav_order: 5
---

# Build & install
{: .no_toc }

1. TOC
{:toc}

---

## Prerequisites

- JDK 17 (Android Gradle Plugin 8.x requires it)
- Android SDK with platform 33 installed
- `adb` on `$PATH` if you want to install to a device

The build does **not** need the Android NDK. There's no native code in the APK; the only `.so` it ever uses (`libSystemUtil.so`) lives on the target device's own `/system/lib/` and is loaded at runtime — see [Direct Handwriting]({{ site.baseurl }}/direct-handwriting/#loading-without-bundling-the-binary).

## Building

```sh
git clone https://github.com/plateaukao/sony_draw.git
cd sony_draw
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~50KB; entirely Java + resources, no JNI libs).

## Installing on a DPT-CP1

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.maoyuankao.sonydraw/.MainActivity
```

Then, in another terminal, watch the relevant logs:

```sh
adb logcat -c       # clear
adb logcat | grep -E 'SystemUtil|StylusView|DirectHandwriting|EpdHelper|RenderingThread'
```

A healthy launch produces, in order:

```
I/SystemUtil: Loaded /system/lib/libSystemUtil.so
I/StylusView: surfaceCreated
I/StylusView: surfaceChanged 1404x1872
I/EpdHelper: EPD reflection: lockCanvas(int)=true lockCanvas(Rect,int)=true invalidate(Rect,int)=true
I/DirectHandwriting: addDhwArea Rect(0, 0 - 1404, 1872) width=2 rot=0 id=0
```

If `SystemUtil: Loaded` is missing, the kernel fast path won't be active — strokes will still appear via the Java path, but with ~120ms latency instead of ~30ms.

## Why the SDK versions look outdated

`app/build.gradle`:

```groovy
minSdk 22
targetSdk 22
compileSdk 33
```

These are deliberate. The DPT-CP1's firmware is Android 5.1.1 (API 22), and:

- **`targetSdk 22`** keeps us in pre-runtime-permission, pre-SAF behaviour. Bumping it would not just change UI affordances — it would also enable linker namespace isolation (API 24+), which would block the `/system/lib/libSystemUtil.so` load entirely.
- **`compileSdk 33`** is fine because we only call APIs that exist on the device. Compiling against a newer SDK gives access to better tooling without changing runtime behaviour.
- **`minSdk 22`** matches `targetSdk` since this app is single-device.

Do not bump `targetSdk` to "fix" any deprecation warnings. The deprecation warnings are mostly about `Region.Op.REPLACE` in `InkStrokeEditor` — which is exactly what we need, see [Architecture]({{ site.baseurl }}/architecture/#things-that-look-weird-but-are-load-bearing).

## Developing without a DPT-CP1

The same APK runs on any Android 5.1+ device (or emulator). On non-Sony hardware, two things degrade silently:

1. **`EpdHelper` reflection finds no Sony overloads.** `lockCanvas(Rect, int updateMode)` does not exist on `android.view.SurfaceHolder`, so the calls fall back to plain `holder.lockCanvas()`. The compositor refreshes at normal speed, which on an LCD is fine but obviously not on e-ink.
2. **`SystemUtil.isAvailable()` returns false.** No DHW. `DirectHandwriting.enable()` / `setAllowArea()` become no-ops. Stroke rendering still works through the Java path.

This is useful for:

- Iterating on stroke geometry (`InkStrokeEditor.drawSegment` — the tapered quad+circle math)
- Testing input handling (stylus vs eraser detection, historical-sample replay)
- Profiling Java-side allocations during a stroke

It is **not** useful for any latency measurement. There is no substitute for the device.

## What's not here

This project deliberately ships no:

- Test suite. There's no straightforward way to test "did the panel refresh in DU mode" without a device.
- Lint config. The deprecation warnings are intentional (see above) and there's nothing useful to enforce.
- CI. The build is small enough that "I built it locally" is sufficient verification.
- Release build type. `app/build.gradle` only defines `debug`. If you need a release build, add it — there's nothing exotic in the manifest.

The whole project is ~700 lines of Java in one Activity. It's meant to be read end-to-end in 20 minutes, not to grow features.
