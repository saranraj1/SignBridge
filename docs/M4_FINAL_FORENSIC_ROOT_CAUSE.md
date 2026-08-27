# M4.5 Final Forensic Root-Cause Investigation & Diagnostic Suite

**Date**: 2026-08-27  
**Platform**: Android 16 / API 36 (iQOO / vivo I2214)  
**Pipeline**: CameraX → MediaPipe Tasks Hand Landmarker (21 landmarks) → Landmark Normalizer → Gesture Segmenter (with Manual Bypass) → 1-NN DTW & Resampled Euclidean Matcher

---

## 1. Executive Summary & Root-Cause Diagnosis

Through continuous physical telemetry and live logcat inspection on the physical iQOO device, we isolated the exact failure modes affecting gesture enrollment and recognition:

### Root Cause 1: Premature Gesture Truncation vs. Unbounded Stillness Loop
- **Initial failure (M4.5)**: Stillness window was set to 6 frames with threshold `0.028`. Natural mid-gesture pauses triggered early termination, capturing 8–9 frame fragments (`MMMMSSSSM`) during Teach Mode.
- **Over-correction failure**: Increasing the window to 15 frames (~1.5s) with threshold `0.018` caused the opposite failure. Natural tracking jitter at ~10 FPS frequently spikes to `0.019–0.022`, resetting the 15-frame counter constantly. As a result, the segmenter **never completed on stillness**, running for 90 frames (10+ seconds) until hitting the max frame cap:
  ```
  P1: protoFrames=90, protoDur=10561ms, motion=MMMMMMMMSMSSSSMSSSMMSSSSSSSSSSSMSSSMSSSMMMSMMMMSSMSSSSSMMSSSSMSSSSSSSMSMSSSSSSSSSSSSSSSSSS
  ```
  This filled prototypes with 80+ frames of resting hand noise, inflating DTW distances to 0.70–0.98.

### Root Cause 2: Normalization Cancellation on Arm/Spatial Translations
- MediaPipe hand normalization translates all coordinates relative to the **wrist** (`wrist = (0,0,0)`).
- If a gesture consists primarily of spatial hand translation (moving the hand across the camera view without finger articulation), normalized coordinate displacements are near zero.
- Instantaneous velocity computed exclusively on normalized landmarks failed to register global hand movement, causing gestures to remain in `IDLE`.

---

## 2. Forensic Diagnostic Suite Implementation

To prove the pipeline end-to-end without speculative threshold guesswork, the following diagnostic systems were implemented and verified across 76 automated unit tests and deployed to the device:

### Phase 1: Complete Data Pipeline Trace (`M4ForensicTrace`)
Logs complete metadata for every captured gesture:
- `SeqID`, `Frames`, `DurationMs`, `EndReason`
- Handedness and average hand scale
- Coordinate checksums: `Total`, `Frame[0]`, `Frame[N-1]`
- Min/Max coordinate bounding box: `X`, `Y`, `Z`
- Statistical velocity metrics: `min`, `max`, `mean`, `median`, `variance`
- Character-level motion profile (`S` for still, `M` for motion)

### Phase 2: Automated Self-Match Test (`M4ForensicSelfMatch`)
Whenever a profile is enrolled in Teach Mode, the app automatically executes:
1. `DTW(P1, P1)`, `DTW(P2, P2)`, `DTW(P3, P3)` $\rightarrow$ **Must be 0.000000**.
2. Intra-prototype distances: `DTW(P1, P2)`, `DTW(P1, P3)`, `DTW(P2, P3)`.
3. Disk serialization (`GestureProfileStorage.saveProfiles`).
4. Disk deserialization (`GestureProfileStorage.loadProfiles`).
5. Round-trip verification: `DTW(original P1, restored P1)` $\rightarrow$ **Must be 0.000000**.
6. `DTW(restored P1, restored P1)` $\rightarrow$ **Must be 0.000000**.

