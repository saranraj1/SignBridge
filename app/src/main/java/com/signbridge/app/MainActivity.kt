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
import com.signbridge.app.gesture.EnrollmentController
import com.signbridge.app.gesture.EnrollmentState
import com.signbridge.app.gesture.GestureConfig
import com.signbridge.app.gesture.GesturePrototype
import com.signbridge.app.gesture.MatchStatus
import com.signbridge.app.gesture.PersonalGestureStore
import com.signbridge.app.gesture.PrototypeMatcher
import com.signbridge.app.gesture.RecognitionResult
import com.signbridge.app.gesture.SequenceStatus
import com.signbridge.app.gesture.TemporalBuffer
import com.signbridge.app.gesture.TemporalSequence
import com.signbridge.app.preprocessing.LandmarkNormalizer
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.HandLandmarkerHelper
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult

/**
 * Main Activity for SignBridge+ (Milestones M1 through M4).
 *
 * Full Pipeline:
 * CameraX Live Preview -> MediaPipe Tasks Hand Landmarker -> Landmark Normalizer ->
 * 30-Frame Rolling Temporal Buffer -> Teach Mode (3-Shot Capture) / 1-NN DTW Recognition
 */
class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraManager: CameraManager

    // M2 Preprocessing and Buffer
    private val landmarkNormalizer = LandmarkNormalizer()
    private val temporalBuffer = TemporalBuffer(windowSize = GestureConfig.DEFAULT_TEMPORAL_WINDOW_SIZE)

    // M3/M4 Personal Store, Matcher, and Teach Mode Controller
    private val personalGestureStore = PersonalGestureStore()
    private val prototypeMatcher = PrototypeMatcher()
    private val enrollmentController = EnrollmentController()

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
        initInitialBenchmarkProfile()

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
        binding.tvDeviceInfo.text = "Device: $manufacturer $model (Android $androidVersion, API $sdkInt) | 3-Shot DTW"
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

        // Open Teach Mode
        binding.btnStartTeachMode.setOnClickListener {
            enrollmentController.startTeaching()
            temporalBuffer.clear()
            updateTeachUi()
        }

        // Cancel Teach Mode
        binding.btnCancelTeach.setOnClickListener {
            enrollmentController.cancel()
            temporalBuffer.clear()
            updateTeachUi()
            Toast.makeText(this, "Teach Mode cancelled", Toast.LENGTH_SHORT).show()
        }

        // Teach Mode Action Button (Record Sample / Save)
        binding.btnTeachAction.setOnClickListener {
            when (enrollmentController.state) {
                EnrollmentState.TEACH_INTRO,
                EnrollmentState.CAPTURED_1,
                EnrollmentState.CAPTURED_2 -> {
                    enrollmentController.startRecordingCurrentSample()
                    temporalBuffer.clear()
                    updateTeachUi()
                }
                EnrollmentState.LABELING -> {
                    val label = binding.etGestureLabel.text.toString()
                    val result = enrollmentController.saveGesture(label, personalGestureStore, prototypeMatcher)
                    if (result.isSuccess) {
                        val profile = result.getOrNull()
                        val intraMean = profile?.meanIntraDistance() ?: 0.0
                        Toast.makeText(
                            this,
                            "✓ Gesture '${profile?.label}' learned (Mean Intra Dist: ${String.format("%.2f", intraMean)})",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.etGestureLabel.setText("")
                        temporalBuffer.clear()
                        updateTeachUi()
                    } else {
                        Toast.makeText(
                            this,
                            "Error: ${result.exceptionOrNull()?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                else -> {}
            }
        }
    }

    private fun updateTeachUi() {
        if (!enrollmentController.isTeaching) {
            binding.teachModeCard.visibility = View.GONE
            binding.btnStartTeachMode.visibility = View.VISIBLE
            return
        }

        binding.teachModeCard.visibility = View.VISIBLE
        binding.btnStartTeachMode.visibility = View.GONE

        when (enrollmentController.state) {
            EnrollmentState.TEACH_INTRO -> {
                binding.tvSampleProgressDots.text = "○ ○ ○"
                binding.tvTeachStepTitle.text = "Sample 1 of 3"
                binding.tvTeachInstructions.text = "Tap 'RECORD SAMPLE 1' then perform your gesture steadily."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "RECORD SAMPLE 1"
            }
            EnrollmentState.RECORDING_1 -> {
                binding.tvSampleProgressDots.text = "◐ ○ ○"
                binding.tvTeachStepTitle.text = "Recording Sample 1/3..."
                binding.tvTeachInstructions.text = "Hold / perform your gesture in camera view until 30 frames are captured."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.GONE
            }
            EnrollmentState.CAPTURED_1 -> {
                binding.tvSampleProgressDots.text = "● ○ ○"
                binding.tvTeachStepTitle.text = "Sample 1 Captured! ✓"
                binding.tvTeachInstructions.text = "Great! Tap 'RECORD SAMPLE 2' and perform the same gesture again."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "RECORD SAMPLE 2"
            }
            EnrollmentState.RECORDING_2 -> {
                binding.tvSampleProgressDots.text = "● ◐ ○"
                binding.tvTeachStepTitle.text = "Recording Sample 2/3..."
                binding.tvTeachInstructions.text = "Hold / perform your gesture in camera view until 30 frames are captured."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.GONE
            }
            EnrollmentState.CAPTURED_2 -> {
                binding.tvSampleProgressDots.text = "● ● ○"
                binding.tvTeachStepTitle.text = "Sample 2 Captured! ✓"
                binding.tvTeachInstructions.text = "Great! Tap 'RECORD SAMPLE 3' for the final demonstration."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "RECORD SAMPLE 3"
            }
            EnrollmentState.RECORDING_3 -> {
                binding.tvSampleProgressDots.text = "● ● ◐"
                binding.tvTeachStepTitle.text = "Recording Sample 3/3..."
                binding.tvTeachInstructions.text = "Hold / perform your gesture in camera view until 30 frames are captured."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.GONE
            }
            EnrollmentState.CAPTURED_3,
            EnrollmentState.LABELING -> {
                binding.tvSampleProgressDots.text = "● ● ●"
                binding.tvTeachStepTitle.text = "All 3 Samples Captured! ✓"
                binding.tvTeachInstructions.text = "Enter a name for this custom gesture and tap SAVE."
                binding.labelInputContainer.visibility = View.VISIBLE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "SAVE GESTURE"
            }
            else -> {}
        }
    }

    /**
     * Initializes an initial benchmark gesture profile ("HELP") with 3 demonstrations.
     */
    private fun initInitialBenchmarkProfile() {
        fun makeSequence(noise: Float): TemporalSequence {
            val frames = (0 until 30).map { step ->
                val progress = step.toFloat() / 29f
                val points = (0 until 21).map { i ->
                    NormalizedLandmarkPoint(
                        x = (i * 0.04f) + noise,
                        y = (i * 0.03f) - (progress * 0.35f) + noise,
                        z = (i * 0.01f)
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
            return TemporalSequence(frames, 30, true)
        }

        val helpDemos = listOf(makeSequence(0.0f), makeSequence(0.01f), makeSequence(0.02f))
        personalGestureStore.createProfile("HELP", helpDemos)

        for (proto in personalGestureStore.getAllPrototypes()) {
            prototypeMatcher.addPrototype(proto)
        }
    }

    private fun initHandLandmarker() {
        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
            currentDelegate = HandLandmarkerHelper.DELEGATE_CPU,
            landmarkerListener = this
        )
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
        val now = SystemClock.uptimeMillis()
        frameCount++
        if (now - lastFpsTimestamp >= 1000) {
            currentFps = (frameCount * 1000.0) / (now - lastFpsTimestamp)
            frameCount = 0
            lastFpsTimestamp = now
        }

        // M2: Landmark normalization and buffer update
        val normalizedFrame = if (GestureConfig.DEFAULT_NORMALIZATION_ENABLED) {
            landmarkNormalizer.normalize(result)
        } else {
            null
        }
        val sequenceStatus = temporalBuffer.addFrame(normalizedFrame)
        val snapshot = temporalBuffer.getSnapshot()

        // M4: Handle Teach Mode state machine or live DTW recognition
        var sampleJustCaptured = false
        var recognitionResult: RecognitionResult? = null

        if (enrollmentController.isTeaching) {
            if (enrollmentController.state in listOf(
                    EnrollmentState.RECORDING_1,
                    EnrollmentState.RECORDING_2,
                    EnrollmentState.RECORDING_3
                )
            ) {
                sampleJustCaptured = enrollmentController.processFrame(result.hasHands, snapshot)
                if (sampleJustCaptured) {
                    temporalBuffer.clear()
                }
            }
        } else {
            // Live 1-NN DTW recognition
            recognitionResult = if (snapshot.isReady) {
                prototypeMatcher.match(snapshot, threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
            } else {
                RecognitionResult.sequenceNotReady(GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
            }
        }

        runOnUiThread {
            // Overlay rendering
            binding.overlayView.setResults(result)

            // Update teach UI if sample was captured
            if (sampleJustCaptured) {
                updateTeachUi()
            }

            // Top HUD: Vision & FPS
            if (result.hasHands) {
                binding.tvHandDetected.text = "YES (${result.totalLandmarksCount} pts)"
                binding.tvHandDetected.setTextColor(Color.parseColor("#FF00E676"))

                binding.tvStatusBadge.text = if (enrollmentController.isTeaching) "TEACHING" else "TRACKING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FF00E676"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                binding.tvHandDetected.text = "NO (0 pts)"
                binding.tvHandDetected.setTextColor(Color.parseColor("#FFFF5252"))

                binding.tvStatusBadge.text = "SEARCHING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF9100"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#33FF9100"))
            }

            val fpsStr = if (currentFps > 0) String.format("%.1f", currentFps) else "--"
            binding.tvFpsLatency.text = "$fpsStr FPS / ${result.inferenceLatencyMs} ms"

            // M2: Buffer status
            val currentBufSize = temporalBuffer.size
            val maxWindowSize = temporalBuffer.windowSize
            binding.tvBufferCount.text = "$currentBufSize / $maxWindowSize ($sequenceStatus)"

            // M4: Enrolled profiles and prototypes count
            val pCount = personalGestureStore.profileCount
            val protoCount = personalGestureStore.totalPrototypeCount
            binding.tvEnrolledCount.text = "$pCount profiles ($protoCount protos)"

            // M3/M4: Live Recognition Telemetry
            if (enrollmentController.isTeaching) {
                binding.tvRecognitionResult.text = "TEACH MODE (${enrollmentController.state})"
                binding.tvRecognitionResult.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvDtwMetrics.text = "Capturing ${enrollmentController.capturedCount}/3"
            } else if (recognitionResult != null) {
                when (recognitionResult.status) {
                    MatchStatus.MATCH -> {
                        binding.tvRecognitionResult.text = "MATCH (${recognitionResult.recognizedLabel})"
                        binding.tvRecognitionResult.setTextColor(Color.parseColor("#FF00E676"))
                    }
                    MatchStatus.UNKNOWN -> {
                        binding.tvRecognitionResult.text = "UNKNOWN"
                        binding.tvRecognitionResult.setTextColor(Color.parseColor("#FFFF9100"))
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

                val distStr = if (recognitionResult.nearestDistance.isFinite()) String.format("%.2f", recognitionResult.nearestDistance) else "--"
                val dtwMsStr = if (recognitionResult.totalLatencyMs > 0) "${String.format("%.1f", recognitionResult.totalLatencyMs)} ms" else "-- ms"
                binding.tvDtwMetrics.text = "$distStr / $dtwMsStr"
            }

            if (frameCount % 30 == 0) {
                Log.i(
                    "SignBridgeMetrics",
                    "Status: ${binding.tvStatusBadge.text} | Profiles: $pCount ($protoCount protos) | Buffer: $currentBufSize/$maxWindowSize | Recognized: ${recognitionResult?.recognizedLabel ?: enrollmentController.state.name} | FPS: $fpsStr | Latency: ${result.inferenceLatencyMs}ms"
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
