package com.signbridge.app.gesture

/**
 * In-memory representation of an enrolled gesture prototype.
 *
 * @property id Unique identifier for the gesture class (e.g. "gesture_swipe_up")
 * @property displayName Human-readable label for UI and metrics (e.g. "SWIPE_UP")
 * @property sequence Normalized 30-frame temporal landmark sequence representing the gesture
 * @property createdAtMs Timestamp when the prototype was recorded or instantiated
 */
data class GesturePrototype(
    val id: String,
    val displayName: String,
    val sequence: TemporalSequence,
    val createdAtMs: Long = System.currentTimeMillis()
)
