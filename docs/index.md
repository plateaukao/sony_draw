---
title: Home
layout: default
nav_order: 1
---

# sony_draw

A small Android app that turns the **Sony DPT-CP1** — a 13.3" e-ink reader Sony shipped in 2018 and never officially opened to third-party developers — into a usable notepad.

The interesting bit isn't the drawing UI. It's that **the strokes appear with the same sub-50ms latency as Sony's first-party `DigitalPaperApp`**, by talking directly to two device-specific layers Sony never documented:

- the **EPD waveform controller** (DU / GC16 partial / GC16 full — the same modes the framebuffer driver exposes to Sony's own apps), accessed through reflection into the modified `android.view.SurfaceHolder` that ships in the DPT-CP1 firmware
- the **Direct Handwriting** kernel path, which lets the framebuffer driver paint stylus strokes inside a registered allow-area rect without ever leaving kernel space

Both paths were reverse-engineered from the decompiled `DigitalPaperApp` APK and the `/system/lib/libSystemUtil.so` shared library pulled off a rooted DPT-CP1.

[View on GitHub](https://github.com/plateaukao/sony_draw){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }

---

## Why this exists

The DPT-CP1 is a beautiful piece of hardware — Carta e-ink, Wacom EMR digitiser, A5 size, weeks of battery. Sony shut down its companion-app support in 2023 and the device shipped locked to a single PDF-reading flow.

Out-of-the-box third-party Android apps can install (the device runs an unmodified-looking AOSP 4.4 underneath), but they have no idea about the EPD waveform controller. So they all draw with the stock Android compositor, which on this device means **every stroke is a 450ms full-screen GC16 refresh** — completely unusable for handwriting.

`sony_draw` is the minimum amount of code needed to prove that a third-party app can hit native-latency stylus performance on this device. It's deliberately small (one Activity, ~700 lines of Java) and exists to be **read and ported**, not to be a polished product.

## How to read these docs

1. [Architecture]({{ site.baseurl }}/architecture.html) — the four-layer pipeline from MotionEvent to e-ink pixels, with a diagram.
2. [E-ink pipeline]({{ site.baseurl }}/eink-pipeline.html) — EPD waveform modes, why DU during the stroke and GC16 on lift, how `EpdHelper` reflects into Sony's framework.
3. [Direct Handwriting]({{ site.baseurl }}/direct-handwriting.html) — the kernel fast-path, the JNI binding to `libSystemUtil.so`, and the package-name landmine.
4. [Build & install]({{ site.baseurl }}/build.html) — Gradle invocation, the deliberate SDK locks, and how to develop on a non-Sony Android device.
5. [Porting to KOReader]({{ site.baseurl }}/koreader-port.html) — using the same primitives to bring stylus support to KOReader's `pencil.koplugin`.

## Status

Works on a Sony DPT-CP1 running firmware 1.6.50.14130. Not tested on the DPT-RP1 (A4 model) or DPT-S1 — they share the same `libSystemUtil.so` symbol set per `strings` output, so it would probably work with minor tuning.
