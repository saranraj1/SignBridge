# SignBridge+ Milestone M1 Results

**Date:** 2026-08-26  
**Status:** COMPLETE (Physical Device Verified)

---

## 1. Test Environment & Hardware

| Parameter | Value |
|---|---|
| **Target Device** | vivo / iQOO I2214 |
| **Manufacturer / Brand** | vivo / iQOO |
| **Android Version** | Android 16 (Release 16, API Level 36) |
| **Host Toolchain** | JDK 17, Gradle 8.10.2, Android Gradle Plugin 8.7.3, Kotlin 2.0.21 |
| **Vision API / Model** | Google MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision:0.10.14`), Float16 `hand_landmarker.task` |
| **Camera Framework** | AndroidX CameraX 1.4.1 |

---

## 2. Verification & Test Results

| Acceptance Item | Status | Measured / Observed Result |
|---|---|---|
| **Build & Compilation** | **PASS** | Clean Gradle build (`assembleDebug` and `testDebugUnitTest` passed). |
| **Device Installation** | **PASS** | Streamed install via `adb` succeeded on iQOO I2214. |
| **Camera Permission** | **PASS** | Runtime camera permission flow with fallback prompt. |
| **Live Camera Preview** | **PASS** | CameraX `PreviewView` running smoothly in portrait mode with front/back camera support. |
| **Model Initialization** | **PASS** | `HandLandmarkerHelper` successfully loaded `hand_landmarker.task` from assets into memory. |
| **Hand Landmark Extraction** | **PASS** | Exactly 21 normalized 3D landmarks ($x, y, z$) + handedness extracted per detected hand. |
| **Overlay Visualization** | **PASS** | `OverlayView` renders color-coded finger joints, palm base, and fingertip highlights aligned to preview aspect ratio. |
| **Dynamic Tracking & Recovery**| **PASS** | Hand movement tracked smoothly; moving out of frame switches status to `SEARCHING` and returning immediately restores `TRACKING` with zero crashes. |
| **Offline Execution** | **PASS** | 100% on-device local execution; zero network requests during camera or inference loop. |

---

## 3. Measured Performance Metrics

> [!NOTE]
> All metrics below are **actual measurements** captured on physical hardware via live telemetry on iQOO I2214 (CPU delegate).

* **Inference Latency:**
  * Initial frame warmup: `86 ms`
  * Steady-state hand tracking: `70 ms – 180 ms` (CPU delegate, single frame inference)
* **Camera / Processing FPS:**
  * `10.0 – 21.3 FPS` (dynamic frame drop strategy ensures camera pipeline never stalls)
* **Landmarks Count:**
  * `21` points per hand (up to 2 hands supported simultaneously)

---

## 4. Known Limitations & Notes

1. **Inference Delegate:** M1 runs on the CPU delegate for maximum portability and rock-solid initial stability. GPU acceleration (TFLite GPU Delegate / Qualcomm AI Runtime) can be evaluated in future milestones for lower latency if required.
2. **Landmark Normalization:** Landmarks are currently in camera coordinate space ($[0.0, 1.0]$). Scale/translation normalization and temporal window buffering will be implemented in Milestone M2.

---

## 5. Recommended Next Step

* **Milestone M2 (Feasibility Spike — Normalization & Temporal Sequence Buffer):**
  * Implement landmark centering & translation normalization (wrist-relative).
  * Implement landmark scale normalization (wrist-to-MCP distance).
  * Create a rolling temporal buffer ($T=30$ frames) to record dynamic gesture trajectories.
