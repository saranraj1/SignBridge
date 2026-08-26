package com.signbridge.app.vision

/**
 * Represents a single 3D point (landmark) in normalized [0.0, 1.0] coordinates or world metric space.
 *
 * @property x Normalized horizontal coordinate (0.0 = left edge, 1.0 = right edge)
 * @property y Normalized vertical coordinate (0.0 = top edge, 1.0 = bottom edge)
 * @property z Relative depth coordinate (smaller value = closer to camera)
 * @property visibility Optional visibility confidence score [0.0, 1.0] if available
 * @property presence Optional presence confidence score [0.0, 1.0] if available
 */
data class LandmarkPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float = 1.0f,
    val presence: Float = 1.0f
)

/**
 * Represents a single detected hand containing all 21 standard MediaPipe landmarks.
 *
 * @property handedness "Left" or "Right"
 * @property score Confidence score of handedness classification [0.0, 1.0]
 * @property landmarks List of 21 normalized landmarks (image coordinates)
 * @property worldLandmarks List of 21 3D world landmarks in real-world meters
 */
data class HandLandmarkData(
    val handedness: String,
    val score: Float,
    val landmarks: List<LandmarkPoint>,
    val worldLandmarks: List<LandmarkPoint> = emptyList()
) {
    init {
        require(landmarks.isEmpty() || landmarks.size == 21) {
            "A detected hand must have exactly 21 landmarks, but got ${landmarks.size}"
        }
    }
}

/**
 * Represents the complete vision inference output for a single camera frame.
 * This is the clean interface passed to future preprocessing, temporal buffering, and gesture recognition.
 *
 * @property timestampMs Frame timestamp in milliseconds
 * @property hands List of detected hands in this frame (0, 1, or 2 hands)
 * @property inferenceLatencyMs Actual time taken by the MediaPipe ML model in milliseconds
 * @property inputImageWidth Width of the image processed by the detector
 * @property inputImageHeight Height of the image processed by the detector
 */
data class VisionFrameResult(
    val timestampMs: Long,
    val hands: List<HandLandmarkData>,
    val inferenceLatencyMs: Long,
    val inputImageWidth: Int,
    val inputImageHeight: Int
) {
    val hasHands: Boolean
        get() = hands.isNotEmpty()

    val totalLandmarksCount: Int
        get() = hands.sumOf { it.landmarks.size }
}
