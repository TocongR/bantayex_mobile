package com.example.bantayex.monitoring

import android.content.Context
import android.content.SharedPreferences

/**
 * Extracted from GazeModels.kt. Logic byte-for-byte unchanged — this is the calibration
 * state machine (9-point calibration sequence), the ridge-regression-based screen
 * position prediction, and the hysteresis logic that keeps gaze status from flickering
 * frame-to-frame. All comments explaining WHY specific constants/behaviors exist are
 * preserved as-is since they document real tuning decisions, not just what the code does.
 */
class CalibrationManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gaze_calibration_v5", Context.MODE_PRIVATE)

    var calibrationData = CalibrationData()
        private set

    private val allSamplesX = mutableListOf<CalibrationSampleX>()
    private val allSamplesY = mutableListOf<CalibrationSampleY>()
    private val currentSamples = mutableListOf<FloatArray>()

    private var currentPoint: CalibrationPoint? = null

    var state: CalibrationState = CalibrationState.NOT_STARTED
        private set

    private var stateStartTime: Long = 0
    private var sampleCount = 0

    var onPointComplete: ((CalibrationPoint) -> Unit)? = null
    var onAllComplete: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    private val pointTargetX = mapOf(
        CalibrationPoint.TOP_LEFT      to 0f,
        CalibrationPoint.TOP_CENTER    to 0.5f,
        CalibrationPoint.TOP_RIGHT     to 1f,
        CalibrationPoint.MIDDLE_LEFT   to 0f,
        CalibrationPoint.MIDDLE_CENTER to 0.5f,
        CalibrationPoint.MIDDLE_RIGHT  to 1f,
        CalibrationPoint.BOTTOM_LEFT   to 0f,
        CalibrationPoint.BOTTOM_CENTER to 0.5f,
        CalibrationPoint.BOTTOM_RIGHT  to 1f
    )

    private val pointTargetY = mapOf(
        CalibrationPoint.TOP_LEFT      to 0f,
        CalibrationPoint.TOP_CENTER    to 0f,
        CalibrationPoint.TOP_RIGHT     to 0f,
        CalibrationPoint.MIDDLE_LEFT   to 0.5f,
        CalibrationPoint.MIDDLE_CENTER to 0.5f,
        CalibrationPoint.MIDDLE_RIGHT  to 0.5f,
        CalibrationPoint.BOTTOM_LEFT   to 1f,
        CalibrationPoint.BOTTOM_CENTER to 1f,
        CalibrationPoint.BOTTOM_RIGHT  to 1f
    )

    val calibrationSequence = listOf(
        CalibrationPoint.MIDDLE_CENTER,
        CalibrationPoint.TOP_LEFT,
        CalibrationPoint.TOP_CENTER,
        CalibrationPoint.TOP_RIGHT,
        CalibrationPoint.MIDDLE_LEFT,
        CalibrationPoint.MIDDLE_RIGHT,
        CalibrationPoint.BOTTOM_LEFT,
        CalibrationPoint.BOTTOM_CENTER,
        CalibrationPoint.BOTTOM_RIGHT
    )

    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", DEFAULT_SENSITIVITY)
        set(value) {
            prefs.edit().putFloat("sensitivity", value.coerceIn(0.0f, 0.5f)).apply()
        }

    // Up/down direction thresholds are constants, not prefs-backed, because they need
    // to be reliably consistent across sessions and can't be accidentally stale from
    // a previous version's SharedPreferences write.
    //
    // Both bands are 0f: as soon as the predicted screenY crosses past the top or
    // bottom edge, it's treated as a genuine off-screen violation immediately, with
    // no intermediate "warning" buffer the way left/right has via `sensitivity`.
    // Up used to reuse the (much larger) horizontal `sensitivity` value as its buffer,
    // which meant a student had to look noticeably far up before it registered —
    // effectively under-detecting upward glances compared to downward ones. Tightening
    // it to match DOWN_WARNING_BAND fixes that asymmetry.

    private var smoothedIrisX: Float? = null
    private var smoothedIrisY: Float? = null
    private var smoothedOpenness: Float? = null
    private var smoothedLidDrop: Float? = null
    private val smoothingAlpha = 0.12f

    private var pendingStatus: GazeStatus? = null
    private var pendingStatusCount = 0
    private var lastStableStatus: GazeStatus = GazeStatus.UNKNOWN
    private val statusHysteresisFrames = 7

    var lastScreenX: Float = 0.5f
        private set
    var lastScreenY: Float = 0.5f
        private set

    companion object {
        private const val SAMPLES_NEEDED = 30
        private const val WAIT_DURATION_MS = 2000L
        private const val COLLECT_DURATION_MS = 2500L
        private const val DEFAULT_SENSITIVITY = 0.18f

        // Was 0.1f. WARNING_DOWN is treated identically to ON_SCREEN downstream (see
        // TakeExamActivity's status when-block) — no violation timer starts for it. A 0.1f
        // buffer meant a predicted screenY had to overshoot the bottom edge by a further 10%
        // before OFF_SCREEN_DOWN (which DOES start the timer) could ever fire. Normal, moderate
        // looking-down landed inside that dead buffer and was silently absorbed as "fine." At
        // 0.0f, OFF_SCREEN_DOWN and WARNING_DOWN share the same threshold (screenY > 1f), so
        // WARNING_DOWN never actually fires — any downward overshoot goes straight to
        // OFF_SCREEN_DOWN, matching how left/right/up already behave without a dead zone.
        private const val DOWN_WARNING_BAND = 0.0f

        private const val UP_WARNING_BAND = 0.2f

        private const val RIDGE_LAMBDA = 0.01f
    }

    init {
        loadCalibration()
    }

    private fun ema(prev: Float?, current: Float, alpha: Float): Float =
        if (prev == null) current else prev + alpha * (current - prev)

    private fun buildFeaturesX(irisX: Float, irisY: Float): FloatArray = floatArrayOf(
        1f, irisX, irisY, irisX * irisX, irisX * irisY
    )

    private fun buildFeaturesY(irisY: Float, eyeOpenness: Float, upperLidDrop: Float): FloatArray = floatArrayOf(
        1f, irisY, eyeOpenness, upperLidDrop, irisY * irisY, irisY * upperLidDrop
    )

    fun predictScreenX(irisX: Float, irisY: Float): Float {
        if (!calibrationData.isCalibrated) return 0.5f
        val f = buildFeaturesX(irisX, irisY)
        return calibrationData.weightsX.indices.sumOf {
            (calibrationData.weightsX[it] * f[it]).toDouble()
        }.toFloat()
    }

    fun predictScreenY(irisY: Float, eyeOpenness: Float, upperLidDrop: Float): Float {
        if (!calibrationData.isCalibrated) return 0.5f
        val f = buildFeaturesY(irisY, eyeOpenness, upperLidDrop)
        return calibrationData.weightsY.indices.sumOf {
            (calibrationData.weightsY[it] * f[it]).toDouble()
        }.toFloat()
    }

    fun reset() {
        state = CalibrationState.NOT_STARTED
        currentPoint = null
        allSamplesX.clear()
        allSamplesY.clear()
        currentSamples.clear()
        sampleCount = 0
        smoothedIrisX = null
        smoothedIrisY = null
        smoothedOpenness = null
        smoothedLidDrop = null
        pendingStatus = null
        pendingStatusCount = 0
        lastStableStatus = GazeStatus.UNKNOWN
    }

    fun startPoint(point: CalibrationPoint) {
        currentPoint = point
        currentSamples.clear()
        sampleCount = 0
        state = CalibrationState.WAITING_FOR_USER
        stateStartTime = System.currentTimeMillis()
    }

    fun getCurrentPoint(): CalibrationPoint? = currentPoint

    fun processSample(
        irisX: Float,
        irisY: Float,
        eyeOpenness: Float,
        upperLidDropRatio: Float,
        headPose: HeadPose
    ) {
        if (state == CalibrationState.TRANSITIONING ||
            state == CalibrationState.POINT_COMPLETE ||
            state == CalibrationState.ALL_DONE ||
            state == CalibrationState.FAILED ||
            state == CalibrationState.NOT_STARTED
        ) return

        val elapsed = System.currentTimeMillis() - stateStartTime

        when (state) {
            CalibrationState.WAITING_FOR_USER -> {
                if (elapsed >= WAIT_DURATION_MS) {
                    state = CalibrationState.COLLECTING
                    stateStartTime = System.currentTimeMillis()
                    currentSamples.clear()
                    sampleCount = 0
                }
            }
            CalibrationState.COLLECTING -> {
                currentSamples.add(floatArrayOf(irisX, irisY, eyeOpenness, upperLidDropRatio))
                sampleCount++

                if (sampleCount >= SAMPLES_NEEDED || elapsed >= COLLECT_DURATION_MS) {
                    if (currentSamples.size >= 10) {
                        finalizeCurrentPoint()
                        state = CalibrationState.POINT_COMPLETE
                        currentPoint?.let { onPointComplete?.invoke(it) }
                    } else {
                        state = CalibrationState.FAILED
                        onFailed?.invoke()
                    }
                }
            }
            else -> {}
        }
    }

    fun getProgress(): Int {
        val elapsed = System.currentTimeMillis() - stateStartTime
        return when (state) {
            CalibrationState.WAITING_FOR_USER ->
                ((elapsed.toFloat() / WAIT_DURATION_MS) * 100).toInt().coerceIn(0, 100)
            CalibrationState.COLLECTING ->
                ((sampleCount.toFloat() / SAMPLES_NEEDED) * 100).toInt().coerceIn(0, 100)
            CalibrationState.POINT_COMPLETE -> 100
            else -> 0
        }
    }

    fun markTransitioning() {
        state = CalibrationState.TRANSITIONING
    }

    private fun finalizeCurrentPoint() {
        val point = currentPoint ?: return
        if (currentSamples.isEmpty()) return

        val targetX = pointTargetX[point] ?: return
        val targetY = pointTargetY[point] ?: return

        val trimCount = (currentSamples.size * 0.15).toInt().coerceAtLeast(1)

        fun trimmed(values: List<Float>): List<Float> {
            val sorted = values.sorted()
            return if (sorted.size > trimCount * 2)
                sorted.drop(trimCount).dropLast(trimCount)
            else sorted
        }

        val trimmedIrisX = trimmed(currentSamples.map { it[0] })
        val trimmedIrisY = trimmed(currentSamples.map { it[1] })
        val trimmedOpenness = trimmed(currentSamples.map { it[2] })
        val trimmedLidDrop = trimmed(currentSamples.map { it[3] })

        val avgIrisX = trimmedIrisX.average().toFloat()
        val avgIrisY = trimmedIrisY.average().toFloat()
        val avgOpenness = trimmedOpenness.average().toFloat()
        val avgLidDrop = trimmedLidDrop.average().toFloat()

        allSamplesX.add(CalibrationSampleX(avgIrisX, avgIrisY, targetX))
        allSamplesY.add(CalibrationSampleY(avgIrisY, avgOpenness, avgLidDrop, targetY))

        val step = maxOf(1, trimmedIrisX.size / 5)
        for (i in trimmedIrisX.indices step step) {
            allSamplesX.add(CalibrationSampleX(trimmedIrisX[i], trimmedIrisY.getOrElse(i) { avgIrisY }, targetX))
            allSamplesY.add(
                CalibrationSampleY(
                    trimmedIrisY.getOrElse(i) { avgIrisY },
                    trimmedOpenness.getOrElse(i) { avgOpenness },
                    trimmedLidDrop.getOrElse(i) { avgLidDrop },
                    targetY
                )
            )
        }
    }

    fun finishCalibration(): Boolean {
        if (allSamplesX.size < 9 || allSamplesY.size < 9) return false

        val featuresX = allSamplesX.map { buildFeaturesX(it.irisX, it.irisY) }
        val targetsX = allSamplesX.map { it.targetScreenX }
        val weightsX = RidgeRegression.fit(featuresX, targetsX, RIDGE_LAMBDA)
        if (weightsX.isEmpty()) return false

        val featuresY = allSamplesY.map { buildFeaturesY(it.irisY, it.eyeOpenness, it.upperLidDropRatio) }
        val targetsY = allSamplesY.map { it.targetScreenY }
        val weightsY = RidgeRegression.fit(featuresY, targetsY, RIDGE_LAMBDA)
        if (weightsY.isEmpty()) return false

        calibrationData = CalibrationData(
            weightsX = weightsX,
            weightsY = weightsY,
            isCalibrated = true
        )

        saveCalibration()
        state = CalibrationState.ALL_DONE
        onAllComplete?.invoke()
        return true
    }

    fun checkGazeStatus(
        irisX: Float,
        irisY: Float,
        eyeOpenness: Float,
        upperLidDropRatio: Float,
        headPose: HeadPose,
        isLidTransitioning: Boolean = false
    ): GazeStatus {
        if (!calibrationData.isCalibrated) return GazeStatus.UNKNOWN

        val alreadyLookingDown = lastStableStatus == GazeStatus.WARNING_DOWN ||
                lastStableStatus == GazeStatus.OFF_SCREEN_DOWN

        if (isLidTransitioning && !alreadyLookingDown) {
            return lastStableStatus
        }

        smoothedIrisX = ema(smoothedIrisX, irisX, smoothingAlpha)
        smoothedIrisY = ema(smoothedIrisY, irisY, smoothingAlpha)
        smoothedOpenness = ema(smoothedOpenness, eyeOpenness, smoothingAlpha)
        smoothedLidDrop = ema(smoothedLidDrop, upperLidDropRatio, smoothingAlpha)

        val screenX = predictScreenX(smoothedIrisX!!, smoothedIrisY!!)
        val screenY = predictScreenY(smoothedIrisY!!, smoothedOpenness!!, smoothedLidDrop!!)

        lastScreenX = screenX
        lastScreenY = screenY

        val hStatus = when {
            screenX < 0f - sensitivity -> GazeStatus.OFF_SCREEN_LEFT
            screenX > 1f + sensitivity -> GazeStatus.OFF_SCREEN_RIGHT
            screenX < 0f              -> GazeStatus.WARNING_LEFT
            screenX > 1f              -> GazeStatus.WARNING_RIGHT
            else                       -> null
        }

        val vStatus = when {
            screenY < 0f - UP_WARNING_BAND      -> GazeStatus.OFF_SCREEN_UP
            screenY > 1f + DOWN_WARNING_BAND    -> GazeStatus.OFF_SCREEN_DOWN
            screenY < 0f                        -> GazeStatus.WARNING_UP
            screenY > 1f                        -> GazeStatus.WARNING_DOWN
            else                                -> null
        }

        val rawStatus = when {
            hStatus == GazeStatus.OFF_SCREEN_LEFT  -> GazeStatus.OFF_SCREEN_LEFT
            hStatus == GazeStatus.OFF_SCREEN_RIGHT -> GazeStatus.OFF_SCREEN_RIGHT
            vStatus == GazeStatus.OFF_SCREEN_UP    -> GazeStatus.OFF_SCREEN_UP
            vStatus == GazeStatus.OFF_SCREEN_DOWN  -> GazeStatus.OFF_SCREEN_DOWN
            hStatus == GazeStatus.WARNING_LEFT     -> GazeStatus.WARNING_LEFT
            hStatus == GazeStatus.WARNING_RIGHT    -> GazeStatus.WARNING_RIGHT
            vStatus == GazeStatus.WARNING_UP       -> GazeStatus.WARNING_UP
            vStatus == GazeStatus.WARNING_DOWN     -> GazeStatus.WARNING_DOWN
            else                                    -> GazeStatus.ON_SCREEN
        }

        return applyHysteresis(rawStatus)
    }

    fun getGazeDirection(
        irisX: Float,
        irisY: Float,
        eyeOpenness: Float,
        upperLidDropRatio: Float,
        headPose: HeadPose
    ): GazeDirection {
        if (!calibrationData.isCalibrated) return GazeDirection.CENTER

        val sX = smoothedIrisX ?: irisX
        val sY = smoothedIrisY ?: irisY
        val sO = smoothedOpenness ?: eyeOpenness
        val sL = smoothedLidDrop ?: upperLidDropRatio

        val screenX = predictScreenX(sX, sY)
        val screenY = predictScreenY(sY, sO, sL)

        return when {
            screenX < 0f - sensitivity       -> GazeDirection.LEFT
            screenX > 1f + sensitivity       -> GazeDirection.RIGHT
            screenY < 0f - UP_WARNING_BAND   -> GazeDirection.UP
            screenY > 1f + DOWN_WARNING_BAND -> GazeDirection.DOWN
            else                              -> GazeDirection.CENTER
        }
    }

    private fun applyHysteresis(rawStatus: GazeStatus): GazeStatus {
        if (rawStatus == lastStableStatus) {
            pendingStatus = null
            pendingStatusCount = 0
            return lastStableStatus
        }

        if (pendingStatus == rawStatus) {
            pendingStatusCount++
            if (pendingStatusCount >= statusHysteresisFrames) {
                lastStableStatus = rawStatus
                pendingStatus = null
                pendingStatusCount = 0
            }
        } else {
            pendingStatus = rawStatus
            pendingStatusCount = 1
        }

        return lastStableStatus
    }

    fun isCalibrated(): Boolean = calibrationData.isCalibrated

    private fun saveCalibration() {
        prefs.edit().apply {
            putInt("weightsX_count", calibrationData.weightsX.size)
            for (i in calibrationData.weightsX.indices) {
                putFloat("weightX_$i", calibrationData.weightsX[i])
            }
            putInt("weightsY_count", calibrationData.weightsY.size)
            for (i in calibrationData.weightsY.indices) {
                putFloat("weightY_$i", calibrationData.weightsY[i])
            }
            putBoolean("isCalibrated", true)
            apply()
        }
    }

    private fun loadCalibration() {
        if (prefs.getBoolean("isCalibrated", false)) {
            val countX = prefs.getInt("weightsX_count", 0)
            val countY = prefs.getInt("weightsY_count", 0)
            if (countX > 0 && countY > 0) {
                calibrationData = CalibrationData(
                    weightsX = FloatArray(countX) { prefs.getFloat("weightX_$it", 0f) },
                    weightsY = FloatArray(countY) { prefs.getFloat("weightY_$it", 0f) },
                    isCalibrated = true
                )
            }
        }
    }

    fun clearCalibration() {
        prefs.edit().clear().apply()
        calibrationData = CalibrationData()
        state = CalibrationState.NOT_STARTED
        allSamplesX.clear()
        allSamplesY.clear()
        currentSamples.clear()
    }
}