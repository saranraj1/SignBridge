package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame

/**
 * Status of the rolling temporal gesture buffer.
 */
enum class SequenceStatus {
    /** Buffer contains 0 frames. */
    EMPTY,

    /** Buffer contains frames but has not reached full window capacity yet. */
    FILLING,

    /** Buffer has reached full window capacity (e.g. 30 frames) and is ready for recognition. */
    READY
}

/**
 * Immutable snapshot of a temporal sequence of normalized landmark frames.
 *
 * @property frames Ordered list of frames from oldest to newest (chronological order)
 * @property windowSize Configured maximum capacity of the sequence window
 * @property isReady True when `frames.size == windowSize`
 */
data class TemporalSequence(
    val frames: List<NormalizedLandmarkFrame>,
    val windowSize: Int,
    val isReady: Boolean
) {
    val frameCount: Int
        get() = frames.size

    val durationMs: Long
        get() = if (frames.size >= 2) {
            frames.last().timestampMs - frames.first().timestampMs
        } else {
            0L
        }
}
