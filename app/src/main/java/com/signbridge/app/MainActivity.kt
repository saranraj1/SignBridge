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
import com.signbridge.app.gesture.GestureProfile
import com.signbridge.app.gesture.GestureProfileStorage
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
 * Main Activity for SignBridge+ (M4 Diagnostic & Recognition Investigation).
 *
 * Full Pipeline:
 * CameraX Live Preview -> MediaPipe Tasks Hand Landmarker -> Landmark Normalizer ->
 * 30-Frame Rolling Temporal Buffer -> Teach Mode (3-Shot Capture) / 1-NN DTW Recognition (with Margin Gating & Diagnostic HUD)
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

    // Performance & UI State Tracking
    private var lastFpsTimestamp: Long = 0L
    private var frameCount: Int = 0
    private var currentFps: Double = 0.0
    private var lastDisplayedLabel: String = "--"

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
                        // Save all profiles to internal local storage for persistence across restarts
                        GestureProfileStorage.saveProfiles(this, personalGestureStore.getAllProfiles())

                        val intraMean = profile?.meanIntraDistance() ?: 0.0
                        Toast.makeText(
                            this,
                            "✓ Gesture '${profile?.label}' saved & persisted! (Intra: ${String.format("%.2f", intraMean)})",
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
                binding.tvTeachInstructions.text = "Position your hand in camera view, then tap 'RECORD SAMPLE 1'."
                binding.labelInputContainer.visibility = View.GONE
                binding.btnTeachAction.visibility = View.VISIBLE
                binding.btnTeachAction.text = "RECORD SAMPLE 1"
            }
            EnrollmentState.RECORDING_1 -> {
                binding.tvSampleProgressDots.text = "◐ ○ ○"
                binding.tvTeachStepTitle.text = "Recording Sample 1/3..."
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (recording 30 frames)..."
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
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (recording 30 frames)..."
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
                binding.tvTeachInstructions.text = "Perform your gesture steadily in camera view (recording 30 frames)..."
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

    /**
     * Loads persisted gesture profiles from local disk storage.
     * If no saved profiles exist, seeds the initial benchmark profile ("HELP").
     */
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
            // Persist the seeded profile so disk state is immediately initialized
            GestureProfileStorage.saveProfiles(this, personalGestureStore.getAllProfiles())
        }
    }

    /**
     * Initializes the default seed gesture profile ("HELP") with 3 demonstrations.
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
        val normalizedFrame = if (GestureConfig.DEFAULT_NORMALIZATION_ENABLED && result.hasHands) {
            landmarkNormalizer.normalize(result)
        } else {
            null
        }
        val sequenceStatus = temporalBuffer.addFrame(normalizedFrame)
        val snapshot = temporalBuffer.getSnapshot()

        // M4: Handle Teach Mode state machine or live DTW recognition
        var sampleJustCaptured = false
        val recognitionResult: RecognitionResult

        if (enrollmentController.isTeaching) {
            if (enrollmentController.state in listOf(
                    EnrollmentState.RECORDING_1,
                    EnrollmentState.RECORDING_2,
                    EnrollmentState.RECORDING_3
                )
            ) {
                sampleJustCaptured = enrollmentController.processFrame(result.hasHands, snapshot)
                if (sampleJustCaptured) {
                    val stats = snapshot.computeStatistics()
                    Log.i(TAG, "Enrollment Sequence Captured: ${stats.formatSummary()}")
                    temporalBuffer.clear()
                }
            }
            recognitionResult = RecognitionResult.sequenceNotReady(GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
        } else {
            // Live 1-NN DTW recognition
            recognitionResult = if (!result.hasHands) {
                RecognitionResult.noHand(GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
            } else if (snapshot.isReady) {
                prototypeMatcher.match(
                    snapshot,
                    threshold = GestureConfig.DEFAULT_RECOGNITION_THRESHOLD,
                    ambiguityMargin = GestureConfig.DEFAULT_AMBIGUITY_MARGIN
                )
            } else {
                RecognitionResult.sequenceNotReady(GestureConfig.DEFAULT_RECOGNITION_THRESHOLD)
            }

            // Diagnostic trace logging for every recognition cycle
            val liveStats = snapshot.computeStatistics()
            val uiLabelBeforeUpdate = lastDisplayedLabel
            val uiLabelAfterUpdate = when (recognitionResult.status) {
                MatchStatus.MATCH -> recognitionResult.bestMatch?.displayName ?: "--"
                MatchStatus.UNKNOWN, MatchStatus.AMBIGUOUS -> "UNKNOWN"
                MatchStatus.SEQUENCE_NOT_READY -> "BUFFERING"
                MatchStatus.NO_HAND -> "--"
                MatchStatus.NO_PROTOTYPES -> "NO_PROTOTYPES"
            }

            Log.d(
                "RecognitionTrace",
                "Cycle: ts=$now | seqId=#${snapshot.sequenceId} (ts=${snapshot.oldestTimestampMs}..${snapshot.newestTimestampMs}, dur=${snapshot.durationMs}ms) | " +
                        "Best=${recognitionResult.bestMatch?.displayName ?: "None"} (Dist: ${if (recognitionResult.nearestDistance.isFinite()) String.format("%.2f", recognitionResult.nearestDistance) else "INF"}) | " +
                        "Second=${recognitionResult.runnerUpMatch?.displayName ?: "None"} (Dist: ${if (recognitionResult.runnerUpDistance.isFinite()) String.format("%.2f", recognitionResult.runnerUpDistance) else "INF"}, Margin: ${String.format("%.2f", recognitionResult.margin)}) | " +
                        "Status=${recognitionResult.status} | Thresh=${recognitionResult.threshold} | " +
                        "UI_Before='$uiLabelBeforeUpdate' -> UI_After='$uiLabelAfterUpdate' | " +
                        "MotionVar=${String.format("%.4f", liveStats.motionVariance)} | Hand=${liveStats.dominantHandedness} | Scale=${String.format("%.3f", liveStats.averageHandScale)}"
            )

            lastDisplayedLabel = uiLabelAfterUpdate
        }

        runOnUiThread {
            // Overlay rendering
            binding.overlayView.setResults(result)

            // Update teach UI if sample was captured
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

            // HUD Row 1: Recognition Status & Label
            if (enrollmentController.isTeaching) {
                binding.tvRecognitionStatus.text = "TEACH MODE"
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvRecognizedLabel.text = enrollmentController.state.name
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FF00E5FF"))
            } else {
                when (recognitionResult.status) {
                    MatchStatus.MATCH -> {
                        binding.tvRecognitionStatus.text = "MATCH"
                        binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E676"))
                        binding.tvRecognizedLabel.text = recognitionResult.recognizedLabel
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
                    MatchStatus.SEQUENCE_NOT_READY -> {
                        binding.tvRecognitionStatus.text = "BUFFERING"
                        binding.tvRecognitionStatus.setTextColor(Color.parseColor("#80FFFFFF"))
                        binding.tvRecognizedLabel.text = "--"
                        binding.tvRecognizedLabel.setTextColor(Color.parseColor("#80FFFFFF"))
                    }
                    MatchStatus.NO_HAND -> {
                        binding.tvRecognitionStatus.text = "SEARCHING"
                        binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FFFF9100"))
                        binding.tvRecognizedLabel.text = "--"
                        binding.tvRecognizedLabel.setTextColor(Color.parseColor("#80FFFFFF"))
                    }
                    MatchStatus.NO_PROTOTYPES -> {
                        binding.tvRecognitionStatus.text = "NO PROTOTYPES"
                        binding.tvRecognitionStatus.setTextColor(Color.parseColor("#80FFFFFF"))
                        binding.tvRecognizedLabel.text = "--"
                        binding.tvRecognizedLabel.setTextColor(Color.parseColor("#80FFFFFF"))
                    }
                }
            }

            // HUD Row 2: Best Dist, Runner-Up & Margin
            val bestDistStr = if (recognitionResult.nearestDistance.isFinite()) String.format("%.2f", recognitionResult.nearestDistance) else "--"
            val runnerUpDistStr = if (recognitionResult.runnerUpDistance.isFinite()) String.format("%.2f", recognitionResult.runnerUpDistance) else "--"
            val marginStr = if (recognitionResult.margin.isFinite() && recognitionResult.margin > 0) String.format("%.2f", recognitionResult.margin) else "--"
            binding.tvBestSecondDist.text = "$bestDistStr / $runnerUpDistStr"
            binding.tvMarginThreshold.text = "$marginStr / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"

            // HUD Row 3: Motion Variance, Hand & Sequence Details
            val liveStats = snapshot.computeStatistics()
            val motionStr = String.format("%.4f", liveStats.motionVariance)
            val handStr = if (result.hasHands) "${liveStats.dominantHandedness} (${result.totalLandmarksCount}p)" else "None"
            binding.tvMotionHand.text = "$motionStr / $handStr"

            val currentBufSize = temporalBuffer.size
            val maxWindowSize = temporalBuffer.windowSize
            val durMs = snapshot.durationMs
            binding.tvSeqIdDuration.text = "$currentBufSize/$maxWindowSize | #${snapshot.sequenceId} | ${durMs}ms"

            // HUD Row 4: Performance & Hardware Profile
            val fpsStr = if (currentFps > 0) String.format("%.1f", currentFps) else "--"
            binding.tvFpsLatency.text = "$fpsStr FPS / ${result.inferenceLatencyMs} ms"

            val pCount = personalGestureStore.profileCount
            val protoCount = personalGestureStore.totalPrototypeCount
            binding.tvEnrolledCount.text = "$pCount profiles ($protoCount protos)"
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
