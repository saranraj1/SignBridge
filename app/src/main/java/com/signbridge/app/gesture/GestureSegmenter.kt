package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import java.util.ArrayList

/**
 * States of the event-driven gesture segmentation lifecycle.
 */
enum class SegmenterState {
    /** Hand just appeared — ignoring motion transients for stabilization. */
    STABILIZING,

    /** Hand is present and stable, waiting for intentional gesture motion onset. */
    IDLE,

    /** Active gesture motion detected; accumulating normalized landmark trajectory. */
    CAPTURING,

    /** Phase 8: Manually bounded diagnostic recording mode (bypasses automatic segmentation). */
    MANUAL_RECORDING
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
        val meanVelocity: Float,
        val durationMs: Long,
        val motionProfile: String,
        val endReason: String
    ) : SegmentationEvent()

    /** Motion occurred but was rejected (e.g. noise twitch < minFrames or invalid). */
    data class Rejected(
        val reason: String,
        val frameCount: Int
    ) : SegmentationEvent()
}

/**
 * Pure Kotlin event-driven gesture segmenter with Phase 8 manual bypass capability.
 */
class GestureSegmenter(
    val motionStartVelocityThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD,
    val motionEndVelocityThreshold: Float = GestureConfig.MOTION_END_VELOCITY_THRESHOLD,
    val motionStartConsecutiveFrames: Int = GestureConfig.MOTION_START_CONSECUTIVE_FRAMES,
    val motionEndConsecutiveFrames: Int = GestureConfig.MOTION_END_CONSECUTIVE_FRAMES,
    val minGestureFrames: Int = GestureConfig.MIN_GESTURE_DURATION_FRAMES,
    val maxGestureFrames: Int = GestureConfig.MAX_GESTURE_DURATION_FRAMES,
    val handStabilizationFrames: Int = GestureConfig.HAND_STABILIZATION_FRAMES,
    val debugLogger: ((tag: String, message: String) -> Unit)? = null
) {
    var state: SegmenterState = SegmenterState.IDLE
        private set

    /** True when hand is currently absent (last frame was null). */
    var handAbsent: Boolean = true
        private set

    private val capturedFrames = ArrayList<NormalizedLandmarkFrame>()
    private var lastFrame: NormalizedLandmarkFrame? = null
    private var consecutiveHighVelocityFrames: Int = 0
    private var consecutiveLowVelocityFrames: Int = 0
    private var stabilizationCounter: Int = 0
    private var sequenceCounter: Long = 0L
    private var lastIdleLogTimestamp: Long = 0L

    val currentAccumulatedCount: Int
        get() = capturedFrames.size

    val isManualRecording: Boolean
        get() = state == SegmenterState.MANUAL_RECORDING

    private fun log(tag: String, msg: String) {
        debugLogger?.invoke(tag, msg)
    }

    /**
     * Phase 8: Starts manual bounded recording (bypasses automatic start detection).
     */
    fun startManualRecording() {
        capturedFrames.clear()
        state = SegmenterState.MANUAL_RECORDING
        log("GestureSegmenter", "MANUAL RECORDING STARTED (Bypassing automatic segmentation)")
    }

    /**
     * Phase 8: Stops manual bounded recording and finalizes the sequence directly.
     */
    fun stopManualRecording(): SegmentationEvent {
        if (state != SegmenterState.MANUAL_RECORDING) {
            return SegmentationEvent.Rejected("Not in manual recording mode", 0)
        }
        val count = capturedFrames.size
        log("GestureSegmenter", "MANUAL RECORDING STOPPED (Frames: $count)")
        return finalizeSequence("manual_stop")
    }

    /**
     * Processes a single normalized frame from the camera/vision pipeline.
     *
     * @param frame Normalized landmark frame (null if hand tracking is absent)
     * @return [SegmentationEvent] indicating progress, completion, or noise rejection
     */
    fun processFrame(frame: NormalizedLandmarkFrame?): SegmentationEvent {
        // ===== NULL FRAME: Hand is absent =====
        if (frame == null) {
            val event = if (state == SegmenterState.CAPTURING && capturedFrames.size >= minGestureFrames) {
                finalizeSequence("hand_lost")
            } else if (state == SegmenterState.MANUAL_RECORDING && capturedFrames.size >= minGestureFrames) {
                finalizeSequence("manual_hand_lost")
            } else {
                if (state == SegmenterState.CAPTURING || state == SegmenterState.MANUAL_RECORDING) {
                    val count = capturedFrames.size
                    reset()
                    SegmentationEvent.Rejected("Hand lost during capture ($count frames < $minGestureFrames min)", count)
                } else {
                    reset()
                    SegmentationEvent.Progress(SegmenterState.IDLE, 0, 0f)
                }
            }
            handAbsent = true
            return event
        }

        // ===== NON-NULL FRAME: Hand is present =====

        // Phase 8: Manual recording bypass
        if (state == SegmenterState.MANUAL_RECORDING) {
            handAbsent = false
            capturedFrames.add(frame)
            val velocity = if (lastFrame != null) {
                TemporalSequence.computeInstantaneousVelocity(lastFrame!!, frame)
            } else {
                0f
            }
            lastFrame = frame
            return SegmentationEvent.Progress(SegmenterState.MANUAL_RECORDING, capturedFrames.size, velocity)
        }

        // Detect hand appearance (transition from absent to present)
        if (handAbsent) {
            handAbsent = false
            lastFrame = frame
            stabilizationCounter = 0
            state = SegmenterState.STABILIZING
            consecutiveHighVelocityFrames = 0
            consecutiveLowVelocityFrames = 0
            return SegmentationEvent.Progress(SegmenterState.STABILIZING, 0, 0f)
        }

        // Compute instantaneous velocity
        val velocity = if (lastFrame != null) {
            TemporalSequence.computeInstantaneousVelocity(lastFrame!!, frame)
        } else {
            0f
        }
        lastFrame = frame

        return when (state) {
            SegmenterState.STABILIZING -> {
                stabilizationCounter++
                if (stabilizationCounter >= handStabilizationFrames) {
                    // Stabilization complete — transition to IDLE
                    state = SegmenterState.IDLE
                    consecutiveHighVelocityFrames = 0
                    consecutiveLowVelocityFrames = 0
                    SegmentationEvent.Progress(SegmenterState.IDLE, 0, velocity)
                } else {
                    SegmentationEvent.Progress(SegmenterState.STABILIZING, 0, velocity)
                }
            }

            SegmenterState.IDLE -> {
                // Phase 5: Determine why gestures stay IDLE - detailed logging
                val now = System.currentTimeMillis()
                if (velocity >= motionStartVelocityThreshold) {
                    consecutiveHighVelocityFrames++
                    log(
                        "M4ForensicIdle",
                        "MOTION ONSET FRAME: vel=${String.format("%.4f", velocity)} >= thresh=${String.format("%.4f", motionStartVelocityThreshold)} " +
                                "(counter=$consecutiveHighVelocityFrames/$motionStartConsecutiveFrames) -> ${if (consecutiveHighVelocityFrames >= motionStartConsecutiveFrames) "TRANSITION TO CAPTURING" else "WAITING"}"
                    )

                    if (consecutiveHighVelocityFrames >= motionStartConsecutiveFrames) {
                        // Gesture start detected — transition to CAPTURING
                        state = SegmenterState.CAPTURING
                        capturedFrames.clear()
                        consecutiveLowVelocityFrames = 0
                        // Include initiating frame
                        capturedFrames.add(frame)
                        SegmentationEvent.Progress(SegmenterState.CAPTURING, capturedFrames.size, velocity)
                    } else {
                        SegmentationEvent.Progress(SegmenterState.IDLE, 0, velocity)
                    }
                } else {
                    if (consecutiveHighVelocityFrames > 0) {
                        log(
                            "M4ForensicIdle",
                            "MOTION RESET: vel=${String.format("%.4f", velocity)} < thresh=${String.format("%.4f", motionStartVelocityThreshold)} (counter was $consecutiveHighVelocityFrames, now 0)"
                        )
                    }
                    consecutiveHighVelocityFrames = 0

                    if (now - lastIdleLogTimestamp >= 1000) {
                        lastIdleLogTimestamp = now
                        log(
                            "M4ForensicIdle",
                            "IDLE TELEMETRY: vel=${String.format("%.4f", velocity)}, thresh=${String.format("%.4f", motionStartVelocityThreshold)}, state=IDLE"
                        )
                    }

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

                when {
                    consecutiveLowVelocityFrames >= motionEndConsecutiveFrames -> {
                        finalizeSequence("sustained_stillness")
                    }
                    capturedFrames.size >= maxGestureFrames -> {
                        finalizeSequence("max_frames_reached")
                    }
                    else -> {
                        SegmentationEvent.Progress(SegmenterState.CAPTURING, capturedFrames.size, velocity)
                    }
                }
            }

            SegmenterState.MANUAL_RECORDING -> {
                SegmentationEvent.Progress(SegmenterState.MANUAL_RECORDING, capturedFrames.size, velocity)
            }
        }
    }

    /**
     * Finalizes the captured sequence: trims trailing idle frames backwards,
     * validates bounds, computes diagnostics, and resets state.
     *
     * @param endReason Human-readable reason for gesture completion
     */
    private fun finalizeSequence(endReason: String): SegmentationEvent {
        val totalRawCount = capturedFrames.size

        // Backward trimming: find the last active motion frame
        var lastActiveIndex = capturedFrames.size - 1
        while (lastActiveIndex > 0) {
            val v = TemporalSequence.computeInstantaneousVelocity(
                capturedFrames[lastActiveIndex - 1],
                capturedFrames[lastActiveIndex]
            )
            if (v >= motionEndVelocityThreshold) {
                break
            }
            lastActiveIndex--
        }

        // Keep 1 buffer frame of settling after last motion for clean endpoint
        val effectiveEndIndex = if (endReason.startsWith("manual")) {
            capturedFrames.size // In manual mode, preserve full user-selected window
        } else {
            minOf(capturedFrames.size, lastActiveIndex + 2)
        }

        val trimmedList = if (effectiveEndIndex > 0) {
            ArrayList(capturedFrames.subList(0, effectiveEndIndex))
        } else {
            ArrayList(capturedFrames)
        }

        val trimmedCount = trimmedList.size

        if (trimmedCount < minGestureFrames) {
            val reason = "Gesture too short ($trimmedCount frames < $minGestureFrames min, endReason=$endReason)"
            reset()
            return SegmentationEvent.Rejected(reason = reason, frameCount = trimmedCount)
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
        val durationMs = sequence.durationMs
        val motionProfile = sequence.formatMotionProfile()

        reset()

        return SegmentationEvent.Completed(
            sequence = sequence,
            rawFrameCount = totalRawCount,
            trimmedFrameCount = trimmedCount,
            meanVelocity = meanVel,
            durationMs = durationMs,
            motionProfile = motionProfile,
            endReason = endReason
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
        stabilizationCounter = 0
    }

    /**
     * Full reset including hand presence tracking.
     */
    fun fullReset() {
        reset()
        handAbsent = true
    }
}
