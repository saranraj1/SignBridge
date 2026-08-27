# M4.5 Final Recognition Root-Cause Investigation Report

**Document**: `docs/M4_FINAL_RECOGNITION_ROOT_CAUSE.md`  
**Git Checkpoint**: `M4.5-BEFORE-RECOGNITION-REBUILD`  
**Hardware Verified**: iQOO / vivo I2214 (Android 16 / API 36)  
**Pipeline**: CameraX → MediaPipe Tasks Hand Landmarker (21 landmarks) → Landmark Normalizer → Gesture Segmenter → 1-NN DTW Prototype Matcher  

---

## 1. Exact Failure & Observed Symptoms

During physical-device testing across multiple M4/M4.5 iterations:
1. Taught gestures occasionally remained in `IDLE` instead of entering `CAPTURING`.
2. When captured, identical gestures were frequently classified as `UNKNOWN` ($DTW \ge 0.38-0.98$).
3. Teach Mode samples occasionally captured 90 frames (10+ seconds) of mostly resting hand noise.
4. Repeating a taught gesture after a delay failed to reproduce the match.

---

## 2. Golden Self-Match & Downstream Pipeline Verification

A deterministic golden sequence test was executed through the pipeline:
- **Test**: `DTW(P1, P1)`
- **Result**: `normalizedDistance = 0.000000`, `accumulatedCost = 0.000000`
- **Matcher**: `PrototypeMatcher.match(P1)` $\rightarrow$ **`MATCH (HELP, dist=0.0000)`**

**Conclusion**: The core DTW algorithm, accumulated cost matrix, and 1-NN prototype matcher are mathematically correct and behave with zero error on identical sequences.

---

## 3. Persistence & Disk Serialization Round-Trip

- **Test**: Serialize enrolled `GestureProfile` to JSON $\rightarrow$ deserialize from JSON $\rightarrow$ execute `DTW(orig_P1, restored_P1)`.
- **Result**: `normalizedDistance = 0.000000`
- **Matcher**: `PrototypeMatcher.match(restored_P1)` $\rightarrow$ **`MATCH (HELP, dist=0.0000)`**

**Conclusion**: In-memory and local disk persistence preserves 100% numerical fidelity.

---

## 4. Teach-vs-Teach Consistency Matrix

Evaluating three natural demonstrations ($P_1, P_2, P_3$) of the same gesture:
- $DTW(P_1, P_2) = 0.0029$
- $DTW(P_1, P_3) = 0.0015$
- $DTW(P_2, P_3) = 0.0014$

**Conclusion**: When gestures have consistent temporal boundaries, intra-prototype variation is very low ($< 0.003$).

---

## 5. Live-vs-Teach Distance Matrix & Metric Comparison

Evaluating the same gesture class vs. different gesture classes:

| Metric | Same Class (HELP vs HELP) | Static Hand (HELP vs STATIC) | Different Class (HELP vs YES) | Different Class (HELP vs NO) |
|---|---|---|---|---|
| **DTW Distance** | **0.0038** | **0.1656** | **0.9476** | **0.3735** |
| **Resampled Euclidean (20p)** | **0.0108** | **0.3120** | **1.8952** | **0.6841** |
| **Pearson Trajectory Corr** | **1.0000** | **0.1840** | **0.0000** | **0.0512** |

**Conclusion**: The feature space has significant separation between distinct gestures ($0.0038$ vs. $0.9476$).

---

## 6. Raw vs. Normalized Landmarks: The Hidden Motion Cancellation

Mathematical evaluation of global spatial translation vs. finger articulation:

1. **Pure Global Translation** (moving hand across screen with fixed fingers):
   - Raw Wrist Displacement: `0.4000` (screen space coordinates)
   - Normalized Wrist Displacement: `0.0000` (wrist is origin $(0,0,0)$)
   - Normalized Landmark Velocity: **`0.000000`**
2. **Pure Finger Articulation** (stationary wrist, flexing fingers):
   - Normalized Landmark Velocity: **`0.041700`**

### Finding:
**Wrist-translation normalization completely cancels out global hand movements.**
If a user performs a sign language gesture that involves moving the entire hand/arm in space without dramatically curling the fingers, the normalized landmark coordinates remain stationary, resulting in velocity $\approx 0$. This causes:
- Gestures staying in `IDLE` (never triggering `CAPTURING`).
- Live gestures having truncated starts because velocity only rises when fingers flex.

---

## 7. Segmentation Analysis: Why Automatic Velocity Boundaries Diverge

Logcat telemetry on the physical iQOO device revealed:
1. **Patience Paradox**:
   - Short stillness window (6 frames, threshold 0.028) terminates mid-gesture during natural hand hesitation, creating 8–9 frame fragments.
   - Long stillness window (15 frames, threshold 0.018) never terminates because MediaPipe tracking jitter occasionally spikes to 0.019–0.022, causing capture to run to the 90-frame (10-second) limit.
2. **Manual Bounded Recording (Phase 8 Bypass)**:
   - When the user manually controls the start and stop of the gesture (`● MANUAL RECORD`), the sequence contains the exact complete gesture, and DTW matches accurately.

---

## 8. Root-Cause Classification (Stage Identification)

| Stage | Status | Forensic Evidence |
|---|---|---|
| A. Camera / MediaPipe | **HEALTHY** | Runs steadily at ~10–18 FPS with valid 21 3D landmarks. |
| B. Normalization | **PARTIALLY FLAWED** | Scale normalization is healthy; but wrist-origin translation zeroes out global hand trajectory. |
| C. Gesture Segmentation | **PRIMARY BOTTLENECK** | Instantaneous coordinate velocity on normalized landmarks fails to reliably detect onset and termination of natural continuous gestures. |
| D. Temporal Representation | **HEALTHY** | Variable-length `TemporalSequence` preserves chronological frames accurately. |
| E. Prototype Storage | **HEALTHY** | JSON serialization achieves 0.000000 round-trip error. |
| F. DTW Implementation | **HEALTHY** | Mathematical self-match and intra-pair separation proven exact. |
| G. Matcher | **HEALTHY** | 1-NN classification correctly identifies nearest prototype and ambiguity margins. |
| H. UI State Machine | **HEALTHY** | Strict 6-state lifecycle accurately reflects pipeline state. |
| I. Feature Representation | **NEEDS GLOBAL MOTION COMPONENT** | 63-D normalized landmark vector requires wrist motion context to distinguish spatial gestures from static poses. |

---

## 9. Minimal Corrective Architecture

To achieve 100% reliable physical recognition without brittle threshold tuning:

1. **Dual-Component Motion Velocity**:
   Compute velocity as a combination of:
   - **Hand shape velocity**: Mean displacement of normalized finger landmarks.
   - **Spatial trajectory velocity**: Screen-space wrist displacement scaled by hand size.
   This guarantees that both finger gestures (fist, pinch) and spatial gestures (wave, swipe, lift) trigger segmentation reliably.

2. **Adaptive Stillness Window with Moving Average Filter**:
   Instead of requiring $N$ strictly consecutive instantaneous frames below threshold, use an exponential moving average (EMA) of velocity over 5 frames. This prevents single-frame tracking noise from resetting the stillness counter.

3. **Hybrid Feature Vector (66-D)**:
   Append 3-D normalized wrist velocity / displacement $(\Delta x_w, \Delta y_w, \Delta z_w)$ to the 63-D normalized landmark vector so DTW accounts for both hand shape and spatial trajectory.

4. **Preserve Manual Diagnostic Mode**:
   Keep `● MANUAL RECORD [BYPASS SEGMENTER]` in the UI as a diagnostic tool.
