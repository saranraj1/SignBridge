# SignBridge+ Milestone M2 Results

**Date:** 2026-08-26  
**Status:** COMPLETE (Physical Device Verified)

---

## 1. Scope

Milestone M2 implements the mathematical preprocessing and temporal sequence foundation for SignBridge+:
$$\text{Raw MediaPipe Landmarks (21 points)} \longrightarrow \text{Translation \& Scale Normalization} \longrightarrow \text{30-Frame Rolling Temporal Buffer}$$

---

## 2. Normalization Details

### Translation Normalization
- **Reference Landmark:** Wrist joint (`landmark 0`).
- **Transformation:**
  $$\Delta x_i = x_i - x_0, \quad \Delta y_i = y_i - y_0, \quad \Delta z_i = z_i - z_0$$
- **Outcome:** The wrist landmark $0$ is mapped directly to origin $(0.0, 0.0, 0.0)$, making landmark coordinates invariant to where the hand appears within the camera field of view.

### Scale Normalization
- **Reference Landmark Pair:** Wrist (`landmark 0`) to Middle-Finger Metacarpophalangeal joint (`landmark 9`).
- **Scale Factor Calculation:**
  $$\text{scale} = \sqrt{(x_9 - x_0)^2 + (y_9 - y_0)^2 + (z_9 - z_0)^2}$$
- **Anatomical Justification:** The metacarpal bone structure between the wrist and the base of the middle finger is rigid and anatomically invariant to finger articulation, flexing, or gesturing.
- **Transformation:**
  $$\hat{x}_i = \frac{\Delta x_i}{\text{scale}}, \quad \hat{y}_i = \frac{\Delta y_i}{\text{scale}}, \quad \hat{z}_i = \frac{\Delta z_i}{\text{scale}}$$
- **Outcome:** Normalizes for hand distance from camera; identical hand postures at 30cm and 80cm yield comparable spatial coordinates.

### Safety & Coordinate Assumptions
- Uses normalized coordinate space $[0.0, 1.0]$ provided by MediaPipe Tasks Vision.
- Rejects degenerate hands with $\text{scale} < 10^{-5}$ or non-finite values ($\text{NaN} / \pm\infty$) safely by returning `null` without throwing exceptions or division-by-zero errors.

---

## 3. Temporal Buffer Details

- **Default Window Size:** $N = 30$ frames (`DEFAULT_TEMPORAL_WINDOW_SIZE = 30` in `GestureConfig`).
- **Rolling FIFO Behavior:** Once capacity of 30 frames is reached, adding frame $t+1$ automatically evicts the oldest frame $t-29$ (sliding window).
- **Primary-Hand Policy:** When hands are detected, the primary hand (highest detection score) is normalized and buffered.
- **Invalid / Missing Frame Policy:** If no hand is present or normalization fails, `null` is handled safely without inserting bogus/interpolated frames into the buffer.
- **Snapshots:** `getSnapshot()` returns an immutable copy of the chronological sequence (`TemporalSequence`), preventing race conditions or external mutations.

---

## 4. Automated Unit Tests

All 13 automated unit tests executed and passed (`13/13 PASS`, 0 failures):

| Test Suite | Test Case | Purpose | Status |
|---|---|---|---|
| `LandmarkNormalizerTest` | `testTranslationNormalization_wristAtOrigin` | Wrist landmark 0 becomes $(0,0,0)$ | **PASS** |
| `LandmarkNormalizerTest` | `testScaleNormalization_scaleInvariance` | $2\times$ scale produces invariant normalized coordinates | **PASS** |
| `LandmarkNormalizerTest` | `testZeroScaleHandling_noNaNOrCrash` | Degenerate hand with zero scale safely returns `null` | **PASS** |
| `LandmarkNormalizerTest` | `testInvalidLandmarkCount_rejected` | Empty vision frame rejected cleanly | **PASS** |
| `TemporalBufferTest` | `testBufferInitiallyEmpty` | Initial buffer size is 0, status is `EMPTY`, `isReady = false` | **PASS** |
| `TemporalBufferTest` | `testBufferFillsToCapacity` | Sequential frames transition status from `FILLING` to `READY` at frame 30 | **PASS** |
| `TemporalBufferTest` | `testRollingBufferDiscardsOldest` | Frame 31 maintains size 30 and evicts oldest frame | **PASS** |
| `TemporalBufferTest` | `testSnapshotImmutability` | Subsequent buffer mutations do not modify previous snapshot | **PASS** |
| `TemporalBufferTest` | `testNullFrameRejected` | Missing/null frames do not enter or corrupt buffer | **PASS** |
| `TemporalBufferTest` | `testTimestampChronologicalOrdering` | Frames in sequence are strictly ordered chronologically | **PASS** |
| `LandmarkDataTest` | `testEmptyVisionFrameResult` | Tests empty raw vision result model | **PASS** |
| `LandmarkDataTest` | `testSingleHandVisionFrameResult` | Tests single hand raw vision result model | **PASS** |
| `LandmarkDataTest` | `testInvalidLandmarksCountThrows` | Tests invalid landmark count enforcement | **PASS** |

---

## 5. Physical Device Verification

| Parameter | Value |
|---|---|
| **Target Device** | vivo / iQOO I2214 |
| **Android Version** | Android 16 (API 36) |
| **Observed Camera / Processing FPS** | `6.2 – 20.0 FPS` (MediaPipe CPU delegate) |
| **Observed Inference Latency** | `90 – 248 ms` (CPU single-frame inference) |
| **Buffer Behavior** | Buffers from `0/30 (EMPTY)` $\to$ `1–29/30 (FILLING)` $\to$ `30/30 (READY)` in ~1.5–3 seconds of steady hand presentation. |
| **Motion Stability** | Hand translation and distance changes maintain smooth normalized tracking without crashes or numerical instability. |

---

## 6. Known Limitations

1. **No DTW / Recognition:** M2 strictly implements normalization and sequence buffering; gesture classification/matching is not yet active.
2. **No Few-Shot Enrollment:** "Teach Mode" and prototype creation are deferred to later milestones.
3. **Single-Hand Focus:** M2 processes the primary hand only. Dual-hand synchronization is deferred to future milestones.
4. **Existing M1 Performance Baseline:** MediaPipe inference on the CPU delegate operates at ~6–20 FPS on Android 16. Fast hand movement experiences frame lag inherent to M1's CPU inference speed.

---

## 7. Recommended Next Milestone

* **Milestone M3 (Dynamic Time Warping Baseline):**
  * Implement Dynamic Time Warping (DTW) distance metric between two 30-frame temporal sequences.
  * Implement 1-nearest-neighbor sequence matching against stored example gestures.
  * Evaluate recognition latency and distance thresholds on normalized temporal sequences.
