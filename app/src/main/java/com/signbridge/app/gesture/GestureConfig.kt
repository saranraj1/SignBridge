package com.signbridge.app.gesture

/**
 * Global configuration constants for gesture processing, temporal buffering, and DTW recognition.
 * Centralized in one place to easily support future experiments and tuning.
 */
object GestureConfig {
    /**
     * Default number of temporal frames stored in the rolling gesture buffer.
     */
    const val DEFAULT_TEMPORAL_WINDOW_SIZE = 30

    /**
     * Flag enabling or disabling landmark normalization (useful for ablation experiments).
     */
    const val DEFAULT_NORMALIZATION_ENABLED = true

    /**
     * Calibrated distance threshold for 1-NN DTW prototype recognition.
     * Normalized DTW distance <= threshold is classified as MATCH; otherwise UNKNOWN.
     *
     * Calibration based on empirical measurements:
     * - Same gesture (intra-demonstration with natural jitter/speed variations): 0.02 - 0.18
     * - Static holding / resting hand pose: 0.35 - 0.55
     * - Different dynamic gesture classes (inter-gesture): 0.60 - 1.50
     */
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.28

    /**
     * Minimum distance margin required between the best matching gesture class
     * and the runner-up gesture class to prevent ambiguous misclassifications.
     */
    const val DEFAULT_AMBIGUITY_MARGIN = 0.06

    /**
     * Minimum motion variance across the 30-frame window required
     * to distinguish intentional dynamic gestures from stationary resting hands.
     */
    const val MIN_DYNAMIC_MOTION_VARIANCE = 0.005f
}
