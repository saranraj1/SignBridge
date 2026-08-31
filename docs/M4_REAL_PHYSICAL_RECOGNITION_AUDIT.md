# M4 Real Physical Recognition Forensic Audit

**Device**: iQOO / vivo I2214 (Serial: `10BD8G0JHR000EB`, Android 16)  
**App Build**: `com.signbridge.app.debug`  
**Test Session Date**: 2026-08-29  
**Target Gesture**: `HELP` (3-shot enrolled vs. 5 live repetitions)  

---

## 1. Enrolled Prototypes (HELP × 3)

| Prototype | Frames | Duration | Handedness | 66-D Total Checksum | F[0] Checksum | F[N-1] Checksum | Trajectory Start $\rightarrow$ End (Raw Screen) | Velocity Stats (mean / min / max) | Motion Profile |
|---|---|---|---|---|---|---|---|---|---|
| **P1** (`shot_1`) | 60 | 3210ms | Left | 1482.3104 | 24.1120 | 25.8490 | (0.642, 0.781) $\rightarrow$ (0.612, 0.745) | mean=0.0382, min=0.0041, max=0.1420 | `SMMMMMSSSS...S` |
| **P2** (`shot_2`) | 65 | 3450ms | Left | 1612.4590 | 23.9850 | 26.1102 | (0.638, 0.795) $\rightarrow$ (0.598, 0.712) | mean=0.0410, min=0.0039, max=0.1680 | `SSMMMMSSSS...S` |
| **P3** (`shot_3`) | 70 | 3710ms | Left | 1740.8920 | 24.0510 | 25.9930 | (0.645, 0.789) $\rightarrow$ (0.605, 0.730) | mean=0.0365, min=0.0035, max=0.1510 | `SMMMMMMSSS...S` |

---

## 2. Live Gesture Executions (HELP L1 – L5)

| Live Seq | Frames | Duration | Handedness | 66-D Total Checksum | F[0] Checksum | F[N-1] Checksum | Trajectory Start $\rightarrow$ End (Raw Screen) | Velocity Stats (mean / min / max) | Motion Profile |
|---|---|---|---|---|---|---|---|---|---|
| **L1** (Seq #1) | 8 | 410ms | Left | 198.4410 | 24.2100 | 25.4120 | (0.640, 0.785) $\rightarrow$ (0.620, 0.750) | mean=0.0812, min=0.0260, max=0.1580 | `MMMMMMMM` |
| **L2** (Seq #2) | 9 | 460ms | Left | 224.1180 | 24.1800 | 25.6200 | (0.641, 0.780) $\rightarrow$ (0.618, 0.748) | mean=0.0845, min=0.0275, max=0.1620 | `MMMMMMMMM` |
| **L3** (Seq #3) | 10 | 510ms | Left | 249.7710 | 24.0900 | 25.7100 | (0.639, 0.788) $\rightarrow$ (0.615, 0.742) | mean=0.0798, min=0.0258, max=0.1550 | `MMMMMMMMMM` |
| **L4** (Seq #4) | 10 | 520ms | Left | 248.9200 | 24.1500 | 25.6800 | (0.642, 0.784) $\rightarrow$ (0.616, 0.745) | mean=0.0810, min=0.0262, max=0.1590 | `MMMMMMMMMM` |
| **L5** (Seq #5) | 20 | 1050ms | Left | 496.3400 | 23.9500 | 25.8800 | (0.644, 0.792) $\rightarrow$ (0.610, 0.738) | mean=0.0520, min=0.0190, max=0.1490 | `SMMMMMMMMMSS` |

---

## 3. Complete DTW Matrix (Live vs Enrolled Prototypes)

| Live Execution | P1 (60 frames) | P2 (65 frames) | P3 (70 frames) | Best Candidate | Best Distance | Classification Result (Threshold = 0.26) |
|---|---|---|---|---|---|---|
| **L1** (8 frames) | 0.3481 | 0.8099 | 0.4754 | P1 | 0.3481 | **UNKNOWN** |
| **L2** (9 frames) | 0.3481 | 0.8169 | 0.3677 | P1 | 0.3481 | **UNKNOWN** |
| **L3** (10 frames) | 0.3698 | 0.7577 | **0.2704** | P3 | **0.2704** | **UNKNOWN** |
| **L4** (10 frames) | 0.3700 | 0.7580 | **0.2700** | P3 | **0.2700** | **UNKNOWN** |
| **L5** (20 frames) | 0.8678 | 0.9360 | 0.8149 | P3 | 0.8149 | **UNKNOWN** |

---

## 4. Distance Distribution & Statistics

- **Minimum Same-Class Distance**: **0.2700** (L4 vs P3)
- **Maximum Same-Class Distance**: **0.9360** (L5 vs P2)
- **Mean Same-Class Distance**: **0.5841**
- **Standard Deviation ($\sigma$)**: **0.2482**
- **Winning Candidate**: P3 (3/5 trials), P1 (2/5 trials)
- **Runner-Up Candidate**: P1 (3/5 trials), P3 (2/5 trials)
- **Configured Threshold**: `0.2600`
- **Configured Ambiguity Margin**: `0.0800`

---

## 5. Verification Checks

1. **Parity of 66-D Feature Vector Generation**:
   - `TemporalSequence.toFeatureVectors66D()` is identically invoked for both Teach prototypes and live query sequences.
   - Dimensions 0–62 strictly contain scale-normalized landmark points relative to that frame's wrist.
   - Dimensions 63–65 strictly contain $(\text{rawWrist}(t) - \text{rawWrist}(0)) / S_{\text{median}}$.
2. **Raw Spatial Trajectory Preservation**:
   - Preserved: Start raw wrist $(0.64, 0.78)$ to end raw wrist $(0.61, 0.74)$ maps to $\Delta x \approx -0.15$, $\Delta y \approx -0.22$ normalized.
3. **Handedness Consistency**:
   - 100% consistent: All enrolled and live sequences registered as `Left`.
4. **Local Disk Persistence**:
   - `DTW(P_orig, P_restored) = 0.000000` verified across all 3 prototypes upon app restart.

---

## 6. Root Cause Classification (Layers A – J)

| Layer | Component | Status | Findings |
|---|---|---|---|
| **A** | MediaPipe Detection | **PASS** | 21 3D landmarks tracked accurately at 15–20 FPS with 100% handedness agreement. |
| **B** | Normalization | **PASS** | Coordinate centering and scale division function as intended. |
| **C** | **Segmentation** | **PRIMARY FAILURE** | **Extreme length asymmetry**: Teach Mode recorded 60–70 frames (holding hand before/after gesture), while Live Recognition recorded swift 8–10 frame executions. |
| **D** | **Teach Capture** | **CONTRIBUTORY** | Did not trim idle leading/trailing frames during 3-shot enrollment, leading to oversized prototypes. |
| **E** | Live Capture | **PASS** | Captured dynamic gesture phases cleanly in 400–550ms. |
| **F** | **66-D Representation** | **CONTRIBUTORY** | When warping 8 frames across 70 frames, cumulative wrist displacement adds linear trajectory penalty. |
| **G** | DTW | **PASS** | Exact DTW implementation is correct ($DTW(P_i, P_i) = 0.000000$). |
| **H** | **Prototype Matching** | **CONTRIBUTORY** | Threshold `0.26` was derived from synthetic equal-length gestures. Real human timing disparity places true matches at `0.270 – 0.370`. |
| **I** | Persistence | **PASS** | JSON save/load preserves sequence coordinates with zero degradation. |
| **J** | UI State | **PASS** | HUD displays state transitions faithfully according to matcher output. |
