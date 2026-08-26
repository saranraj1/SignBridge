package com.signbridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.signbridge.app.gesture.GestureProfile
import com.signbridge.app.gesture.GestureProfileStorage
import com.signbridge.app.gesture.GesturePrototype
import com.signbridge.app.gesture.GestureSegmenter
import com.signbridge.app.gesture.MatchStatus
import com.signbridge.app.gesture.PersonalGestureStore
import com.signbridge.app.gesture.PrototypeMatcher
import com.signbridge.app.gesture.RecognitionResult
import com.signbridge.app.gesture.SegmentationEvent
import com.signbridge.app.gesture.SegmenterState
import com.signbridge.app.gesture.TemporalSequence
import com.signbridge.app.preprocessing.LandmarkNormalizer
import com.signbridge.app.preprocessing.NormalizedLandmarkFrame
import com.signbridge.app.preprocessing.NormalizedLandmarkPoint
import com.signbridge.app.vision.HandLandmarkerHelper
import com.signbridge.app.vision.LandmarkPoint
import com.signbridge.app.vision.VisionFrameResult

/**
 * Recognition UI Display Lifecycle states.
 */
enum class DisplayLifecycleState {
    SEARCHING,
    CAPTURING,
    RECOGNIZING,
    RESULT_DISPLAY
}

/**
 * Main Activity for SignBridge+ (M4.5 Event-Driven Gesture Segmentation & Recognition).
 *
 * Full Pipeline:
 * CameraX Live Preview -> MediaPipe Tasks Hand Landmarker -> Landmark Normalizer ->
 * Event-Driven Gesture Segmenter -> 1-NN DTW Recognition (Executed ONCE per gesture) -> Result Hold & Auto-Reset.
 */
