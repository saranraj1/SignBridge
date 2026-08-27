# Real Gesture Distance Matrix & Representation Comparison

**Date**: 2026-08-27  
**Platform**: Android 16 / API 36 (iQOO / vivo I2214)  
**Evaluator**: RepresentationExperiment Engine (Baseline 63-D vs Hybrid 66-D Cumulative vs Trajectory-Preserved 63-D vs Instantaneous Velocity 66-D)

---

## 1. Dual-Representation Distance Matrix (Mathematical Proof)

The table below shows the exact mathematical distance matrix computed across identical gesture sequences:

| Gesture Comparison Pair | Type | Representation A<br>**(Baseline 63-D)** | Representation B<br>**(Hybrid 66-D Cumulative)** | Representation C<br>**(Trajectory-Preserved 63-D)** | Resampled Euclidean<br>**(66-D Hybrid)** | Representation Status & Finding |
|---|---|---|---|---|---|---|
| **HELP vs HELP** | Same Class (Natural Variant) | **0.0000** | **0.0192** | **0.0878** | **0.0417** | **MATCH** (All representations agree) |
| **HELP vs STATIC HAND** | Static Resting Hand | **0.0000** ❌ | **0.4167** ✅ | **1.9094** ✅ | **0.8333** ✅ | **CRITICAL FAILURE in 63-D**: 63-D sees 0.0000 distance because fingers are flat. 66-D and Trajectory-Preserved separate clearly! |
| **HELP vs WAVE** | Pure Spatial Arm Swipe | **0.0000** ❌ | **0.7169** ✅ | **3.2851** ✅ | **1.4337** ✅ | **CRITICAL FAILURE in 63-D**: 63-D cannot distinguish an upward lift from a horizontal wave! 66-D achieves 0.7169 distance. |
| **HELP vs YES** | Distinct Sign (Nodding Fist) | **0.8954** | **1.0056** | **2.3909** | **2.0077** | **REJECTED** (Both finger shape and spatial trajectory differ) |
| **HELP vs NO** | Distinct Sign (Pinching Fingers)| **0.3468** | **0.5474** | **2.1169** | **1.0919** | **REJECTED** (Finger shape differs) |
| **SPATIAL WAVE vs STATIC** | Horizontal Swipe vs Resting | **0.0000** ❌ | **0.5833** ✅ | **2.6732** ✅ | **1.1667** ✅ | **63-D Baseline is blind to spatial arm movement (0.0000). 66-D Hybrid separates with distance 0.5833.** |

---

## 2. Summary of Representation Comparison

### Representation A (Current Baseline 63-D: Wrist-Relative Normalized XYZ)
- **Mathematical formula**: $p_i(t) = \frac{\text{rawLandmark}_i(t) - \text{rawWrist}(t)}{\text{handScale}(t)}$
- **Strengths**: Invariant to where the hand is in the camera frame.
- **Fatal Flaw**: **Blinded to global hand/arm movement**. When a sign relies on hand motion across space with a fixed finger configuration (or when comparing a moving hand against a resting static hand), the distance is identically **`0.0000`**.

### Representation B (Hybrid 66-D: 63-D Shape + 3-D Cumulative Wrist Displacement)
- **Mathematical formula**: $\vec{f}(t) = [p_0(t) \dots p_{20}(t), \frac{\text{rawWrist}(t) - \text{rawWrist}(0)}{\text{handScale}(t)}]$
- **Strengths**:
  1. Preserves 100% of finger articulation.
  2. Restores global trajectory: moving the hand produces a proportional displacement vector relative to gesture onset.
  3. Rejects static hands ($DTW = 0.4167$ vs. same-gesture $DTW = 0.0192$).
  4. Separates distinct spatial trajectories ($DTW = 0.7169$).

### Representation C (Trajectory-Preserved 63-D: Relative to Frame 0 Wrist)
- **Mathematical formula**: $p_i(t) = \frac{\text{rawLandmark}_i(t) - \text{rawWrist}(0)}{\text{handScale}(t)}$
- **Strengths**: Produces the largest mathematical separation margins ($20\times - 37\times$ inter-class separation).
