package com.signbridge.app.gesture

/**
 * Global configuration constants for gesture preprocessing, event-driven segmentation,
 * and 66-D 1-NN DTW prototype recognition.
 *
 * Production representation: 66-D (63-D Normalized Hand Shape + 3-D Cumulative Wrist Trajectory).
 * Thresholds derived empirically from physical 66-D DTW distributions.
 */
object GestureConfig {
    /**
     * Legacy default temporal window size (used for rolling buffer fallback/benchmarks).
     */
    const val DEFAULT_TEMPORAL_WINDOW_SIZE = 30

    /**
     * Flag enabling or disabling landmark normalization.
     */
    const val DEFAULT_NORMALIZATION_ENABLED = true

    /**
     * Production 66-D DTW Recognition Threshold:
     * - Same gesture repetitions: DTW ≈ 0.019 – 0.080
     * - Stationary resting hand: DTW ≈ 0.416
     * - Distinct/Unrelated gestures: DTW ≈ 0.547 – 1.005
     * Threshold 0.26 sits with wide safety margins: 0.08 << 0.26 << 0.41.
     */
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.26

    /**
     * Minimum distance margin required between best match and runner-up match
     * to prevent ambiguous misclassifications.
     */
    const val DEFAULT_AMBIGUITY_MARGIN = 0.08

    // ============================
    // GESTURE SEGMENTATION
    // ============================

    /**
     * Minimum combined velocity required to trigger gesture motion onset.
     * Combines finger articulation and scaled wrist movement.
     */
    const val MOTION_START_VELOCITY_THRESHOLD = 0.028f

    /**
     * Maximum combined velocity below which motion is considered idle/settled.
     */
    const val MOTION_END_VELOCITY_THRESHOLD = 0.022f

    /**
     * Number of consecutive high-velocity frames required to initiate CAPTURING.
     */
    const val MOTION_START_CONSECUTIVE_FRAMES = 2

    /**
     * Number of consecutive low-velocity frames required to finalize a gesture.
     * 6 frames at ~10-15 FPS = ~400-600ms of post-gesture stillness.
     */
    const val MOTION_END_CONSECUTIVE_FRAMES = 6

    /**
     * Minimum number of frames required for a valid gesture sequence.
     */
    const val MIN_GESTURE_DURATION_FRAMES = 6

    /**
     * Maximum number of frames allowed for a single gesture before forced finalization.
     */
    const val MAX_GESTURE_DURATION_FRAMES = 50

    /**
     * Number of frames to ignore after a hand first appears in the camera frame.
     */
    const val HAND_STABILIZATION_FRAMES = 3

    /**
     * Duration in milliseconds to display a recognition result before resetting HUD to SEARCHING.
     */
    const val RESULT_DISPLAY_DURATION_MS = 2500L
}
