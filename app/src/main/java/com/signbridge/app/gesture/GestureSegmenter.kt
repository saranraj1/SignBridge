package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import java.util.ArrayList

/**
 * States of the event-driven gesture segmentation lifecycle.
 */
enum class SegmenterState {
    /** Hand is present but stationary/idling. Waiting for gesture motion onset. */
    IDLE,

    /** Active gesture motion detected; accumulating normalized landmark trajectory. */
    CAPTURING
}

/**
 * Event emitted by [GestureSegmenter] when a gesture sequence is finalized.
 */
sealed class SegmentationEvent {
    /** No complete gesture ready yet; current live segmentation state. */
    data class Progress(
        val state: SegmenterState,
        val accumulatedFrames: Int,
        val currentVelocity: Float
    ) : SegmentationEvent()

    /** A clean, variable-length gesture trajectory has been isolated and completed. */
    data class Completed(
        val sequence: TemporalSequence,
        val rawFrameCount: Int,
        val trimmedFrameCount: Int,
        val meanVelocity: Float
    ) : SegmentationEvent()

    /** Motion occurred but was rejected (e.g. noise twitch < minFrames or invalid). */
    data class Rejected(
        val reason: String,
        val frameCount: Int
    ) : SegmentationEvent()
}

/**
 * Pure Kotlin event-driven gesture segmenter.
 *
 * Responsibilities:
 * - Computes instantaneous landmark coordinate displacement Delta L(t, t-1).
 * - Distinguishes hand entry/repositioning from true gesture execution using consecutive frame gating.
 * - Captures variable-length sequences with pause tolerance (does not abort during short mid-gesture stops).
 * - Trims trailing post-gesture stillness frames before emission.
 * - Enforces minimum and maximum duration safety bounds.
 */
class GestureSegmenter(
    val motionStartVelocityThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD,
    val motionEndVelocityThreshold: Float = GestureConfig.MOTION_END_VELOCITY_THRESHOLD,
    val motionStartConsecutiveFrames: Int = GestureConfig.MOTION_START_CONSECUTIVE_FRAMES,
    val motionEndConsecutiveFrames: Int = GestureConfig.MOTION_END_CONSECUTIVE_FRAMES,
    val pauseToleranceFrames: Int = GestureConfig.PAUSE_TOLERANCE_FRAMES,
    val minGestureFrames: Int = GestureConfig.MIN_GESTURE_DURATION_FRAMES,
    val maxGestureFrames: Int = GestureConfig.MAX_GESTURE_DURATION_FRAMES
) {
    var state: SegmenterState = SegmenterState.IDLE
        private set

    private val capturedFrames = ArrayList<NormalizedLandmarkFrame>()
    private var lastFrame: NormalizedLandmarkFrame? = null
    private var consecutiveHighVelocityFrames: Int = 0
    private var consecutiveLowVelocityFrames: Int = 0
    private var sequenceCounter: Long = 0L

    val currentAccumulatedCount: Int
        get() = capturedFrames.size

    /**
     * Processes a single normalized frame from the camera/vision pipeline.
     *
     * @param frame Normalized landmark frame (null if hand tracking is absent)
     * @return [SegmentationEvent] indicating progress, completion, or noise rejection
     */
    fun processFrame(frame: NormalizedLandmarkFrame?): SegmentationEvent {
        if (frame == null) {
            // Hand is lost or removed
            val event = if (state == SegmenterState.CAPTURING && capturedFrames.size >= minGestureFrames) {
                finalizeSequence()
            } else {
                reset()
                SegmentationEvent.Progress(SegmenterState.IDLE, 0, 0f)
            }
            return event
        }

        val velocity = if (lastFrame != null) {
            TemporalSequence.computeInstantaneousVelocity(lastFrame!!, frame)
        } else {
            0f
        }
        lastFrame = frame

        return when (state) {
            SegmenterState.IDLE -> {
                if (velocity >= motionStartVelocityThreshold) {
                    consecutiveHighVelocityFrames++
                    if (consecutiveHighVelocityFrames >= motionStartConsecutiveFrames) {
                        // Gesture start detected! Transition to CAPTURING
                        state = SegmenterState.CAPTURING
                        capturedFrames.clear()
                        consecutiveLowVelocityFrames = 0
                        // Include the initiating frame
                        capturedFrames.add(frame)
                        SegmentationEvent.Progress(SegmenterState.CAPTURING, capturedFrames.size, velocity)
                    } else {
                        SegmentationEvent.Progress(SegmenterState.IDLE, 0, velocity)
                    }
                } else {
                    consecutiveHighVelocityFrames = 0
                    SegmentationEvent.Progress(SegmenterState.IDLE, 0, velocity)
                }
            }

            SegmenterState.CAPTURING -> {
                capturedFrames.add(frame)

                if (velocity < motionEndVelocityThreshold) {
                    consecutiveLowVelocityFrames++
                } else {
                    consecutiveLowVelocityFrames = 0
                }

                // Check for completion conditions:
                // 1. Sustained stillness beyond pause tolerance (consecutive low velocity >= motionEndConsecutiveFrames)
                // 2. Maximum gesture frame limit reached
                if (consecutiveLowVelocityFrames >= motionEndConsecutiveFrames || capturedFrames.size >= maxGestureFrames) {
                    finalizeSequence()
                } else {
                    SegmentationEvent.Progress(SegmenterState.CAPTURING, capturedFrames.size, velocity)
                }
            }
        }
    }

    /**
     * Finalizes the captured sequence, trims trailing idle frames, validates safety bounds, and resets state.
     */
    private fun finalizeSequence(): SegmentationEvent {
        val totalRawCount = capturedFrames.size

        // Trim trailing low-velocity frames (up to consecutiveLowVelocityFrames)
        val trimCount = minOf(consecutiveLowVelocityFrames, capturedFrames.size)
        val effectiveEndIndex = capturedFrames.size - trimCount
        val trimmedList = if (effectiveEndIndex > 0) {
            ArrayList(capturedFrames.subList(0, effectiveEndIndex))
        } else {
            ArrayList(capturedFrames)
        }

        val trimmedCount = trimmedList.size

        if (trimmedCount < minGestureFrames) {
            reset()
            return SegmentationEvent.Rejected(
                reason = "Gesture too short ($trimmedCount frames < $minGestureFrames min)",
                frameCount = trimmedCount
            )
        }

        sequenceCounter++
        val sequence = TemporalSequence(
            frames = trimmedList,
            windowSize = trimmedCount,
            isReady = true,
            sequenceId = sequenceCounter
        )

        val velocities = sequence.computeFrameVelocities()
        val meanVel = if (velocities.isNotEmpty()) velocities.average().toFloat() else 0f

        reset()

        return SegmentationEvent.Completed(
            sequence = sequence,
            rawFrameCount = totalRawCount,
            trimmedFrameCount = trimmedCount,
            meanVelocity = meanVel
        )
    }

    /**
     * Resets the segmenter back to clean IDLE state.
     */
    fun reset() {
        state = SegmenterState.IDLE
        capturedFrames.clear()
        lastFrame = null
        consecutiveHighVelocityFrames = 0
        consecutiveLowVelocityFrames = 0
    }
}
