package com.signbridge.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX initialization, preview binding, and image analysis frame dispatch.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrameAvailable: (imageProxy: ImageProxy, isFrontCamera: Boolean) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT

    val isFrontCamera: Boolean
        get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /**
     * Initializes CameraX and binds Preview + Analysis use cases.
     */
    fun startCamera(lensFacing: Int = CameraSelector.LENS_FACING_FRONT) {
        this.lensFacing = lensFacing
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Binds CameraX Preview and ImageAnalysis use cases to lifecycle.
     */
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        // 1. Preview UseCase
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 2. ImageAnalysis UseCase
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    onFrameAvailable(imageProxy, isFrontCamera)
                }
            }

        // 3. CameraSelector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.i(TAG, "Camera use cases bound successfully (lensFacing=$lensFacing)")
        } catch (e: Exception) {
            Log.e(TAG, "Binding camera use cases failed: ${e.message}", e)
        }
    }

    /**
     * Toggles between front and back camera lenses.
     */
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bindCameraUseCases()
    }

    /**
     * Releases executor and camera provider resources.
     */
    fun shutdown() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        Log.d(TAG, "Camera manager shut down")
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
