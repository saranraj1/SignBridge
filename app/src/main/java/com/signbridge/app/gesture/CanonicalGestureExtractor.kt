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
     * Trims leading and trailing stillness from raw frames while strictly preserving
     * internal gesture pauses and rejecting trailing exit noise.
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
        // Scan backwards from the end of the sequence to find the last significant active motion (>= endThreshold).
        // This ensures all internal pauses (e.g. SSSS in the middle of MMMMSSSSMM) are preserved.
        // If there are isolated trailing noise spikes (1 frame) after a long stillness block (>= 4 frames),
        // they are ignored so trailing withdrawal noise does not extend the gesture.
        var motionEndIndex = -1
        for (i in rawFrames.lastIndex downTo motionStartIndex) {
            if (velocities[i] >= endThreshold) {
                var isIsolatedTrailingNoise = false
                if (i > motionStartIndex + 4) {
                    var precedingStillCount = 0
                    for (k in i - 1 downTo motionStartIndex) {
                        if (velocities[k] < endThreshold) {
                            precedingStillCount++
                        } else {
                            break
                        }
                    }
                    if (precedingStillCount >= 4) {
                        var trailingActiveCount = 0
                        for (k in i until rawFrames.size) {
                            if (velocities[k] >= endThreshold) trailingActiveCount++
                        }
                        if (trailingActiveCount < 2) {
                            isIsolatedTrailingNoise = true
                        }
                    }
                }

                if (!isIsolatedTrailingNoise) {
                    motionEndIndex = i
                    break
                }
            }
        }

        if (motionEndIndex == -1 || motionEndIndex < motionStartIndex) {
            motionEndIndex = motionStartIndex
        }

        val startIndex = max(0, motionStartIndex - preMargin)
        val endIndex = min(rawFrames.size, motionEndIndex + postMargin + 1)

        if (startIndex >= endIndex || (endIndex - startIndex) < 3) {
            return rawFrames
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
