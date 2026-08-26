package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import java.util.ArrayDeque

/**
 * Thread-safe rolling temporal FIFO buffer that stores the most recent N normalized landmark frames.
 *
 * Once the buffer reaches [windowSize], adding a new frame discards the oldest frame,
 * maintaining a continuous sliding temporal window.
 *
 * @property windowSize Maximum number of frames retained in the rolling buffer
 */
class TemporalBuffer(
    val windowSize: Int = GestureConfig.DEFAULT_TEMPORAL_WINDOW_SIZE
) {
    init {
        require(windowSize > 0) { "Temporal window size must be greater than 0, but was $windowSize" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<NormalizedLandmarkFrame>(windowSize)

    /**
     * Current number of valid frames in the buffer.
     */
    val size: Int
        get() = synchronized(lock) { buffer.size }

    /**
     * True when the buffer has filled to capacity [windowSize] and is ready for recognition.
     */
    val isReady: Boolean
        get() = synchronized(lock) { buffer.size == windowSize }

    /**
     * Current state of the temporal sequence buffer.
     */
    val status: SequenceStatus
        get() = synchronized(lock) {
            when {
                buffer.isEmpty() -> SequenceStatus.EMPTY
                buffer.size == windowSize -> SequenceStatus.READY
                else -> SequenceStatus.FILLING
            }
        }

    /**
     * Appends a new normalized landmark frame to the temporal buffer.
     *
     * If [frame] is null (e.g. no hand detected or invalid frame), no frame is inserted,
     * protecting the buffer against corrupted/bogus sequence gaps.
     *
     * @param frame Normalized hand landmark frame to add
     * @return Updated [SequenceStatus] of the buffer
     */
    fun addFrame(frame: NormalizedLandmarkFrame?): SequenceStatus {
        if (frame == null) {
            return status
        }

        synchronized(lock) {
            if (buffer.size >= windowSize) {
                buffer.removeFirst()
            }
            buffer.addLast(frame)

            return when {
                buffer.size == windowSize -> SequenceStatus.READY
                else -> SequenceStatus.FILLING
            }
        }
    }

    /**
     * Returns an immutable defensive snapshot of the current temporal sequence in chronological order.
     */
    fun getSnapshot(): TemporalSequence {
        synchronized(lock) {
            val snapshotList = ArrayList(buffer)
            return TemporalSequence(
                frames = snapshotList,
                windowSize = windowSize,
                isReady = snapshotList.size == windowSize
            )
        }
    }

    /**
     * Resets and clears all frames from the temporal buffer.
     */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }
}
