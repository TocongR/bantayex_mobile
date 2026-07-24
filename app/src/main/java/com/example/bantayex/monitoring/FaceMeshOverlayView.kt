package com.example.bantayex.monitoring

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.sin

data class HeadRotation(
    val pitch: Float,
    val yaw: Float,
    val roll: Float
)

class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private var distancesCm: List<Float?> = emptyList()
    private var headRotations: List<HeadRotation?> = emptyList()
    private var gazeDirections: List<GazeDirection?> = emptyList()
    private var gazeStatuses: List<GazeStatus?> = emptyList()

    private var isCalibrationMode = false
    private var calibrationPoint: CalibrationPoint? = null
    private var calibrationState: CalibrationState = CalibrationState.NOT_STARTED
    private var calibrationProgress: Int = 0
    private var _stepIndex = 0
    private var _totalSteps = 9

    private val tesselationPaint = Paint().apply {
        color = Color.argb(80, 0, 255, 0)
        strokeWidth = 1f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val contourPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val irisPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gazeStatusPaint = Paint().apply {
        textSize = 44f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val gazeStatusBgPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gazeDebugPaint = Paint().apply {
        color = Color.CYAN
        textSize = 26f
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
    }

    private val calibArcPaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val calibDotPaint = Paint().apply {
        isAntiAlias = true
    }

    // Arrow paint for corner indicators — pulsing alpha applied per-draw
    private val cornerArrowPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setResults(
        result: FaceLandmarkerResult,
        imageWidth: Int,
        imageHeight: Int,
        distancesCm: List<Float?>,
        headRotations: List<HeadRotation?>,
        gazeDirections: List<GazeDirection?> = emptyList(),
        gazeStatuses: List<GazeStatus?> = emptyList()
    ) {
        results = result
        this.imageWidth = if (imageWidth > 0) imageWidth else 1
        this.imageHeight = if (imageHeight > 0) imageHeight else 1
        this.distancesCm = distancesCm
        this.headRotations = headRotations
        this.gazeDirections = gazeDirections
        this.gazeStatuses = gazeStatuses
        isCalibrationMode = false
        invalidate()
    }

    fun showCalibration(
        point: CalibrationPoint,
        state: CalibrationState,
        progress: Int,
        stepIndex: Int = 0,
        totalSteps: Int = 9
    ) {
        isCalibrationMode = true
        calibrationPoint = point
        calibrationState = state
        calibrationProgress = progress
        _stepIndex = stepIndex
        _totalSteps = totalSteps
        invalidate()
    }

    fun hideCalibration() {
        isCalibrationMode = false
        invalidate()
    }

    private fun getScaleAndOffset(): FloatArray {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = imageWidth.toFloat()
        val imgH = imageHeight.toFloat()
        val scale = maxOf(viewW / imgW, viewH / imgH)
        val offsetX = (viewW - imgW * scale) / 2f
        val offsetY = (viewH - imgH * scale) / 2f
        return floatArrayOf(scale, offsetX, offsetY)
    }

    private fun lx(x: Float, scale: Float, offsetX: Float) = x * imageWidth * scale + offsetX
    private fun ly(y: Float, scale: Float, offsetY: Float) = y * imageHeight * scale + offsetY

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isCalibrationMode) {
            drawCalibrationUI(canvas)
            return
        }

        val result = results ?: return
        val faceLandmarks = result.faceLandmarks()
        if (faceLandmarks.isEmpty()) return

        val scaleOffset = getScaleAndOffset()
        val scale = scaleOffset[0]
        val offsetX = scaleOffset[1]
        val offsetY = scaleOffset[2]

        for ((faceIndex, landmarks) in faceLandmarks.withIndex()) {
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_TESSELATION, scale, offsetX, offsetY, tesselationPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_FACE_OVAL, scale, offsetX, offsetY, contourPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_LEFT_EYE, scale, offsetX, offsetY, contourPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE, scale, offsetX, offsetY, contourPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_LIPS, scale, offsetX, offsetY, contourPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_LEFT_IRIS, scale, offsetX, offsetY, irisPaint)
            drawConnections(canvas, landmarks, FaceLandmarker.FACE_LANDMARKS_RIGHT_IRIS, scale, offsetX, offsetY, irisPaint)

            drawDistanceLabel(canvas, faceIndex, landmarks, scale, offsetX, offsetY)
            drawGazeStatus(canvas, faceIndex)
        }
    }

    private fun drawConnections(
        canvas: Canvas,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        connections: Set<com.google.mediapipe.tasks.components.containers.Connection>?,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        paint: Paint
    ) {
        connections ?: return
        for (conn in connections) {
            val start = landmarks[conn.start()]
            val end = landmarks[conn.end()]
            canvas.drawLine(
                lx(start.x(), scale, offsetX), ly(start.y(), scale, offsetY),
                lx(end.x(), scale, offsetX), ly(end.y(), scale, offsetY),
                paint
            )
        }
    }

    private fun drawDistanceLabel(
        canvas: Canvas,
        faceIndex: Int,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        scale: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        if (landmarks.isEmpty()) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE

        for (landmark in landmarks) {
            val x = lx(landmark.x(), scale, offsetX)
            val y = ly(landmark.y(), scale, offsetY)
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
        }

        val distance = distancesCm.getOrNull(faceIndex)
        val distanceText = if (distance != null)
            "Face ${faceIndex + 1}: ${distance.toInt()} cm"
        else
            "Face ${faceIndex + 1}: -- cm"

        val textWidth = labelTextPaint.measureText(distanceText)
        val textHeight = labelTextPaint.textSize
        val centerX = (minX + maxX) / 2f
        val textX = centerX - textWidth / 2f
        val textY = minY - 20f
        val padding = 10f

        val bgRect = RectF(
            textX - padding, textY - textHeight - padding,
            textX + textWidth + padding, textY + padding
        )
        canvas.drawRoundRect(bgRect, 12f, 12f, labelBackgroundPaint)
        canvas.drawText(distanceText, textX, textY, labelTextPaint)
    }

    private fun drawGazeStatus(canvas: Canvas, faceIndex: Int) {
        val status = gazeStatuses.getOrNull(faceIndex) ?: return
        val direction = gazeDirections.getOrNull(faceIndex)

        val (text, color) = when (status) {
            GazeStatus.ON_SCREEN        -> "LOOKING AT SCREEN" to Color.GREEN
            GazeStatus.OFF_SCREEN_LEFT  -> "LOOKING LEFT!" to Color.RED
            GazeStatus.OFF_SCREEN_RIGHT -> "LOOKING RIGHT!" to Color.RED
            GazeStatus.OFF_SCREEN_UP    -> "LOOKING UP!" to Color.RED
            GazeStatus.OFF_SCREEN_DOWN  -> "LOOKING DOWN!" to Color.RED
            GazeStatus.WARNING_LEFT     -> "NEAR LEFT EDGE" to Color.YELLOW
            GazeStatus.WARNING_RIGHT    -> "NEAR RIGHT EDGE" to Color.YELLOW
            GazeStatus.WARNING_UP       -> "NEAR TOP EDGE" to Color.YELLOW
            GazeStatus.WARNING_DOWN     -> "NEAR BOTTOM EDGE" to Color.YELLOW
            GazeStatus.UNKNOWN          -> "CALIBRATE FIRST" to Color.GRAY
        }

        val bannerHeight = 90f
        gazeStatusBgPaint.color = when (status) {
            GazeStatus.OFF_SCREEN_LEFT,
            GazeStatus.OFF_SCREEN_RIGHT,
            GazeStatus.OFF_SCREEN_UP,
            GazeStatus.OFF_SCREEN_DOWN  -> Color.argb(200, 120, 0, 0)
            GazeStatus.WARNING_LEFT,
            GazeStatus.WARNING_RIGHT,
            GazeStatus.WARNING_UP,
            GazeStatus.WARNING_DOWN     -> Color.argb(180, 100, 80, 0)
            else                         -> Color.argb(140, 0, 0, 0)
        }

        canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, gazeStatusBgPaint)
        gazeStatusPaint.color = color
        canvas.drawText(text, width / 2f, bannerHeight / 2f + 14f, gazeStatusPaint)

        val isOffScreen = status == GazeStatus.OFF_SCREEN_LEFT ||
                status == GazeStatus.OFF_SCREEN_RIGHT ||
                status == GazeStatus.OFF_SCREEN_UP ||
                status == GazeStatus.OFF_SCREEN_DOWN

        if (isOffScreen) {
            val borderPaint = Paint().apply {
                this.color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 12f
            }
            canvas.drawRect(6f, 6f, width - 6f, height - 6f, borderPaint)

            val arrowPaint = Paint().apply {
                this.color = Color.RED
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val arrowSize = 60f
            val cx = width / 2f
            val cy = height / 2f

            when (status) {
                GazeStatus.OFF_SCREEN_LEFT -> {
                    val path = Path().apply {
                        moveTo(40f, cy)
                        lineTo(40f + arrowSize, cy - arrowSize / 2)
                        lineTo(40f + arrowSize, cy + arrowSize / 2)
                        close()
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                GazeStatus.OFF_SCREEN_RIGHT -> {
                    val path = Path().apply {
                        moveTo(width - 40f, cy)
                        lineTo(width - 40f - arrowSize, cy - arrowSize / 2)
                        lineTo(width - 40f - arrowSize, cy + arrowSize / 2)
                        close()
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                GazeStatus.OFF_SCREEN_UP -> {
                    val path = Path().apply {
                        moveTo(cx, 110f)
                        lineTo(cx - arrowSize / 2, 110f + arrowSize)
                        lineTo(cx + arrowSize / 2, 110f + arrowSize)
                        close()
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                GazeStatus.OFF_SCREEN_DOWN -> {
                    val path = Path().apply {
                        moveTo(cx, height - 110f)
                        lineTo(cx - arrowSize / 2, height - 110f - arrowSize)
                        lineTo(cx + arrowSize / 2, height - 110f - arrowSize)
                        close()
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                else -> {}
            }
        }

        direction?.let {
            canvas.drawText("Gaze: ${it.name}", 20f, height - 20f, gazeDebugPaint)
        }
    }

    // ── Calibration UI ────────────────────────────────────────────────────────

    private fun drawCalibrationUI(canvas: Canvas) {

        val point = calibrationPoint ?: return

        val margin = 50f       // corner inset — dots stay fully on-screen
        val topY = 150f        // small clearance for the "Look at" title above
        val midY = height / 2f
        val botY = height - 150f  // small clearance for the message/% text below
        val leftX = margin
        val centerX = width / 2f
        val rightX = width - margin

        val (dotX, dotY) = when (point) {
            CalibrationPoint.TOP_LEFT      -> Pair(leftX, topY)
            CalibrationPoint.TOP_CENTER    -> Pair(centerX, topY)
            CalibrationPoint.TOP_RIGHT     -> Pair(rightX, topY)
            CalibrationPoint.MIDDLE_LEFT   -> Pair(leftX, midY)
            CalibrationPoint.MIDDLE_CENTER -> Pair(centerX, midY)
            CalibrationPoint.MIDDLE_RIGHT  -> Pair(rightX, midY)
            CalibrationPoint.BOTTOM_LEFT   -> Pair(leftX, botY)
            CalibrationPoint.BOTTOM_CENTER -> Pair(centerX, botY)
            CalibrationPoint.BOTTOM_RIGHT  -> Pair(rightX, botY)
        }

        // Faint grid showing all 9 positions
        val gridPaint = Paint().apply {
            color = Color.argb(40, 255, 255, 255)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        for (gx in listOf(leftX, centerX, rightX)) {
            for (gy in listOf(topY, midY, botY)) {
                canvas.drawCircle(gx, gy, 12f, gridPaint)
            }
        }

        val sq2 = 0.7071f  // 1/√2 for diagonal directions

        // ── Directional arrows radiating outward from the dot ────────────────
        // Arrows point away from the dot in the direction the user's eyes need
        // to travel to reach it from center. This matches the sketch: the dot
        // sits in the middle, arrows fan out showing "look this way."
        // Shown for all points except MIDDLE_CENTER (no direction needed there),
        // and hidden once the point is complete.
        if (calibrationState != CalibrationState.POINT_COMPLETE &&
            calibrationState != CalibrationState.TRANSITIONING &&
            point != CalibrationPoint.MIDDLE_CENTER
        ) {
            val pulse = ((sin(System.currentTimeMillis() / 420.0) + 1.0) / 2.0).toFloat()
            val arrowAlpha = (140 + (pulse * 100)).toInt().coerceIn(0, 255)
            val arrowDist = 110f  // how far from dot center the arrow tip sits
            val arrowSize = 48f

            // Determine which directions to draw based on the calibration point
            // Corner points get 2 arrows (diagonal spread), edge centers get 1,
            // middle sides get 1. Each arrow points away from screen center.
            val directions: List<Pair<Float, Float>> = when (point) {
                CalibrationPoint.TOP_LEFT     -> listOf(-sq2 to -sq2, -1f to 0f, 0f to -1f)
                CalibrationPoint.TOP_CENTER   -> listOf(0f to -1f)
                CalibrationPoint.TOP_RIGHT    -> listOf(sq2 to -sq2, 1f to 0f, 0f to -1f)
                CalibrationPoint.MIDDLE_LEFT  -> listOf(-1f to 0f)
                CalibrationPoint.MIDDLE_RIGHT -> listOf(1f to 0f)
                CalibrationPoint.BOTTOM_LEFT  -> listOf(-sq2 to sq2, -1f to 0f, 0f to 1f)
                CalibrationPoint.BOTTOM_CENTER-> listOf(0f to 1f)
                CalibrationPoint.BOTTOM_RIGHT -> listOf(sq2 to sq2, 1f to 0f, 0f to 1f)
                CalibrationPoint.MIDDLE_CENTER -> emptyList()
            }

            for ((dx, dy) in directions) {
                val tipX = dotX + dx * arrowDist
                val tipY = dotY + dy * arrowDist
                drawDiagonalArrow(canvas, tipX, tipY, dx, dy, arrowSize, arrowAlpha)
            }

            postInvalidateDelayed(16)
        }

        // ── Calibration dot ─────────────────────────────────────────────────
        when (calibrationState) {
            CalibrationState.WAITING_FOR_USER -> {
                calibArcPaint.color = Color.CYAN
                calibArcPaint.strokeWidth = 8f
                canvas.drawArc(
                    RectF(dotX - 70f, dotY - 70f, dotX + 70f, dotY + 70f),
                    -90f, calibrationProgress * 3.6f, false, calibArcPaint
                )
                calibDotPaint.style = Paint.Style.STROKE
                calibDotPaint.color = Color.YELLOW
                calibDotPaint.strokeWidth = 4f
                canvas.drawCircle(dotX, dotY, 45f, calibDotPaint)
                canvas.drawCircle(dotX, dotY, 25f, calibDotPaint)
                calibDotPaint.style = Paint.Style.FILL
                canvas.drawCircle(dotX, dotY, 10f, calibDotPaint)
            }
            CalibrationState.COLLECTING -> {
                calibArcPaint.color = Color.GREEN
                calibArcPaint.strokeWidth = 12f
                canvas.drawArc(
                    RectF(dotX - 70f, dotY - 70f, dotX + 70f, dotY + 70f),
                    -90f, calibrationProgress * 3.6f, false, calibArcPaint
                )
                calibDotPaint.style = Paint.Style.FILL
                calibDotPaint.color = Color.GREEN
                canvas.drawCircle(
                    dotX, dotY,
                    30f + ((System.currentTimeMillis() % 500) / 500f) * 15f,
                    calibDotPaint
                )
            }
            CalibrationState.POINT_COMPLETE, CalibrationState.TRANSITIONING -> {
                calibDotPaint.style = Paint.Style.FILL
                calibDotPaint.color = Color.GREEN
                canvas.drawCircle(dotX, dotY, 50f, calibDotPaint)
                calibDotPaint.style = Paint.Style.STROKE
                calibDotPaint.color = Color.WHITE
                calibDotPaint.strokeWidth = 8f
                canvas.drawLine(dotX - 20f, dotY, dotX - 5f, dotY + 15f, calibDotPaint)
                canvas.drawLine(dotX - 5f, dotY + 15f, dotX + 25f, dotY - 15f, calibDotPaint)
            }
            else -> {}
        }
    }

    /**
     * Draws a chevron-style arrow with its tip at (tipX, tipY), pointing in the
     * direction (dx, dy). The two arms spread 45° either side of the direction vector.
     * This is deliberately not a filled triangle — a chevron reads as "look this way"
     * rather than "tap here," which is the right affordance for a gaze target.
     */
    private fun drawDiagonalArrow(
        canvas: Canvas,
        tipX: Float, tipY: Float,
        dx: Float, dy: Float,
        size: Float,
        alpha: Int
    ) {
        // Perpendicular to (dx, dy) rotated 45° for the two arm directions
        // Arrow: two lines from tip, each going backward and outward at 45°
        val backX = -dx * size
        val backY = -dy * size
        val perpX = -dy   // perpendicular unit vector
        val perpY = dx

        val spreadFactor = 0.55f  // controls how wide the V opens

        val arm1X = tipX + backX + perpX * size * spreadFactor
        val arm1Y = tipY + backY + perpY * size * spreadFactor
        val arm2X = tipX + backX - perpX * size * spreadFactor
        val arm2Y = tipY + backY - perpY * size * spreadFactor

        val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            this.alpha = alpha
        }

        canvas.drawLine(tipX, tipY, arm1X, arm1Y, paint)
        canvas.drawLine(tipX, tipY, arm2X, arm2Y, paint)
    }
}