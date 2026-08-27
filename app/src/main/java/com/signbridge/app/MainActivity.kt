package com.signbridge.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.signbridge.app.gesture.DTW
import com.signbridge.app.gesture.DTWResult
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
import com.signbridge.app.vision.HandLandmarkerHelper
import com.signbridge.app.vision.VisionFrameResult

/**
 * Recognition UI Display Lifecycle states.
 *
 * NO_HAND → HAND_DETECTED → SEARCHING → CAPTURING → RECOGNIZING → RESULT_DISPLAY → SEARCHING
 */
enum class DisplayLifecycleState {
    /** MediaPipe sees no hand at all. */
    NO_HAND,
    /** Hand just appeared — stabilizing. */
    HAND_DETECTED,
    /** Hand is stable, waiting for gesture motion. */
    SEARCHING,
    /** Active gesture being captured. */
    CAPTURING,
    /** DTW matching in progress. */
    RECOGNIZING,
    /** Match or Unknown result displayed. */
    RESULT_DISPLAY
}

/**
 * Main Activity for SignBridge+ (M4.5 Forensic Diagnostic Suite).
 */
class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraManager: CameraManager

    // M2 Preprocessing
    private val landmarkNormalizer = LandmarkNormalizer()

    // M4.5 Event-Driven Gesture Segmenter with Manual Bypass
    private val gestureSegmenter = GestureSegmenter(debugLogger = { tag, msg -> Log.d(tag, msg) })

    // M3/M4 Personal Store, Matcher, and Teach Mode Controller
    private val personalGestureStore = PersonalGestureStore()
    private val prototypeMatcher = PrototypeMatcher()
    private val enrollmentController = EnrollmentController()

    // Recognition Lifecycle & Result Hold
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayState: DisplayLifecycleState = DisplayLifecycleState.NO_HAND
    private var lastActiveResult: RecognitionResult? = null
    private var lastResultTimestamp: Long = 0L
    private var dtwCallCount: Long = 0L

    // Performance & Telemetry Tracking
    private var lastFpsTimestamp: Long = 0L
    private var frameCount: Int = 0
    private var currentFps: Double = 0.0
    private var lastVelocity: Float = 0f

    // Phase 4 tracking: Store last 3 teach and last 3 live sequences
    private val teachHistory = mutableListOf<TemporalSequence>()
    private val liveHistory = mutableListOf<TemporalSequence>()

    private val resultResetRunnable = Runnable {
        if (displayState == DisplayLifecycleState.RESULT_DISPLAY) {
            displayState = if (gestureSegmenter.handAbsent) {
                DisplayLifecycleState.NO_HAND
            } else {
                DisplayLifecycleState.SEARCHING
            }
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
                gestureSegmenter.fullReset()
                displayState = DisplayLifecycleState.NO_HAND
                lastActiveResult = null
                val lens = if (cameraManager.isFrontCamera) "Front" else "Back"
                Toast.makeText(this, "Switched to $lens Camera", Toast.LENGTH_SHORT).show()
            }
        }

        // Phase 8: Manual Recording Bypass Button
        binding.btnManualRecord.setOnClickListener {
            if (!gestureSegmenter.isManualRecording) {
                // START manual recording
                gestureSegmenter.startManualRecording()
                binding.btnManualRecord.text = "■ STOP RECORDING & EVALUATE"
                binding.btnManualRecord.setBackgroundColor(Color.parseColor("#FFFF5252"))
                displayState = DisplayLifecycleState.CAPTURING
                updateRecognitionDisplay()
            } else {
                // STOP manual recording
                val event = gestureSegmenter.stopManualRecording()
                binding.btnManualRecord.text = "● MANUAL RECORD [BYPASS SEGMENTER]"
                binding.btnManualRecord.setBackgroundColor(Color.parseColor("#FF6200EE"))

                if (event is SegmentationEvent.Completed) {
                    processCompletedGesture(event)
                } else if (event is SegmentationEvent.Rejected) {
                    Toast.makeText(this, "Manual capture rejected: ${event.reason}", Toast.LENGTH_SHORT).show()
                    displayState = DisplayLifecycleState.SEARCHING
                    updateRecognitionDisplay()
                }
            }
        }

        // Open Teach Mode
        binding.btnStartTeachMode.setOnClickListener {
            enrollmentController.startTeaching()
            gestureSegmenter.fullReset()
            displayState = DisplayLifecycleState.NO_HAND
            updateTeachUi()
        }

        // Cancel Teach Mode
        binding.btnCancelTeach.setOnClickListener {
            enrollmentController.cancel()
            gestureSegmenter.fullReset()
            displayState = DisplayLifecycleState.NO_HAND
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
                    gestureSegmenter.fullReset()
                    updateTeachUi()
                }
                EnrollmentState.LABELING -> {
                    val label = binding.etGestureLabel.text.toString()
                    val result = enrollmentController.saveGesture(label, personalGestureStore, prototypeMatcher)
                    if (result.isSuccess) {
                        val profile = result.getOrNull()
                        GestureProfileStorage.saveProfiles(this, personalGestureStore.getAllProfiles())

                        // Track taught prototypes for Phase 4 Asymmetry
                        if (profile != null) {
                            teachHistory.clear()
                            for (p in profile.prototypes) {
                                teachHistory.add(p.sequence)
                            }
                            // Run Phase 2 Self-Match verification
                            runPhase2SelfMatchTest(profile)
                        }

                        val intraMean = profile?.meanIntraDistance() ?: 0.0
                        Toast.makeText(
                            this,
                            "✓ Gesture '${profile?.label}' saved! (Intra: ${String.format("%.2f", intraMean)})",
                            Toast.LENGTH_LONG
                        ).show()
                        binding.etGestureLabel.setText("")
                        gestureSegmenter.fullReset()
                        displayState = DisplayLifecycleState.NO_HAND
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
            binding.bottomButtonContainer.visibility = View.VISIBLE
            return
        }

        binding.teachModeCard.visibility = View.VISIBLE
        binding.bottomButtonContainer.visibility = View.GONE

        when (enrollmentController.state) {
            EnrollmentState.TEACH_INTRO -> {
                binding.tvSampleProgressDots.text = "○ ○ ○"
                binding.tvTeachStepTitle.text = "Sample 1 of 3"
                binding.tvTeachInstructions.text = "Position your hand in camera view, then tap 'RECORD SAMPLE 1' (or use MANUAL RECORD)."
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
        val restoredProfiles = GestureProfileStorage.loadProfiles(this)
        if (restoredProfiles.isNotEmpty()) {
            personalGestureStore.clearAll()
            prototypeMatcher.clearPrototypes()
            for (profile in restoredProfiles) {
                personalGestureStore.addProfile(profile)
                for (proto in profile.prototypes) {
                    prototypeMatcher.addPrototype(proto)
                }
                val protoSummary = profile.prototypes.joinToString(" | ") { p ->
                    "id=${p.id}, frames=${p.sequence.frameCount}, ready=${p.sequence.isReady}, ws=${p.sequence.windowSize}"
                }
                Log.i(TAG, "  Profile '${profile.label}': ${profile.prototypes.size} protos → [$protoSummary]")
            }
            Log.i(TAG, "Restored ${restoredProfiles.size} gesture profiles (${personalGestureStore.totalPrototypeCount} prototypes) from local storage")
        } else {
            Log.w(TAG, "No saved gesture profiles found. Teach Mode required to enroll gestures.")
        }
    }

    private fun initHandLandmarker() {
        handLandmarkerHelper = HandLandmarkerHelper(
            context = this,
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
            val primaryHand = result.hands.maxByOrNull { it.score } ?: result.hands[0]
            val norm = landmarkNormalizer.normalizeHand(primaryHand, result.timestampMs)
            if (norm != null && frameCount % 30 == 0) {
                // Phase 6: Periodic normalization diagnostic check
                landmarkNormalizer.logDiagnosticVerification(primaryHand, norm)
            }
            norm
        } else {
            null
        }

        // Feed normalized frame to event-driven GestureSegmenter
        val segEvent = gestureSegmenter.processFrame(normalizedFrame)
        var sampleJustCaptured = false

        when (segEvent) {
            is SegmentationEvent.Progress -> {
                lastVelocity = segEvent.currentVelocity
                when (segEvent.state) {
                    SegmenterState.STABILIZING -> {
                        if (displayState != DisplayLifecycleState.RESULT_DISPLAY) {
                            displayState = DisplayLifecycleState.HAND_DETECTED
                        }
                    }
                    SegmenterState.IDLE -> {
                        if (displayState != DisplayLifecycleState.RESULT_DISPLAY) {
                            displayState = DisplayLifecycleState.SEARCHING
                        }
                    }
                    SegmenterState.CAPTURING,
                    SegmenterState.MANUAL_RECORDING -> {
                        mainHandler.removeCallbacks(resultResetRunnable)
                        displayState = DisplayLifecycleState.CAPTURING
                    }
                }
            }

            is SegmentationEvent.Completed -> {
                sampleJustCaptured = processCompletedGesture(segEvent)
            }

            is SegmentationEvent.Rejected -> {
                Log.d("GestureSegmenter", "Rejected: ${segEvent.reason}")
            }
        }

        // Handle explicit NO_HAND when no hand is detected
        if (!result.hasHands && displayState != DisplayLifecycleState.RESULT_DISPLAY) {
            displayState = DisplayLifecycleState.NO_HAND
            lastActiveResult = null
            mainHandler.removeCallbacks(resultResetRunnable)
        }

        runOnUiThread {
            binding.overlayView.setResults(result)

            if (sampleJustCaptured) {
                updateTeachUi()
            }

            // Top Status Badge
            if (result.hasHands) {
                binding.tvStatusBadge.text = if (enrollmentController.isTeaching) "TEACHING" else "TRACKING"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FF00E676"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#3300E676"))
            } else {
                binding.tvStatusBadge.text = "NO HAND"
                binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF5252"))
                binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#33FF5252"))
            }

            updateRecognitionDisplay()

            // Performance & Enrolled Profiles
            val fpsStr = if (currentFps > 0) String.format("%.1f", currentFps) else "--"
            binding.tvFpsLatency.text = "$fpsStr FPS / ${result.inferenceLatencyMs} ms"

            val pCount = personalGestureStore.profileCount
            val protoCount = personalGestureStore.totalPrototypeCount
            binding.tvEnrolledCount.text = "$pCount profiles ($protoCount protos | #$dtwCallCount)"
        }
    }

    /**
     * Processes a completed gesture sequence from either automatic segmentation or manual bypass.
     */
    private fun processCompletedGesture(segEvent: SegmentationEvent.Completed): Boolean {
        val completedSeq = segEvent.sequence
        val now = SystemClock.uptimeMillis()
        var sampleCaptured = false

        // Phase 1: Comprehensive trace of the completed gesture
        logDetailedSequenceTrace("M4ForensicTrace", "COMPLETED_GESTURE", completedSeq, segEvent.endReason)

        if (enrollmentController.isTeaching) {
            // Teach Mode: Register sample into enrollment FSM
            sampleCaptured = enrollmentController.registerSegmentedSample(completedSeq)
            Log.i("TeachMode", "Registered Sample ${enrollmentController.capturedCount}/3 (frames=${completedSeq.frameCount})")
        } else {
            // Live Recognition Mode: Execute DTW ONCE
            displayState = DisplayLifecycleState.RECOGNIZING
            dtwCallCount++

            // Track live history for Phase 4 Asymmetry
            liveHistory.add(completedSeq)
            if (liveHistory.size > 3) liveHistory.removeAt(0)

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

            // ===== PHASES 3, 4, 7, 9 FORENSIC DIAGNOSTICS =====
            runLiveRecognitionDiagnostics(completedSeq, recognitionResult)

            // Schedule auto-reset back to SEARCHING after timeout
            mainHandler.removeCallbacks(resultResetRunnable)
            mainHandler.postDelayed(resultResetRunnable, GestureConfig.RESULT_DISPLAY_DURATION_MS)
        }

        return sampleCaptured
    }

    /**
     * Phase 1: Trace complete gesture with checksums, bounds, frame counts, handedness, and velocity stats.
     */
    private fun logDetailedSequenceTrace(
        tag: String,
        stage: String,
        seq: TemporalSequence,
        endReason: String = "N/A"
    ) {
        val stats = seq.computeStatistics()
        val vStats = stats.velocityStats

        Log.i(tag, "══════════════════════════════════════════════════════════")
        Log.i(tag, "[$stage] SeqID=${seq.sequenceId} | Frames=${seq.frameCount} | Dur=${seq.durationMs}ms | EndReason=$endReason")
        Log.i(tag, "  Handedness: ${stats.dominantHandedness} | Scale: ${String.format("%.4f", stats.averageHandScale)}")
        Log.i(tag, "  Checksums: Total=${String.format("%.4f", stats.totalChecksum)} | Frame[0]=${String.format("%.4f", stats.firstFrameChecksum)} | Frame[N-1]=${String.format("%.4f", stats.lastFrameChecksum)}")
        Log.i(tag, "  Bounds: X=[${String.format("%.3f", stats.minX)}..${String.format("%.3f", stats.maxX)}], Y=[${String.format("%.3f", stats.minY)}..${String.format("%.3f", stats.maxY)}], Z=[${String.format("%.3f", stats.minZ)}..${String.format("%.3f", stats.maxZ)}]")
        Log.i(tag, "  Velocity: [${vStats.formatSummary()}] | MotionVar=${String.format("%.4f", stats.motionVariance)}")
        Log.i(tag, "  Motion Profile: ${seq.formatMotionProfile()}")
        Log.i(tag, "══════════════════════════════════════════════════════════")
    }

    /**
     * Phase 2: Self-Match Test.
     * Evaluates DTW(P1, P1), DTW(P1, P2), DTW(P1, P3), round-trip persistence DTW(original, restored).
     */
    private fun runPhase2SelfMatchTest(profile: GestureProfile) {
        val tag = "M4ForensicSelfMatch"
        Log.i(tag, "╔══════════════════════════════════════════════════════════╗")
        Log.i(tag, "║ PHASE 2: SELF-MATCH & PERSISTENCE TEST for '${profile.label}' ║")
        Log.i(tag, "╚══════════════════════════════════════════════════════════╝")

        val protos = profile.prototypes
        if (protos.isEmpty()) {
            Log.e(tag, "No prototypes in profile!")
            return
        }

        // 1. DTW(Pi, Pi) self matches (MUST BE 0.0000)
        for ((idx, p) in protos.withIndex()) {
            val selfDtw = DTW.computeDistance(p.sequence, p.sequence)
            Log.i(tag, "  SELF-MATCH DTW(P${idx + 1}, P${idx + 1}): dist=${String.format("%.6f", selfDtw.normalizedDistance)} (accCost=${String.format("%.4f", selfDtw.accumulatedCost)}) [Expected: 0.0000]")
        }

        // 2. Intra-prototype distances
        for (i in protos.indices) {
            for (j in i + 1 until protos.size) {
                val pairDtw = DTW.computeDistance(protos[i].sequence, protos[j].sequence)
                Log.i(tag, "  INTRA-PAIR DTW(P${i + 1}, P${j + 1}): dist=${String.format("%.4f", pairDtw.normalizedDistance)} (accCost=${String.format("%.2f", pairDtw.accumulatedCost)})")
            }
        }

        // 3. Persistence round-trip verification
        val reloadedProfiles = GestureProfileStorage.loadProfiles(this)
        val reloadedProfile = reloadedProfiles.find { it.id == profile.id }

        if (reloadedProfile != null && reloadedProfile.prototypes.size == protos.size) {
            for (i in protos.indices) {
                val orig = protos[i].sequence
                val restored = reloadedProfile.prototypes[i].sequence
                val roundTripDtw = DTW.computeDistance(orig, restored)
                val restoredSelfDtw = DTW.computeDistance(restored, restored)
                Log.i(tag, "  PERSISTENCE ROUND-TRIP DTW(Orig_P${i + 1}, Restored_P${i + 1}): dist=${String.format("%.6f", roundTripDtw.normalizedDistance)} [Expected: 0.0000]")
                Log.i(tag, "  RESTORED SELF-MATCH DTW(Restored_P${i + 1}, Restored_P${i + 1}): dist=${String.format("%.6f", restoredSelfDtw.normalizedDistance)} [Expected: 0.0000]")
            }
        } else {
            Log.e(tag, "FAILED TO RELOAD PROFILE FROM DISK FOR PERSISTENCE TEST!")
        }
        Log.i(tag, "══════════════════════════════════════════════════════════")
    }

    /**
     * Phases 3, 4, 7, 9: Live Recognition Diagnostics.
     */
    private fun runLiveRecognitionDiagnostics(liveSeq: TemporalSequence, result: RecognitionResult) {
        val liveStats = liveSeq.computeStatistics()

        // --- Phase 3 & 7 & 9: Full Comparison Matrix ---
        val profiles = personalGestureStore.getAllProfiles()
        for (profile in profiles) {
            Log.i("M4ForensicLiveRepeat", "──────────────────────────────────────────────")
            Log.i("M4ForensicLiveRepeat", "LIVE vs PROFILE '${profile.label}' MATRIX:")

            for ((pIdx, proto) in profile.prototypes.withIndex()) {
                val dtwRes = DTW.computeDistance(liveSeq, proto.sequence)
                val resampledEuclid = liveSeq.computeResampledEuclideanDistance(proto.sequence, 20)
                val pearsonCorr = liveSeq.computeTrajectoryPearsonCorrelation(proto.sequence, 20)
                val protoStats = proto.sequence.computeStatistics()

                // Phase 7: Handedness check
                val handMatch = if (liveStats.dominantHandedness.equals(protoStats.dominantHandedness, ignoreCase = true)) "MATCH" else "MISMATCH!"

                Log.i("M4ForensicLiveRepeat", "  P${pIdx + 1} [${proto.id}]: " +
                        "DTW_dist=${String.format("%.4f", dtwRes.normalizedDistance)} | " +
                        "ResampledEuclid=${String.format("%.4f", resampledEuclid)} | " +
                        "PearsonCorr=${String.format("%.4f", pearsonCorr)} | " +
                        "Handedness: Live=${liveStats.dominantHandedness} vs Proto=${protoStats.dominantHandedness} ($handMatch) | " +
                        "LiveLen=${liveSeq.frameCount}p vs ProtoLen=${proto.sequence.frameCount}p")
            }
        }

        // --- Phase 4: Segmentation Asymmetry Table ---
        if (teachHistory.isNotEmpty()) {
            Log.i("M4ForensicAsymmetry", "╔══════════════════════════════════════════════════════════════════════════════════════════╗")
            Log.i("M4ForensicAsymmetry", "║ PHASE 4: SEGMENTATION ASYMMETRY TABLE                                                    ║")
            Log.i("M4ForensicAsymmetry", "╠══════════════════════════════════════════════════════════════════════════════════════════╣")
            Log.i("M4ForensicAsymmetry", "║ Seq | Source      | Frames | Dur(ms) | MeanVel | MaxVel | MotionVar | Checksum           ║")
            Log.i("M4ForensicAsymmetry", "╠══════════════════════════════════════════════════════════════════════════════════════════╣")

            for ((i, tSeq) in teachHistory.withIndex()) {
                val st = tSeq.computeStatistics()
                val vs = st.velocityStats
                Log.i("M4ForensicAsymmetry", String.format("║ P%-2d | Teach       | %-6d | %-7d | %-7.4f | %-6.4f | %-9.4f | %-18.4f ║",
                    i + 1, tSeq.frameCount, tSeq.durationMs, vs.meanVelocity, vs.maxVelocity, st.motionVariance, st.totalChecksum))
            }
            for ((i, lSeq) in liveHistory.withIndex()) {
                val st = lSeq.computeStatistics()
                val vs = st.velocityStats
                Log.i("M4ForensicAsymmetry", String.format("║ L%-2d | Recognition | %-6d | %-7d | %-7.4f | %-6.4f | %-9.4f | %-18.4f ║",
                    i + 1, lSeq.frameCount, lSeq.durationMs, vs.meanVelocity, vs.maxVelocity, st.motionVariance, st.totalChecksum))
            }
            Log.i("M4ForensicAsymmetry", "╚══════════════════════════════════════════════════════════════════════════════════════════╝")
        }
    }

    /**
     * Updates HUD UI elements reflecting the event-driven recognition lifecycle.
     */
    private fun updateRecognitionDisplay() {
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
            DisplayLifecycleState.NO_HAND -> {
                binding.tvRecognitionStatus.text = "NO HAND FOUND"
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FFFF5252"))
                binding.tvRecognizedLabel.text = "--"
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#80FFFFFF"))
                binding.tvBestSecondDist.text = "-- / --"
                binding.tvMarginThreshold.text = "-- / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
                binding.tvSeqIdDuration.text = "No hand detected"
            }

            DisplayLifecycleState.HAND_DETECTED -> {
                binding.tvRecognitionStatus.text = "HAND DETECTED"
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvRecognizedLabel.text = "Stabilizing..."
                binding.tvRecognizedLabel.setTextColor(Color.parseColor("#FF00E5FF"))
                binding.tvBestSecondDist.text = "-- / --"
                binding.tvMarginThreshold.text = "-- / ${GestureConfig.DEFAULT_RECOGNITION_THRESHOLD}"
                binding.tvSeqIdDuration.text = "Hand entry stabilization"
            }

            DisplayLifecycleState.SEARCHING -> {
                binding.tvRecognitionStatus.text = "SEARCHING"
                binding.tvRecognitionStatus.setTextColor(Color.parseColor("#FF00E676"))
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
