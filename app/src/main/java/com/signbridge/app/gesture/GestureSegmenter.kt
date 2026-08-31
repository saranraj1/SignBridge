package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import java.util.ArrayDeque

/**
 * Event-driven gesture segmentation engine.
 *
 * State Lifecycle:
 * NO_HAND (handAbsent) -> STABILIZING -> IDLE -> CAPTURING -> (COMPLETED / REJECTED) -> IDLE
 */
enum class SegmenterState { STABILIZING, IDLE, CAPTURING, MANUAL_RECORDING }

sealed class SegmentationEvent {
    data class Progress(val state: SegmenterState, val frameCount: Int, val currentVelocity: Float) : SegmentationEvent()
    data class Completed(
        val sequence: TemporalSequence,
        val rawFrameCount: Int,
        val trimmedFrameCount: Int,
        val meanVelocity: Float,
        val durationMs: Long,
        val motionProfile: String,
        val endReason: String
    ) : SegmentationEvent()
    data class Rejected(val reason: String, val frameCount: Int) : SegmentationEvent()
}

class GestureSegmenter(
    private val motionStartVelocityThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD,
    private val motionEndVelocityThreshold: Float = GestureConfig.MOTION_END_VELOCITY_THRESHOLD,
    private val motionStartConsecutiveFrames: Int = GestureConfig.MOTION_START_CONSECUTIVE_FRAMES,
    private val motionEndConsecutiveFrames: Int = GestureConfig.MOTION_END_CONSECUTIVE_FRAMES,
    private val minGestureFrames: Int = GestureConfig.MIN_GESTURE_DURATION_FRAMES,
    private val maxGestureFrames: Int = GestureConfig.MAX_GESTURE_DURATION_FRAMES,
    private val handStabilizationFrames: Int = GestureConfig.HAND_STABILIZATION_FRAMES,
    private val debugLogger: ((String, String) -> Unit)? = null
) {
    var state = SegmenterState.IDLE
        private set
    var handAbsent = true
        private set
    val isManualRecording get() = state == SegmenterState.MANUAL_RECORDING
    val currentAccumulatedCount get() = captured.size

    private val captured = ArrayList<NormalizedLandmarkFrame>()
    private val preRoll = ArrayDeque<NormalizedLandmarkFrame>()
    private var last: NormalizedLandmarkFrame? = null
    private var ema = 0f
    private var high = 0
    private var low = 0
    private var stabilization = 0
    private var sequenceCounter = 0L

    private fun log(t: String, m: String) { debugLogger?.invoke(t, m) }

    @Synchronized
    fun startManualRecording(): SegmentationEvent {
        captured.clear()
        preRoll.clear()
        state = SegmenterState.MANUAL_RECORDING
        return SegmentationEvent.Progress(state, 0, 0f)
    }

    @Synchronized
    fun stopManualRecording(): SegmentationEvent =
        if (state == SegmenterState.MANUAL_RECORDING) finalizeSequence("manual_stop")
        else SegmentationEvent.Rejected("Not in manual recording mode", 0)

    @Synchronized
    fun processFrame(frame: NormalizedLandmarkFrame?): SegmentationEvent {
        if (frame == null) {
            val wasCapturing = (state == SegmenterState.CAPTURING || state == SegmenterState.MANUAL_RECORDING)
            if (wasCapturing) {
                val ev = finalizeSequence("hand_lost")
                handAbsent = true
                return ev
            }
            reset()
            handAbsent = true
            return SegmentationEvent.Progress(SegmenterState.IDLE, 0, 0f)
        }

        if (state == SegmenterState.MANUAL_RECORDING) {
            captured.add(frame)
            last = frame
            handAbsent = false
            return SegmentationEvent.Progress(state, captured.size, 0f)
        }

        if (handAbsent) {
            handAbsent = false
            last = frame
            ema = 0f
            high = 0
            low = 0
            stabilization = 0
            state = if (handStabilizationFrames <= 0) SegmenterState.IDLE else SegmenterState.STABILIZING
            preRoll.clear()
            return SegmentationEvent.Progress(state, 0, 0f)
        }

        val raw = last?.let { TemporalSequence.computeInstantaneousVelocity(it, frame) } ?: 0f
        last = frame
        ema = if (ema == 0f) raw else GestureConfig.VELOCITY_EMA_ALPHA * raw + (1f - GestureConfig.VELOCITY_EMA_ALPHA) * ema

        return when (state) {
            SegmenterState.STABILIZING -> {
                stabilization++
                if (stabilization >= handStabilizationFrames) {
                    state = SegmenterState.IDLE
                    stabilization = 0
                    preRoll.clear()
                }
                SegmentationEvent.Progress(state, 0, ema)
            }

            SegmenterState.IDLE -> {
                preRoll.addLast(frame)
                while (preRoll.size > GestureConfig.PRE_ROLL_FRAMES) preRoll.removeFirst()

                if (raw >= motionStartVelocityThreshold) {
                    high++
                } else {
                    high = 0
                }

                if (high >= motionStartConsecutiveFrames) {
                    captured.clear()
                    captured.addAll(preRoll)
                    state = SegmenterState.CAPTURING
                    low = 0
                    high = 0
                    log("GestureSegmenter", "CAPTURING start vel=${"%.4f".format(ema)} preRoll=${captured.size}")
                    SegmentationEvent.Progress(state, captured.size, ema)
                } else {
                    SegmentationEvent.Progress(state, 0, ema)
                }
            }

            SegmenterState.CAPTURING -> {
                captured.add(frame)
                if (raw < motionEndVelocityThreshold || ema < motionEndVelocityThreshold) {
                    low++
                } else {
                    low = maxOf(0, low - 2)
                }

                when {
                    low >= motionEndConsecutiveFrames -> finalizeSequence("sustained_stillness")
                    captured.size >= maxGestureFrames -> finalizeSequence("max_frames_reached")
                    else -> SegmentationEvent.Progress(state, captured.size, ema)
                }
            }

            SegmenterState.MANUAL_RECORDING -> SegmentationEvent.Progress(state, captured.size, ema)
        }
    }

    private fun finalizeSequence(reason: String): SegmentationEvent {
        val raw = captured.size
        val trimmedFrames = if (!reason.startsWith("manual")) {
            CanonicalGestureExtractor.trimToActiveGesture(
                captured,
                startThreshold = motionStartVelocityThreshold,
                endThreshold = motionEndVelocityThreshold,
                preMargin = 2,
                postMargin = 2
            )
        } else {
            captured
        }

        val frames = ArrayList(trimmedFrames)
        if (frames.size < minGestureFrames) {
            val n = frames.size
            reset()
            return SegmentationEvent.Rejected("Gesture too short: $n frames", n)
        }
        sequenceCounter++
        val seq = TemporalSequence(frames, frames.size, true, sequenceCounter)
        val velocities = seq.computeFrameVelocities()
        val mean = if (velocities.isEmpty()) 0f else velocities.average().toFloat()
        val event = SegmentationEvent.Completed(seq, raw, frames.size, mean, seq.durationMs, seq.formatMotionProfile(), reason)
        log("GestureSegmenter", "COMPLETED raw=$raw trimmed=${frames.size} reason=$reason")
        reset()
        return event
    }

    @Synchronized
    fun reset() {
        state = SegmenterState.IDLE
        captured.clear()
        preRoll.clear()
        last = null
        ema = 0f
        high = 0
        low = 0
        stabilization = 0
    }

    @Synchronized
    fun fullReset() {
        reset()
        handAbsent = true
    }
}
