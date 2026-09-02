package com.signbridge.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Helper class that wraps Google's MediaPipe Tasks Hand Landmarker.
 *
 * It manages:
 * - Model lifecycle (loading, initialization, inference, cleanup)
 * - LIVE_STREAM asynchronous frame detection
 * - ImageProxy to MPImage rotation & transformation
 * - Converting raw MediaPipe outputs into clean [VisionFrameResult] domain models
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
    private val minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
    private val minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
    private val maxNumHands: Int = DEFAULT_NUM_HANDS,
    private val currentDelegate: Int = DELEGATE_CPU,
    private val landmarkerListener: LandmarkerListener? = null
) {
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    /**
     * Initializes or re-initializes the HandLandmarker instance with configured parameters.
     */
    fun setupHandLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(MP_HAND_LANDMARKER_TASK)

        when (currentDelegate) {
            DELEGATE_CPU -> baseOptionsBuilder.setDelegate(Delegate.CPU)
            DELEGATE_GPU -> baseOptionsBuilder.setDelegate(Delegate.GPU)
        }

        try {
            val baseOptions = baseOptionsBuilder.build()
            val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(minHandDetectionConfidence)
                .setMinTrackingConfidence(minHandTrackingConfidence)
                .setMinHandPresenceConfidence(minHandPresenceConfidence)
                .setNumHands(maxNumHands)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::returnLivestreamResult)
                .setErrorListener(this::returnLivestreamError)

            val options = optionsBuilder.build()
            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "MediaPipe Hand Landmarker initialized successfully (Delegate: $currentDelegate)")
        } catch (e: IllegalStateException) {
            val errorMsg = "MediaPipe Hand Landmarker failed to initialize: ${e.message}"
            Log.e(TAG, errorMsg, e)
            landmarkerListener?.onError(errorMsg)
        } catch (e: RuntimeException) {
            val errorMsg = "MediaPipe Hand Landmarker error: ${e.message}"
            Log.e(TAG, errorMsg, e)
            landmarkerListener?.onError(errorMsg)
        }
    }

    /**
     * Converts a CameraX [ImageProxy] frame and dispatches it asynchronously to MediaPipe.
     * Automatically closes the [ImageProxy] once bitmap extraction is complete to prevent camera pipeline stalls.
     */
    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean = false) {
        val frameTime = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // Convert ImageProxy to Bitmap and close proxy immediately
        val bitmap = imageProxy.toBitmap()
        val srcWidth = imageProxy.width
        val srcHeight = imageProxy.height
        imageProxy.close()

        val matrix = Matrix().apply {
            if (isFrontCamera) {
                // Mirror horizontally around bitmap center before rotation
                preScale(-1f, 1f, srcWidth / 2f, srcHeight / 2f)
            }
            // Rotate to match device screen orientation
            postRotate(rotationDegrees.toFloat())
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, srcWidth, srcHeight,
            matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        try {
            handLandmarker?.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            Log.e(TAG, "Error invoking detectAsync: ${e.message}", e)
            landmarkerListener?.onError("Inference error: ${e.message}")
        }
    }

    /**
     * Callback triggered by MediaPipe on successful landmark detection in LIVE_STREAM mode.
     */
    private fun returnLivestreamResult(
        result: HandLandmarkerResult,
        input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceLatencyMs = finishTimeMs - result.timestampMs()

        val handsList = mutableListOf<HandLandmarkData>()

        val landmarksList = result.landmarks()
        val worldLandmarksList = result.worldLandmarks()
        val handednessesList = result.handednesses()

        for (i in landmarksList.indices) {
            val rawLandmarks = landmarksList[i]
            val landmarkPoints = rawLandmarks.map { landmark ->
                LandmarkPoint(
                    x = landmark.x(),
                    y = landmark.y(),
                    z = landmark.z(),
                    visibility = if (landmark.visibility().isPresent) landmark.visibility().get() else 1.0f,
                    presence = if (landmark.presence().isPresent) landmark.presence().get() else 1.0f
                )
            }

            val worldPoints = if (i < worldLandmarksList.size) {
                worldLandmarksList[i].map { wl ->
                    LandmarkPoint(
                        x = wl.x(),
                        y = wl.y(),
                        z = wl.z(),
                        visibility = if (wl.visibility().isPresent) wl.visibility().get() else 1.0f,
                        presence = if (wl.presence().isPresent) wl.presence().get() else 1.0f
                    )
                }
            } else {
                emptyList()
            }

            val handednessCategory = if (i < handednessesList.size && handednessesList[i].isNotEmpty()) {
                handednessesList[i][0]
            } else {
                null
            }

            val handednessName = handednessCategory?.categoryName() ?: "Unknown"
            val handednessScore = handednessCategory?.score() ?: 0.0f

            handsList.add(
                HandLandmarkData(
                    handedness = handednessName,
                    score = handednessScore,
                    landmarks = landmarkPoints,
                    worldLandmarks = worldPoints
                )
            )
        }

        val visionResult = VisionFrameResult(
            timestampMs = result.timestampMs(),
            hands = handsList,
            inferenceLatencyMs = inferenceLatencyMs,
            inputImageWidth = input.width,
            inputImageHeight = input.height
        )

        landmarkerListener?.onResults(visionResult)
    }

    /**
     * Callback triggered by MediaPipe on runtime inference failure.
     */
    private fun returnLivestreamError(error: RuntimeException) {
        val errorMsg = error.message ?: "Unknown MediaPipe runtime error"
        Log.e(TAG, "MediaPipe LiveStream error: $errorMsg", error)
        landmarkerListener?.onError(errorMsg)
    }

    /**
     * Releases detector resources safely.
     */
    fun clearHandLandmarker() {
        handLandmarker?.close()
        handLandmarker = null
        Log.d(TAG, "Hand Landmarker closed")
    }

    fun isClose(): Boolean {
        return handLandmarker == null
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: VisionFrameResult)
    }

    companion object {
        private const val TAG = "HandLandmarkerHelper"
        const val MP_HAND_LANDMARKER_TASK = "hand_landmarker.task"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1

        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5f
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5f
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5f
        const val DEFAULT_NUM_HANDS = 2
    }
}
