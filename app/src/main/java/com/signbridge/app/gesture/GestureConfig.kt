package com.signbridge.app.gesture

/** Central configuration for the on-device personalized gesture recognizer. */
object GestureConfig {
    const val DEFAULT_TEMPORAL_WINDOW_SIZE = 30
    const val DEFAULT_NORMALIZATION_ENABLED = true

    // FIX #1 (CRITICAL): Threshold was 0.32 in production but ALL tests validate against 0.26.
    // At 0.32 the recognizer accepts gestures that are 23% more distorted than what
    // the test suite proves safe. Lower to 0.26 to match empirically validated test boundary.
    // The separation gap between same-class max and different-class min is ~0.10, giving
    // a safe band of [0.00 .. 0.26] for same class and [0.36+] for different class.
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.26

    // Ambiguity margin unchanged — still geometrically justified.
    const val DEFAULT_AMBIGUITY_MARGIN = 0.08

    // Motion thresholds
    const val MOTION_START_VELOCITY_THRESHOLD = 0.026f
    const val MOTION_END_VELOCITY_THRESHOLD   = 0.018f

    // Consecutive frames required for state transitions.
    const val MOTION_START_CONSECUTIVE_FRAMES = 2
    const val MOTION_END_CONSECUTIVE_FRAMES   = 6

    // EMA smoothing unchanged.
    const val VELOCITY_EMA_ALPHA = 0.45f

    // Stabilization / pre-roll unchanged.
    const val HAND_STABILIZATION_FRAMES = 3
    const val PRE_ROLL_FRAMES           = 4

    // Gesture duration bounds.
    const val MIN_GESTURE_DURATION_FRAMES = 6
    const val MAX_GESTURE_DURATION_FRAMES = 90

    // Result display hold time.
    const val RESULT_DISPLAY_DURATION_MS = 2500L

    // Static posture detection (unchanged).
    const val STATIC_POSTURE_HOLD_FRAMES          = 12
    const val STATIC_POSTURE_VELOCITY_THRESHOLD   = 0.016f

    // FIX #4: Sakoe-Chiba warping band — fraction of max(N,M) frames allowed to warp.
    // 0 = no band (original, allows absurd warping). 0.20 = 20% of sequence length.
    // This prevents a 10-frame gesture matching a completely different 90-frame sequence
    // by warping every frame to the same column.
    const val DTW_SAKOE_CHIBA_BAND_RATIO = 0.20f
}
