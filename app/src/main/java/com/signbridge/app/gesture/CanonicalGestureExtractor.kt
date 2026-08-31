package com.signbridge.app.gesture

import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Canonical Gesture Extractor:
 * Extracts the clean, active gesture sequence from raw captured frames by removing
 * leading and trailing stationary/idle padding while strictly preserving internal gesture pauses.
 *
 * Both Teach Mode and Live Recognition Mode MUST pass through this exact extractor
 * to ensure 100% representation parity.
 */
object CanonicalGestureExtractor {

    /**
     * Trims leading and trailing stillness from raw frames.
     */
    fun trimToActiveGesture(
        rawFrames: List<NormalizedLandmarkFrame>,
        startThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD,
        endThreshold: Float = GestureConfig.MOTION_END_VELOCITY_THRESHOLD,
        preMargin: Int = 2,
        postMargin: Int = 2
    ): List<NormalizedLandmarkFrame> {
        if (rawFrames.size < GestureConfig.MIN_GESTURE_DURATION_FRAMES) {
            return rawFrames
        }

        val velocities = ArrayList<Float>(rawFrames.size)
        velocities.add(0f)
        for (i in 1 until rawFrames.size) {
            velocities.add(TemporalSequence.computeInstantaneousVelocity(rawFrames[i - 1], rawFrames[i]))
        }

        // 1. Find Motion Start Index (first frame with active motion >= startThreshold)
        var motionStartIndex = -1
        for (i in velocities.indices) {
            if (velocities[i] >= startThreshold) {
                motionStartIndex = i
                break
            }
        }

        if (motionStartIndex == -1) {
            return rawFrames
        }

        // 2. Find Motion End Index:
        // Look for the last active motion frame. If there is a sustained stillness block (>= 4 frames < endThreshold),
        // any subsequent motion is post-gesture withdrawal/exit motion, so the gesture ends before the stillness.
        var motionEndIndex = rawFrames.lastIndex
        var consecutiveStill = 0
        var foundStillnessBreak = -1

        for (i in motionStartIndex until rawFrames.size) {
            if (velocities[i] < endThreshold) {
                consecutiveStill++
                if (consecutiveStill >= 4 && foundStillnessBreak == -1) {
                    foundStillnessBreak = i - consecutiveStill
                }
            } else {
                consecutiveStill = 0
            }
        }

        if (foundStillnessBreak != -1 && foundStillnessBreak >= motionStartIndex) {
            motionEndIndex = foundStillnessBreak
        } else {
            for (i in rawFrames.lastIndex downTo motionStartIndex) {
                if (velocities[i] >= endThreshold) {
                    motionEndIndex = i
                    break
                }
            }
        }

        val activeFrameSpan = motionEndIndex - motionStartIndex + 1
        if (activeFrameSpan < 3) {
            return emptyList()
        }

        val startIndex = max(0, motionStartIndex - preMargin)
        val endIndex = min(rawFrames.size, motionEndIndex + postMargin + 1)

        if (startIndex >= endIndex) {
            return emptyList()
        }

        return rawFrames.subList(startIndex, endIndex)
    }

    /**
     * Extracts a canonical TemporalSequence from raw frames.
     */
    fun extractCanonicalSequence(
        rawFrames: List<NormalizedLandmarkFrame>,
        sequenceId: Long = 0L,
        startThreshold: Float = GestureConfig.MOTION_START_VELOCITY_THRESHOLD,
        endThreshold: Float = GestureConfig.MOTION_END_VELOCITY_THRESHOLD
    ): TemporalSequence? {
        val trimmed = trimToActiveGesture(rawFrames, startThreshold, endThreshold)
        if (trimmed.size < GestureConfig.MIN_GESTURE_DURATION_FRAMES) {
            return null
        }
        return TemporalSequence(
            frames = ArrayList(trimmed),
            windowSize = trimmed.size,
            isReady = true,
            sequenceId = sequenceId
        )
    }
}