class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraManager: CameraManager

    // M2 Preprocessing
    private val landmarkNormalizer = LandmarkNormalizer()

    // M4.5 Event-Driven Gesture Segmenter
    private val gestureSegmenter = GestureSegmenter()

    // M3/M4 Personal Store, Matcher, and Teach Mode Controller
    private val personalGestureStore = PersonalGestureStore()
    private val prototypeMatcher = PrototypeMatcher()
    private val enrollmentController = EnrollmentController()

    // Recognition Lifecycle & Result Hold
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayState: DisplayLifecycleState = DisplayLifecycleState.SEARCHING
    private var lastActiveResult: RecognitionResult? = null
    private var lastResultTimestamp: Long = 0L
    private var dtwCallCount: Long = 0L

    // Performance & Telemetry Tracking
    private var lastFpsTimestamp: Long = 0L
    private var frameCount: Int = 0
    private var currentFps: Double = 0.0
    private var lastVelocity: Float = 0f

    private val resultResetRunnable = Runnable {
        if (displayState == DisplayLifecycleState.RESULT_DISPLAY) {
            displayState = DisplayLifecycleState.SEARCHING
            lastActiveResult = null
            updateRecognitionDisplay()
        }
    }

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

        setupListeners()
        initHandLandmarker()
        loadOrInitializeGestureProfiles()

        if (hasCameraPermission()) {
            binding.permissionContainer.visibility = View.GONE
            startCameraPipeline()
        } else {
            binding.permissionContainer.visibility = View.VISIBLE
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Tap metrics card to switch between front and back camera
        binding.metricsCard.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.switchCamera()
                gestureSegmenter.reset()
                displayState = DisplayLifecycleState.SEARCHING
                lastActiveResult = null
                val lens = if (cameraManager.isFrontCamera) "Front" else "Back"
                Toast.makeText(this, "Switched to $lens Camera", Toast.LENGTH_SHORT).show()
            }
        }

        // Open Teach Mode
        binding.btnStartTeachMode.setOnClickListener {
            enrollmentController.startTeaching()
            gestureSegmenter.reset()
            displayState = DisplayLifecycleState.SEARCHING
            updateTeachUi()
        }

        // Cancel Teach Mode
        binding.btnCancelTeach.setOnClickListener {
            enrollmentController.cancel()
            gestureSegmenter.reset()
            displayState = DisplayLifecycleState.SEARCHING
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
                    gestureSegmenter.reset()
                    updateTeachUi()
                }
                EnrollmentState.LABELING -> {
                    val label = binding.etGestureLabel.text.toString()
                    val result = enrollmentController.saveGesture(label, personalGestureStore, prototypeMatcher)
                    if (result.isSuccess) {
                        val profile = result.getOrNull()
                        GestureProfileStorage.saveProfiles(this, personalGestureStore.getAllProfiles())

                        val intraMean = profile?.meanIntraDistance() ?: 0.0
                        Toast.makeText(
                            this,
                            "✓ Gesture '${profile?.label}' saved! (Intra: ${String.format("%.2f", intraMean)})",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.etGestureLabel.setText("")
                        gestureSegmenter.reset()
                        displayState = DisplayLifecycleState.SEARCHING
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
                binding.tvTeachInstructions.text = "Position your hand in camera view, then tap 'RECORD SAMPLE 1'."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "RECORD SAMPLE 1"
            }
            EnrollmentState.RECORDING_1 -> {
                binding.tvSampleProgressDots.text = "◐ ○ ○"
                binding.tvTeachStepTitle.text = "Recording Sample 1/3..."
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (auto-captured on completion)..."
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
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (auto-captured on completion)..."
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
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (auto-captured on completion)..."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.GONE
            }
            EnrollmentState.CAPTURED_3,
            EnrollmentState.LABELING -> {
                binding.tvSampleProgressDots.text = "● ● ●"
                binding.tvTeachStepTitle.text = "All 3 Samples Captured! ✓"
                binding.tvTeachInstructions.text = "Enter a name for this custom gesture and tap SAVE GESTURE."
                binding.labelInputContainer.visibility = View.VISIBLE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "SAVE GESTURE"
            }
            else -> {}
        }
    }

    private fun loadOrInitializeGestureProfiles() {
        val savedProfiles = GestureProfileStorage.loadProfiles(this)
        personalGestureStore.clearAll()
        prototypeMatcher.clearPrototypes()

        if (savedProfiles.isNotEmpty()) {
            for (profile in savedProfiles) {
                personalGestureStore.addProfile(profile)
                for (proto in profile.prototypes) {
                    prototypeMatcher.addPrototype(proto)
                }
            }
            Log.i(TAG, "Restored ${savedProfiles.size} gesture profiles (${prototypeMatcher.prototypeCount} prototypes) from local storage")
        } else {
            initInitialBenchmarkProfile()
            GestureProfileStorage.saveProfiles(this, personalGestureStore.getAllProfiles())
        }
    }

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

        // M2: Landmark normalization
        val normalizedFrame = if (GestureConfig.DEFAULT_NORMALIZATION_ENABLED && result.hasHands) {
            landmarkNormalizer.normalize(result)
        } else {
            null
        }

        // Feed normalized frame to event-driven GestureSegmenter
        val segEvent = gestureSegmenter.processFrame(normalizedFrame)
        var sampleJustCaptured = false

        when (segEvent) {
            is SegmentationEvent.Progress -> {
                lastVelocity = segEvent.currentVelocity
                if (segEvent.state == SegmenterState.CAPTURING) {
                    mainHandler.removeCallbacks(resultResetRunnable)
                    displayState = DisplayLifecycleState.CAPTURING
                }
            }

            is SegmentationEvent.Completed -> {
                val completedSeq = segEvent.sequence
                val stats = completedSeq.computeStatistics()

                Log.i(
                    "GestureSegmenter",
                    "Completed Gesture: raw=${segEvent.rawFrameCount}p -> trimmed=${segEvent.trimmedFrameCount}p, " +
                            "dur=${completedSeq.durationMs}ms, meanVel=${String.format("%.4f", segEvent.meanVelocity)}, " +
                            "motionVar=${String.format("%.4f", stats.motionVariance)}"
                )

                if (enrollmentController.isTeaching) {
                    // Teach Mode: Register sample into enrollment FSM
                    sampleJustCaptured = enrollmentController.registerSegmentedSample(completedSeq)
                } else {
                    // Live Recognition Mode: Execute DTW ONCE
                    displayState = DisplayLifecycleState.RECOGNIZING
                    dtwCallCount++

                    val recognitionResult = prototypeMatcher.match(
                        completedSeq,
                        threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD,
                        ambiguityMargin = GestureConfig.DEFAULT_AMBIGUITY_MARGIN
                    )

                    lastActiveResult = recognitionResult
                    lastResultTimestamp = now
                    displayState = DisplayLifecycleState.RESULT_DISPLAY

                    Log.i(
                        "EventRecognition",
                        "DTW #$dtwCallCount Result: Status=${recognitionResult.status} | " +
                                "Best=${recognitionResult.bestMatch?.displayName ?: "None"} (Dist=${String.format("%.2f", recognitionResult.nearestDistance)}) | " +
                                "Second=${recognitionResult.runnerUpMatch?.displayName ?: "None"} (Dist=${String.format("%.2f", recognitionResult.runnerUpDistance)}, Margin=${String.format("%.2f", recognitionResult.margin)}) | " +
                                "Candidates: [${recognitionResult.formatCandidateDistances()}]"
                    )

                    // Schedule auto-reset back to SEARCHING after timeout
                    mainHandler.removeCallbacks(resultResetRunnable)
                    mainHandler.postDelayed(resultResetRunnable, GestureConfig.RESULT_DISPLAY_DURATION_MS)
                }
            }

            is SegmentationEvent.Rejected -> {
                Log.d("GestureSegmenter", "Rejected: ${segEvent.reason}")
            }
        }

        runOnUiThread {
            binding.overlayView.setResults(result)

            if (sampleJustCaptured) {
                updateTeachUi()
            }

            // Top Status Badge & Hand Tracking
            if (result.hasHands) {
                binding.tvStatusBadge.text = if (enrollmentController.isTeaching) "TEACHING" else "TRACKING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FF00E676"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                binding.tvStatusBadge.text = "SEARCHING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF9100"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#33FF9100"))
            }

            updateRecognitionDisplay(result.hasHands)

            // Performance & Enrolled Profiles
            val fpsStr = if (currentFps > 0) String.format("%.1f", currentFps) else "--"
            binding.tvFpsLatency.text = "$fpsStr FPS / ${result.inferenceLatencyMs} ms"

            val pCount = personalGestureStore.profileCount
            val protoCount = personalGestureStore.totalPrototypeCount
            binding.tvEnrolledCount.text = "$pCount profiles ($protoCount protos | #$dtwCallCount)"
        }
    }

    /**
     * Updates HUD UI elements reflecting the event-driven recognition lifecycle.
     */
    private fun updateRecognitionDisplay(hasHands: Boolean = true) {
        if (enrollmentController.isTeaching) {
            binding.tvRecognitionStatus.text = "TEACH MODE"
            binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E5FF"))
            binding.tvRecognizedLabel.text = enrollmentController.state.name
            binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FF00E5FF"))
            binding.tvBestSecondDist.text = "-- / --"
            binding.tvMarginThreshold.text = "-- / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
            binding.tvSeqIdDuration.text = "Cap: ${gestureSegmenter.currentAccumulatedCount} frames"
            return
        }

        when (displayState) {
            DisplayLifecycleState.SEARCHING -> {
                binding.tvRecognitionStatus.text = if (hasHands) "IDLE" else "SEARCHING"
                binding.tvRecognitionStatus.setTextColor(if (hasHands) Color.parseColor("#FF00E676") else Color.parseColor("#FFFF9100"))
                binding.tvRecognizedLabel.text = "--"
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#80FFFFFF"))
                binding.tvBestSecondDist.text = "-- / --"
                binding.tvMarginThreshold.text = "-- / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
                binding.tvSeqIdDuration.text = "Idle | Vel: ${String.format("%.4f", lastVelocity)}"
            }

            DisplayLifecycleState.CAPTURING -> {
                binding.tvRecognitionStatus.text = "CAPTURING..."
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvRecognizedLabel.text = "SIGNING..."
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvBestSecondDist.text = "-- / --"
                binding.tvMarginThreshold.text = "-- / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
                binding.tvSeqIdDuration.text = "Frames: ${gestureSegmenter.currentAccumulatedCount} | Vel: ${String.format("%.4f", lastVelocity)}"
            }

            DisplayLifecycleState.RECOGNIZING -> {
                binding.tvRecognitionStatus.text = "RECOGNIZING..."
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FFFFD600"))
                binding.tvRecognizedLabel.text = "MATCHING..."
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FFFFD600"))
            }

            DisplayLifecycleState.RESULT_DISPLAY -> {
                val res = lastActiveResult
                if (res != null) {
                    when (res.status) {
                        MatchStatus.MATCH -> {
                            binding.tvRecognitionStatus.text = "MATCH"
                            binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E676"))
                            binding.tvRecognizedLabel.text = res.recognizedLabel
                            binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FF00E676"))
                        }
                        MatchStatus.UNKNOWN -> {
                            binding.tvRecognitionStatus.text = "UNKNOWN"
                            binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FFFF9100"))
                            binding.tvRecognizedLabel.text = "UNKNOWN"
                            binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FFFF9100"))
                        }
                        MatchStatus.AMBIGUOUS -> {
                            binding.tvRecognitionStatus.text = "AMBIGUOUS"
                            binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FFFF5252"))
                            binding.tvRecognizedLabel.text = "AMBIGUOUS"
                            binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FFFF5252"))
                        }
                        else -> {
                            binding.tvRecognitionStatus.text = "--"
                            binding.tvRecognizedLabel.text = "--"
                        }
                    }

                    val bestDistStr = if (res.nearestDistance.isFinite()) String.format("%.2f", res.nearestDistance) else "--"
                    val runnerUpDistStr = if (res.runnerUpDistance.isFinite()) String.format("%.2f", res.runnerUpDistance) else "--"
                    val marginStr = if (res.margin.isFinite() && res.margin > 0) String.format("%.2f", res.margin) else "--"
                    binding.tvBestSecondDist.text = "$bestDistStr / $runnerUpDistStr"
                    binding.tvMarginThreshold.text = "$marginStr / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
                    binding.tvSeqIdDuration.text = "DTW #${dtwCallCount} | ${String.format("%.1f", res.totalLatencyMs)}ms"
                }
            }
        }

        binding.tvMotionHand.text = "V:${String.format("%.4f", lastVelocity)}"
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
        mainHandler.removeCallbacks(resultResetRunnable)
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
