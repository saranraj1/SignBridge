package com.signbridge.app.gesture

/**
 * Global configuration constants for gesture preprocessing, event-driven segmentation,
 * and 1-NN DTW prototype recognition.
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
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.28

    /**
     * Minimum distance margin required between the best matching gesture class
     * and the runner-up gesture class to prevent ambiguous misclassifications.
     */
    const val DEFAULT_AMBIGUITY_MARGIN = 0.06

    /**
     * Minimum landmark coordinate velocity required to trigger gesture motion onset.
     * Calibrated on physical hardware (resting hand mean = 0.0114, 95th pct = 0.0165).
     */
    const val MOTION_START_VELOCITY_THRESHOLD = 0.045f

    /**
     * Maximum landmark coordinate velocity below which motion is considered settled/idle.
     */
    const val MOTION_END_VELOCITY_THRESHOLD = 0.028f

    /**
     * Number of consecutive high-velocity frames required to initiate CAPTURING.
     * Prevents false triggers from single-frame hand entry transients.
     */
    const val MOTION_START_CONSECUTIVE_FRAMES = 2

    /**
     * Number of consecutive low-velocity frames required to finalize a gesture.
     */
    const val MOTION_END_CONSECUTIVE_FRAMES = 6

    /**
     * Number of low-velocity pause frames tolerated mid-gesture without premature termination.
     */
    const val PAUSE_TOLERANCE_FRAMES = 5

    /**
     * Minimum number of frames required for a valid gesture sequence (discards noise bursts).
     */
    const val MIN_GESTURE_DURATION_FRAMES = 8

    /**
     * Maximum number of frames allowed for a single gesture before forced finalization.
     */
    const val MAX_GESTURE_DURATION_FRAMES = 50

    /**
     * Duration in milliseconds to display a recognition result before resetting HUD to SEARCHING.
     */
    const val RESULT_DISPLAY_DURATION_MS = 2000L
}
