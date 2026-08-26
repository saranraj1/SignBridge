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
import com.signbridge.app.vision.HandLandmarkerHelper
import com.signbridge.app.vision.VisionFrameResult

/**
 * Main Activity for SignBridge+ Milestone M1.
 *
 * Implements the minimal, solid vision foundation:
 * Live Camera preview -> MediaPipe Hand Landmarker (on-device) -> 21 3D landmarks & live metrics.
 */
class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraManager: CameraManager

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
        binding.tvDeviceInfo.text = "Device: $manufacturer $model (Android $androidVersion, API $sdkInt) | Local ML"
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Tap metrics card to switch between front and back camera
        binding.metricsCard.setOnClickListener {
            if (::cameraManager.isInitialized) {
                cameraManager.switchCamera()
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

        runOnUiThread {
            // Update landmark overlay canvas
            binding.overlayView.setResults(result)

            // Update live debug metrics UI
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

            // Actual measured metrics
            binding.tvFps.text = if (currentFps > 0) "${String.format("%.1f", currentFps)} FPS" else "-- FPS"
            binding.tvInferenceTime.text = "${result.inferenceLatencyMs} ms"

            if (frameCount % 30 == 0) {
                Log.i(
                    "SignBridgeMetrics",
                    "Status: ${binding.tvStatusBadge.text} | Hands: ${result.hands.size} | Landmarks: ${result.totalLandmarksCount} | FPS: ${String.format("%.1f", currentFps)} | Latency: ${result.inferenceLatencyMs}ms"
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
