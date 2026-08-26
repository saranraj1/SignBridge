package com.signbridge.app.preprocessing

import com.signbridge.app.vision.LandmarkPoint

/**
 * Represents a single 3D landmark point after translation and scale normalization.
 *
 * In normalized space:
 * - The wrist (landmark 0) is at origin (0.0, 0.0, 0.0).
 * - Distances are scaled relative to the wrist-to-middle-MCP bone length.
 *
 * @property x Relative horizontal coordinate
 * @property y Relative vertical coordinate
 * @property z Relative depth coordinate
 */
data class NormalizedLandmarkPoint(
    val x: Float,
    val y: Float,
    val z: Float
) {
    /**
     * Converts to a FloatArray [x, y, z] for numeric computations and vector distance metrics.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(x, y, z)
}

/**
 * Represents a single hand landmark frame after translation and scale normalization.
 * This is the atomic frame unit stored in temporal sequences and passed to recognition.
 *
 * @property timestampMs Timestamp of the camera frame in milliseconds
 * @property handedness "Left", "Right", or "Unknown"
 * @property landmarks List of exactly 21 normalized 3D landmark points
 * @property handScale Measured hand scale (wrist-to-middle-finger MCP distance) prior to normalization
 * @property rawWristPosition Original camera coordinate position of the wrist (landmark 0)
 */
data class NormalizedLandmarkFrame(
    val timestampMs: Long,
    val handedness: String,
    val landmarks: List<NormalizedLandmarkPoint>,
    val handScale: Float,
    val rawWristPosition: LandmarkPoint
) {
    init {
        require(landmarks.size == 21) {
            "Normalized hand frame must contain exactly 21 landmarks, but got ${landmarks.size}"
        }
    }

    /**
     * Flattens all 21 normalized 3D landmarks into a single 63-dimensional feature vector [x0, y0, z0, x1, y1, z1, ...].
     */
    fun toFeatureVector(): FloatArray {
        val vector = FloatArray(21 * 3)
        var idx = 0
        for (landmark in landmarks) {
            vector[idx++] = landmark.x
            vector[idx++] = landmark.y
            vector[idx++] = landmark.z
        }
        return vector
    }
}