### Phase 3 & 4: Live Repeat & Segmentation Asymmetry (`M4ForensicAsymmetry`)
Maintains history of Teach prototypes ($P_1, P_2, P_3$) and Live captures ($L_1, L_2, L_3$) and outputs a side-by-side comparison table showing frame counts, durations, velocities, variances, and checksums.

### Phase 5: Idle Velocity Telemetry (`M4ForensicIdle`)
Logs real-time instantaneous velocity, motion start threshold, consecutive frame counter, and state transitions to verify whether gestures trigger `CAPTURING` promptly.

### Phase 6: Normalization Verification (`M4ForensicNorm`)
Verifies:
- Normalized wrist is $(0.000, 0.000, 0.000)$.
- Normalized Middle MCP distance is exactly $1.0000$.
- Scale is positive, finite, and non-zero.

### Phase 7: Handedness Cross-Check (`M4ForensicLiveRepeat`)
Compares dominant handedness of live gesture vs. prototype handedness and flags mismatches (e.g. Right hand live vs. Left hand prototype).

### Phase 8: Manual Bounded Recording Bypass (`btnManualRecord`)
Adds a dedicated UI button:
```
● MANUAL RECORD [BYPASS SEGMENTER]
```
- **Tap to Start**: Bypasses automatic motion onset detection and accumulates all normalized frames.
- **Tap to Stop**: Immediately bounds the sequence, trims settling noise, and passes it to DTW/Matcher.
- Enables complete diagnostic isolation between the segmentation layer and the recognition/DTW layer.

### Phase 9: DTW vs. Resampled Euclidean Bypass (`M4ForensicLiveRepeat`)
For every recognition evaluation, compares:
1. **DTW Normalized Distance** (variable length alignment)
2. **Resampled Euclidean Distance** (20-frame linear temporal interpolation)
3. **Pearson Trajectory Correlation** (landmark trajectory covariance)

### Phase 10 & 11: Strict UI State Reflection
The UI HUD strictly reflects the actual pipeline state:
- `NO_HAND` $\rightarrow$ "NO HAND FOUND" (Red badge & text)
- `HAND_DETECTED` $\rightarrow$ "HAND DETECTED" (Blue stabilization)
- `SEARCHING` $\rightarrow$ "SEARCHING" (Green resting state)
- `CAPTURING` $\rightarrow$ "CAPTURING..." (Cyan active state)
- `RECOGNIZING` $\rightarrow$ "RECOGNIZING..." (Yellow matching state)
- `RESULT_DISPLAY` $\rightarrow$ "MATCH: <label>" or "UNKNOWN" (Green/Orange)

---

## 3. Physical Acceptance Verification Steps

The diagnostic build is installed and running on **iQOO I2214**. Run the following verification protocol:

### Step 1: Self-Match & Teach Mode Test
1. Tap **TEACH NEW GESTURE**.
2. Tap **RECORD SAMPLE 1** (or use **MANUAL RECORD**).
3. Perform **HELP** and pause for 0.5s.
4. Verify "Sample 1 Captured! ✓" appears within 0.5s.
5. Repeat for Samples 2 and 3.
6. Enter name `HELP` and tap **SAVE GESTURE**.
7. Check logcat (`adb logcat -d -s M4ForensicSelfMatch:I`):
   - Verify `SELF-MATCH DTW(P1, P1) = 0.000000`.
   - Verify `PERSISTENCE ROUND-TRIP = 0.000000`.

### Step 2: Live Recognition Test (Phase 3 & 4)
1. Perform **HELP** 5 times.
2. Check logcat (`adb logcat -d -s M4ForensicLiveRepeat:I M4ForensicAsymmetry:I`):
   - Review the Asymmetry Table.
   - Review DTW distance vs. Resampled Euclidean distance.

### Step 3: Manual Bypass Isolation Test (Phase 8)
1. Tap **● MANUAL RECORD [BYPASS SEGMENTER]**.
2. Perform **HELP**.
3. Tap **■ STOP RECORDING & EVALUATE**.
4. Verify whether manual bounding produces immediate match.
