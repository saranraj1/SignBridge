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
     * Default distance threshold for 1-NN DTW prototype recognition.
     * Normalized DTW distance <= threshold is classified as MATCH; otherwise UNKNOWN.
     *
     * Note: This is an experimental parameter calibrated through intra- vs inter-gesture testing.
     */
    const val DEFAULT_RECOGNITION_THRESHOLD = 5.0
}
