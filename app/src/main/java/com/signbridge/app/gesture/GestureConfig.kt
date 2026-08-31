package com.signbridge.app.gesture

/** Central configuration for the on-device personalized gesture recognizer. */
object GestureConfig {
    const val DEFAULT_TEMPORAL_WINDOW_SIZE = 30
    const val DEFAULT_NORMALIZATION_ENABLED = true

    // Recognition is based on empirical 66-D shape + trajectory features.
    const val DEFAULT_RECOGNITION_THRESHOLD = 0.26
    const val DEFAULT_AMBIGUITY_MARGIN = 0.08

    // Segmentation: velocity is EMA-smoothed and includes normalized hand-shape motion
    // plus global wrist motion, so translation gestures are not invisible.
    const val MOTION_START_VELOCITY_THRESHOLD = 0.026f
    const val MOTION_END_VELOCITY_THRESHOLD = 0.018f
    const val MOTION_START_CONSECUTIVE_FRAMES = 2
    const val MOTION_END_CONSECUTIVE_FRAMES = 6
    const val VELOCITY_EMA_ALPHA = 0.45f
    const val HAND_STABILIZATION_FRAMES = 3
    const val PRE_ROLL_FRAMES = 4
    const val MIN_GESTURE_DURATION_FRAMES = 6
    const val MAX_GESTURE_DURATION_FRAMES = 90
    const val RESULT_DISPLAY_DURATION_MS = 2500L
}
