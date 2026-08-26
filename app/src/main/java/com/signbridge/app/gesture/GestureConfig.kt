package com.signbridge.app.gesture

/**
 * Global configuration constants for gesture processing and temporal buffering.
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
}
