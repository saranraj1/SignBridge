package com.signbridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.signbridge.app.camera.CameraManager
import com.signbridge.app.databinding.ActivityMainBinding
import com.signbridge.app.gesture.GestureConfig
import com.signbridge.app.gesture.GesturePrototype
import com.signbridge.app.gesture.MatchStatus
import com.signbridge.app.gesture.PrototypeMatcher
import com.signbridge.app.gesture.SequenceStatus
import com.signbridge.app.gesture.TemporalBuffer
import com.signbridge.app.gesture.TemporalSequence
import com.signbridge.app.preprocessing.LandmarkNormalizer
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.HandLandmarkerHelper
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult
import kotlin.math.sin

/**
 * Main Activity for SignBridge+ (Milestones M1 + M2 + M3).
 *
 * Full Pipeline:
 * Live Camera Preview -> MediaPipe Hand Landmarker -> Landmark Normalization ->
 * 30-Frame Rolling Temporal Buffer -> 1-NN Dynamic Time Warping (DTW) -> Threshold Gating (MATCH / UNKNOWN)
 */
class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraManager: CameraManager

    // M2 Preprocessing and Gesture Buffering
    private val landmarkNormalizer = LandmarkNormalizer()
    private val temporalBuffer = TemporalBuffer(windowSize = GestureConfig.DEFAULT_TEMPORAL_WINDOW_SIZE)

    // M3 DTW Prototype Matching
    private val prototypeMatcher = PrototypeMatcher()

    // Performance instrumentation
    private var lastFpsTimestamp: Long = 0L
    private var frameCount: Int = 0
    private var currentFps: Double = 0.0

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                binding.permissionContainer.visibility = View.GONE
                startCameraPipeline()
            } else {
                binding.permissionContainer.visibility = View.VISIBLE
                Toast.makeText(
                    this,
                    "Camera permission is required for live hand tracking",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDeviceInfo()
        setupListeners()
        initHandLandmarker()
        initBenchmarkPrototypes()

        if (hasCameraPermission()) {
            binding.permissionContainer.visibility = View.GONE
            startCameraPipeline()
        } else {
            binding.permissionContainer.visibility = View.VISIBLE
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupDeviceInfo() {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE
        val sdkInt = Build.VERSION.SDK_INT
        binding.tvDeviceInfo.text = "Device: $manufacturer $model (Android $androidVersion, API $sdkInt) | Local DTW"
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Tap metrics card to switch between front and back camera
        binding.metricsCard.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.switchCamera()
                temporalBuffer.clear()
                val lens = if (cameraManager.isFrontCamera) "Front" else "Back"
                Toast.makeText(this, "Switched to $lens Camera", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initHandLandmarker() {
        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            currentDelegate = HandLandmarkerHelper.DELEGATE_CPU,
            landmarkerListener = this
        )
    }

    /**
     * Initializes in-memory benchmark gesture prototypes for M3 testing.
     */
    private fun initBenchmarkPrototypes() {
        fun makeSyntheticPrototype(id: String, name: String, trajectoryFunc: (Float, Int) -> Triple<Float, Float, Float>): GesturePrototype {
            val frames = (0 until 30).map { step ->
                val progress = step.toFloat() / 29f
                val points = (0 until 21).map { i ->
                    val (dx, dy, dz) = trajectoryFunc(progress, i)
                    NormalizedLandmarkPoint(
                        x = (i * 0.04f) + dx,
                        y = (i * 0.03f) + dy,
                        z = (i * 0.01f) + dz
                    )
                }
                NormalizedLandmarkFrame(
                    timestampMs = (step * 33L),
                    handedness = "Right",
                    landmarks = points,
                    handScale = 1.0f,
                    rawWristPosition = LandmarkPoint(0.5f, 0.5f, 0.0f)
                )
            }
            return GesturePrototype(id, name, TemporalSequence(frames, 30, true))
        }

        // Prototype 1: Lateral Palm Wave
        val waveProto = makeSyntheticPrototype("proto_palm_wave", "PALM_WAVE") { p, _ ->
            val wave = (sin(p * 2 * Math.PI) * 0.35f).toFloat()
            Triple(wave, 0.0f, 0.0f)
        }

        // Prototype 2: Pinch Tap
        val pinchProto = makeSyntheticPrototype("proto_pinch_tap", "PINCH_TAP") { p, i ->
            val pinch = if (i in listOf(4, 8)) (1.0f - p) * 0.25f else 0.0f
            Triple(pinch, pinch, 0.0f)
        }

        // Prototype 3: Swipe Up
        val swipeProto = makeSyntheticPrototype("proto_swipe_up", "SWIPE_UP") { p, _ ->
            Triple(0.0f, -p * 0.45f, p * 0.05f)
        }

        prototypeMatcher.addPrototype(waveProto)
        prototypeMatcher.addPrototype(pinchProto)
        prototypeMatcher.addPrototype(swipeProto)
    }

    private fun startCameraPipeline() {
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.previewView,
            onFrameAvailable = { imageProxy, isFrontCamera ->
                handLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
            }
        )
        cameraManager.startCamera()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    // --- HandLandmarkerHelper.LandmarkerListener Callbacks ---

    override fun onResults(result: VisionFrameResult) {
        // Measure real FPS from incoming results
        val now = SystemClock.uptimeMillis()
        frameCount++
        if (now - lastFpsTimestamp >= 1000) {
            currentFps = (frameCount * 1000.0) / (now - lastFpsTimestamp)
            frameCount = 0
            lastFpsTimestamp = now
        }

        // M2: Process landmark normalization and append to rolling temporal buffer
        val normalizedFrame = if (GestureConfig.DEFAULT_NORMALIZATION_ENABLED) {
            landmarkNormalizer.normalize(result)
        } else {
            null
        }
        val sequenceStatus = temporalBuffer.addFrame(normalizedFrame)

        // M3: Perform 1-NN DTW Prototype Matching if sequence is ready
        val snapshot = temporalBuffer.getSnapshot()
        val recognitionResult = if (snapshot.isReady) {
            prototypeMatcher.match(snapshot, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        } else {
            com.signbridge.app.gesture.RecognitionResult.sequenceNotReady(GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        }

        runOnUiThread {
            // Update landmark overlay canvas
            binding.overlayView.setResults(result)

            // Update live vision debug metrics UI
            if (result.hasHands) {
                binding.tvHandDetected.text = getString(R.string.hand_detected_yes)
                binding.tvHandDetected.setTextColor(Color.parseColor("#FF00E676")) // Green

                val handsCount = result.hands.size
                val landmarksCount = result.totalLandmarksCount
                val handsLabel = if (handsCount == 1) "1 hand" else "$handsCount hands"
                binding.tvHandsCount.text = "$handsLabel ($landmarksCount pts)"

                binding.tvStatusBadge.text = "TRACKING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FF00E676"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                binding.tvHandDetected.text = getString(R.string.hand_detected_no)
                binding.tvHandDetected.setTextColor(Color.parseColor("#FFFF5252")) // Red
                binding.tvHandsCount.text = "0 hands (0 pts)"

                binding.tvStatusBadge.text = "SEARCHING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF9100"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#33FF9100"))
            }

            // Performance metrics
            binding.tvFps.text = if (currentFps > 0) "${String.format("%.1f", currentFps)} FPS" else "-- FPS"
            binding.tvInferenceTime.text = "${result.inferenceLatencyMs} ms"

            // M2: Temporal Buffer HUD
            val currentBufSize = temporalBuffer.size
            val maxWindowSize = temporalBuffer.windowSize
            binding.tvBufferCount.text = "$currentBufSize / $maxWindowSize frames"

            when (sequenceStatus) {
                SequenceStatus.READY -> {
                    binding.tvSequenceStatus.text = "READY | Norm: YES"
                    binding.tvSequenceStatus.setTextColor(Color.parseColor("#FF00E676")) // Green
                }
                SequenceStatus.FILLING -> {
                    binding.tvSequenceStatus.text = "FILLING | Norm: YES"
                    binding.tvSequenceStatus.setTextColor(Color.parseColor("#FF00E5FF")) // Cyan
                }
                SequenceStatus.EMPTY -> {
                    binding.tvSequenceStatus.text = "EMPTY | Norm: YES"
                    binding.tvSequenceStatus.setTextColor(Color.parseColor("#FFFF9100")) // Orange
                }
            }

            // M3: DTW Recognition HUD
            binding.tvNearestMatch.text = recognitionResult.bestMatch?.displayName ?: "NONE"
            binding.tvDtwDistance.text = if (recognitionResult.nearestDistance.isFinite()) {
                "${String.format("%.2f", recognitionResult.nearestDistance)} / ${String.format("%.1f", recognitionResult.threshold)}"
            } else {
                "-- / ${String.format("%.1f", recognitionResult.threshold)}"
            }

            when (recognitionResult.status) {
                MatchStatus.MATCH -> {
                    binding.tvRecognitionResult.text = "MATCH (${recognitionResult.recognizedLabel})"
                    binding.tvRecognitionResult.setTextColor(Color.parseColor("#FF00E676")) // Green
                }
                MatchStatus.UNKNOWN -> {
                    binding.tvRecognitionResult.text = "UNKNOWN"
                    binding.tvRecognitionResult.setTextColor(Color.parseColor("#FFFF9100")) // Orange
                }
                MatchStatus.SEQUENCE_NOT_READY -> {
                    binding.tvRecognitionResult.text = "BUFFERING"
                    binding.tvRecognitionResult.setTextColor(Color.parseColor("#80FFFFFF"))
                }
                MatchStatus.NO_PROTOTYPES -> {
                    binding.tvRecognitionResult.text = "NO PROTOTYPES"
                    binding.tvRecognitionResult.setTextColor(Color.parseColor("#80FFFFFF"))
                }
            }

            binding.tvDtwLatency.text = if (recognitionResult.totalLatencyMs > 0) {
                "${String.format("%.1f", recognitionResult.totalLatencyMs)} ms"
            } else {
                "-- ms"
            }

            if (frameCount % 30 == 0) {
                Log.i(
                    "SignBridgeMetrics",
                    "Status: ${binding.tvStatusBadge.text} | Hands: ${result.hands.size} | Buffer: $currentBufSize/$maxWindowSize | Nearest: ${recognitionResult.bestMatch?.displayName ?: "NONE"} (Dist: ${if (recognitionResult.nearestDistance.isFinite()) String.format("%.2f", recognitionResult.nearestDistance) else "--"}) -> ${recognitionResult.status} | DTW: ${String.format("%.1f", recognitionResult.totalLatencyMs)}ms | FPS: ${String.format("%.1f", currentFps)} | Latency: ${result.inferenceLatencyMs}ms"
                )
            }
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, "HandLandmarker error: $error")
        runOnUiThread {
            binding.tvStatusBadge.text = "ERROR"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF5252"))
            binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#33FF5252"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::handLandmarkerHelper.isInitialized) {
            handLandmarkerHelper.clearHandLandmarker()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
