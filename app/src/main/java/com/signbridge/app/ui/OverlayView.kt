package com.signbridge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.signbridge.app.vision.VisionFrameResult
import kotlin.math.max

/**
 * Custom View that renders hand landmarks and skeletal bone connections
 * directly on top of the live camera preview.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: VisionFrameResult? = null

    // Paints
    private val linePaint = Paint().apply {
        color = Color.parseColor("#8000E5FF")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint().apply {
        color = Color.parseColor("#FF00E5FF")
        strokeWidth = 10f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val tipPaint = Paint().apply {
        color = Color.parseColor("#FFFFD600")
        strokeWidth = 14f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val leftHandLinePaint = Paint().apply {
        color = Color.parseColor("#8000E676")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val leftHandJointPaint = Paint().apply {
        color = Color.parseColor("#FF00E676")
        strokeWidth = 10f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private var scaleFactor: Float = 1f
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    /**
     * Updates current landmark results and triggers redraw.
     */
    fun setResults(visionResult: VisionFrameResult) {
        results = visionResult
        invalidate()
    }

    /**
     * Clears existing landmarks and redraws empty canvas.
     */
    fun clear() {
        results = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentResults = results ?: return
        if (!currentResults.hasHands) return

        val imageWidth = currentResults.inputImageWidth
        val imageHeight = currentResults.inputImageHeight

        if (imageWidth <= 0 || imageHeight <= 0) return

        // Calculate scaling and centering offset for fillCenter / aspect fit
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()
        scaleFactor = max(scaleX, scaleY)

        val scaledWidth = imageWidth * scaleFactor
        val scaledHeight = imageHeight * scaleFactor
        offsetX = (width - scaledWidth) / 2f
        offsetY = (height - scaledHeight) / 2f

        for (hand in currentResults.hands) {
            val isRightHand = hand.handedness.equals("Right", ignoreCase = true)
            val currentLinePaint = if (isRightHand) linePaint else leftHandLinePaint
            val currentJointPaint = if (isRightHand) jointPaint else leftHandJointPaint

            val landmarks = hand.landmarks
            if (landmarks.size != 21) continue

            // 1. Draw skeletal connection lines
            for (connection in HAND_CONNECTIONS) {
                val start = landmarks[connection.first]
                val end = landmarks[connection.second]

                val startX = start.x * scaledWidth + offsetX
                val startY = start.y * scaledHeight + offsetY
                val endX = end.x * scaledWidth + offsetX
                val endY = end.y * scaledHeight + offsetY

                canvas.drawLine(startX, startY, endX, endY, currentLinePaint)
            }

            // 2. Draw landmark points (joints and fingertips)
            for (i in landmarks.indices) {
                val point = landmarks[i]
                val px = point.x * scaledWidth + offsetX
                val py = point.y * scaledHeight + offsetY

                val isFingertip = i in FINGERTIP_INDICES
                val paint = if (isFingertip) tipPaint else currentJointPaint
                val radius = if (isFingertip) 10f else 7f

                canvas.drawCircle(px, py, radius, paint)
            }

            // 3. Draw handedness label near wrist (landmark 0)
            val wrist = landmarks[0]
            val wx = wrist.x * scaledWidth + offsetX
            val wy = wrist.y * scaledHeight + offsetY + 40f
            val label = "${hand.handedness} (${(hand.score * 100).toInt()}%)"
            canvas.drawText(label, wx - 40f, wy, textPaint)
        }
    }

    companion object {
        // Fingertip indices: Thumb (4), Index (8), Middle (12), Ring (16), Pinky (20)
        private val FINGERTIP_INDICES = setOf(4, 8, 12, 16, 20)

        // 21 MediaPipe Hand Landmark skeletal connections
        private val HAND_CONNECTIONS = listOf(
            // Palm base & MCP arches
            Pair(0, 1),
            Pair(0, 5),
            Pair(5, 9),
            Pair(9, 13),
            Pair(13, 17),
            Pair(0, 17),

            // Thumb
            Pair(1, 2),
            Pair(2, 3),
            Pair(3, 4),

            // Index finger
            Pair(5, 6),
            Pair(6, 7),
            Pair(7, 8),

            // Middle finger
            Pair(9, 10),
            Pair(10, 11),
            Pair(11, 12),

            // Ring finger
            Pair(13, 14),
            Pair(14, 15),
            Pair(15, 16),

            // Pinky finger
            Pair(17, 18),
            Pair(18, 19),
            Pair(19, 20)
        )
    }
}
