package com.signbridge.app.gesture

/**
 * Global configuration constants for gesture preprocessing, event-driven segmentation,
 * and 1-NN DTW prototype recognition.
 *
 * All segmentation thresholds are calibrated from physical-device telemetry on iQOO I2214.
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
     * Calibrated distance threshold for 1-NN DTW prototype recognition.
     * Normalized DTW distance <= threshold is classified as MATCH; otherwise UNKNOWN.
     */
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.32

    /**
     * Minimum distance margin required between the best matching gesture class
     * and the runner-up gesture class to prevent ambiguous misclassifications.
     */
    const val DEFAULT_AMBIGUITY_MARGIN = 0.06

    // ============================
    // GESTURE SEGMENTATION
    // ============================

    /**
     * Minimum instantaneous landmark velocity required to trigger gesture motion onset.
     * Calibrated above resting hand jitter (resting 95th pct = 0.0165).
     */
    const val MOTION_START_VELOCITY_THRESHOLD = 0.028f

    /**
     * Maximum instantaneous landmark velocity below which motion is considered idle/settled.
     * Set with slight hysteresis below start threshold.
     */
    const val MOTION_END_VELOCITY_THRESHOLD = 0.024f

    /**
     * Number of consecutive high-velocity frames required to initiate CAPTURING.
     * 2 frames at ~10-15 FPS = ~150-200ms of intentional motion.
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
     * 3 frames ≈ 200-300ms of hand entry stabilization.
     */
    const val HAND_STABILIZATION_FRAMES = 3

    /**
     * Duration in milliseconds to display a recognition result before resetting HUD to SEARCHING.
     */
    const val RESULT_DISPLAY_DURATION_MS = 2500L
}
