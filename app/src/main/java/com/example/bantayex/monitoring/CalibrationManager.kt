package com.example.bantayex.monitoring

import android.content.Context
import android.content.SharedPreferences

/**
 * Calibration state machine (9-point sequence), ridge-regression screen-position
 * prediction, and the per-frame gaze verdict pipeline.
 *
 * Synced from GazeDebug. Differences vs. the previous BantayEx version:
 *  - blink EMA-gate, adaptive "droop-down" lane, down-reach amplifier
 *  - head-pose override relative to a calibrated baseline yaw/pitch
 *  - 8-frame blink immunity, 3-frame hysteresis, smoothing alpha 0.16
 *  - warningDelayMs (default 2s): how long the student can look away before the
 *    warning banner + tone appear. The violation fires awayViolationThresholdMs
 *    after the warning appears (see TakeExamActivity.registerGazeAway).
 *
 * BantayEx-specific: onAllComplete is kept, and loadCalibration() refuses a saved
 * calibration that has no head-pose baseline (saved by the older pipeline).
 */
class CalibrationManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gaze_calibration_v5", Context.MODE_PRIVATE)

    var calibrationData = CalibrationData()
        private set

    private val allSamplesX = mutableListOf<CalibrationSampleX>()
    private val allSamplesY = mutableListOf<CalibrationSampleY>()
    private val currentSamples = mutableListOf<FloatArray>()

    // Head pose while looking at the centre dot -> baseline for the head-pose override.
    private val centerYawSamples = mutableListOf<Float>()
    private val centerPitchSamples = mutableListOf<Float>()

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

    // ── Tunables (prefs-backed so the GazeDebug sliders can drive them) ──────

    /** Left/right buffer beyond the screen edge before OFF_SCREEN_LEFT/RIGHT fires. */
    var sensitivity: Float
        get() = prefs.getFloat("sensitivity", DEFAULT_SENSITIVITY)
        set(value) {
            prefs.edit().putFloat("sensitivity", value.coerceIn(0.0f, 0.5f)).apply()
        }

    /** |relative yaw| beyond this (heuristic units) -> head turned left/right. */
    var headYawThreshold: Float
        get() = prefs.getFloat("headYawThreshold", 35f)
        set(value) {
            prefs.edit().putFloat("headYawThreshold", value.coerceIn(5f, 60f)).apply()
        }

    /** Relative pitch beyond -this (heuristic units) for a few frames -> head tilted up. */
    var headUpPitchThreshold: Float
        get() = prefs.getFloat("headUpPitchThreshold", 5f)
        set(value) {
            prefs.edit().putFloat("headUpPitchThreshold", value.coerceIn(5f, 60f)).apply()
        }

    /** Extra buffer above the top edge before an upward gaze counts as OFF_SCREEN_UP. */
    var upGazeBand: Float
        get() = prefs.getFloat("upGazeBand", 0f)
        set(value) {
            prefs.edit().putFloat("upGazeBand", value.coerceIn(0f, 0.5f)).apply()
        }

    /**
     * How long the student can look away before the warning banner + tone appear.
     * BantayEx default is 2s (GazeDebug default is 0).
     */
    var warningDelayMs: Long
        get() = prefs.getLong("warningDelayMs", DEFAULT_WARNING_DELAY_MS)
        set(value) {
            prefs.edit().putLong("warningDelayMs", value.coerceIn(0L, 5000L)).apply()
        }

    // ── Per-frame state ──────────────────────────────────────────────────────

    private var smoothedIrisX: Float? = null
    private var smoothedIrisY: Float? = null
    private var smoothedOpenness: Float? = null
    private var smoothedLidDrop: Float? = null
    private val smoothingAlpha = 0.16f

    private var pendingStatus: GazeStatus? = null
    private var pendingStatusCount = 0
    private var lastStableStatus: GazeStatus = GazeStatus.UNKNOWN
    private val statusHysteresisFrames = 3

    private var frameCount = 0
    private var lastBlinkFrame = -DOWN_BLINK_IMMUNITY_FRAMES - 1

    // Blink EMA-gate: running max of openness with slow decay.
    private var opennessRef: Float? = null
    private var eyeGateActive = false
    private var eyeGateFrameCount = 0

    private var upPitchHoldFrames = 0

    // Adaptive droop-down lane.
    private var droopDownHoldFrames = 0
    private var droopDownActive = false
    private var baselineIrisY: Float? = null
    private var baselineLidDrop: Float? = null

    var lastScreenX: Float = 0.5f
        private set
    var lastScreenY: Float = 0.5f
        private set

    /** screenY before the down-reach amplifier — used by the droop-baseline update. */
    var lastRawScreenY: Float = 0.5f
        private set

    companion object {
        private const val SAMPLES_NEEDED = 30
        private const val WAIT_DURATION_MS = 2000L
        private const val COLLECT_DURATION_MS = 2500L
        private const val DEFAULT_SENSITIVITY = 0.18f
        private const val DEFAULT_WARNING_DELAY_MS = 2000L

        // screenY beyond (1 - this) -> OFF_SCREEN_DOWN.
        private const val DOWN_OFF_SCREEN_THRESHOLD = 0.15f

        // Values <= PIVOT (content-reading band) are untouched; above it they are
        // stretched by GAIN so a genuine look-down commits strongly.
        private const val DOWN_REACH_PIVOT = 0.82f
        private const val DOWN_REACH_GAIN = 1.60f

        // Droop lane fires when irisY AND upperLidDrop both exceed their per-user
        // baselines by these margins.
        private const val DROOP_IRIS_DELTA = 0.06f
        private const val DROOP_LID_DELTA = 0.06f
        private const val DROOP_BASELINE_ALPHA = 0.05f
        private const val DROOP_DOWN_HOLD_FRAMES = 3

        private const val RIDGE_LAMBDA = 0.01f

        // Frame-over-frame openness dip (vs. smoothed) that counts as a blink.
        private const val BLINK_DIP_RATIO = 0.75f

        // After any blink signal, down/up verdicts are suppressed for this many frames.
        private const val DOWN_BLINK_IMMUNITY_FRAMES = 8

        private const val OPENNESS_REF_DECAY = 0.991f
        private const val EYE_GATE_ENTER_RATIO = 0.82f
        private const val EYE_GATE_EXIT_RATIO = 0.88f

        // Gate held this long (~0.8s) = eyes genuinely closed -> treated as look-away.
        private const val GATE_SUSTAINED_FRAMES = 25

        private const val HEAD_PITCH_OFF_SCREEN_THRESHOLD = 30f
        private const val HEAD_PITCH_UP_HOLD_FRAMES = 5
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
        centerYawSamples.clear()
        centerPitchSamples.clear()
        sampleCount = 0
        smoothedIrisX = null
        smoothedIrisY = null
        smoothedOpenness = null
        smoothedLidDrop = null
        pendingStatus = null
        pendingStatusCount = 0
        lastStableStatus = GazeStatus.UNKNOWN
        frameCount = 0
        lastBlinkFrame = -DOWN_BLINK_IMMUNITY_FRAMES - 1
        opennessRef = null
        eyeGateActive = false
        eyeGateFrameCount = 0
        upPitchHoldFrames = 0
        droopDownHoldFrames = 0
        droopDownActive = false
        baselineIrisY = null
        baselineLidDrop = null
    }

    // Read-outs for debugging UIs.
    fun getEyeOpennessRef(): Float? = opennessRef
    fun isEyeGateActive(): Boolean = eyeGateActive
    fun isDroopDownActive(): Boolean = droopDownActive
    fun getDroopIrisBase(): Float? = baselineIrisY
    fun getDroopLidBase(): Float? = baselineLidDrop

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

                // Head pose at the centre dot = the "looking straight at the screen" baseline.
                if (currentPoint == CalibrationPoint.MIDDLE_CENTER) {
                    centerYawSamples.add(headPose.yaw)
                    centerPitchSamples.add(headPose.pitch)
                }

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

        val baselineYaw = if (centerYawSamples.isNotEmpty()) centerYawSamples.average().toFloat() else 0f
        val baselinePitch = if (centerPitchSamples.isNotEmpty()) centerPitchSamples.average().toFloat() else 0f

        calibrationData = CalibrationData(
            weightsX = weightsX,
            weightsY = weightsY,
            isCalibrated = true,
            baselineYaw = baselineYaw,
            baselinePitch = baselinePitch
        )

        saveCalibration()
        state = CalibrationState.ALL_DONE
        onAllComplete?.invoke()
        return true
    }

    /**
     * Per-frame verdict. Order of stages:
     * blink EMA-gate -> droop-down lane -> EMA smoothing -> ridge regression ->
     * down-reach amplifier -> thresholds -> head-pose override -> blink immunity ->
     * hysteresis.
     */
    fun checkGazeStatus(
        irisX: Float,
        irisY: Float,
        eyeOpenness: Float,
        upperLidDropRatio: Float,
        headPose: HeadPose,
        isLidTransitioning: Boolean = false
    ): GazeStatus {
        if (!calibrationData.isCalibrated) return GazeStatus.UNKNOWN

        frameCount++

        // ── Blink EMA-gate ───────────────────────────────────────────────────
        val prevRef = opennessRef
        opennessRef = if (prevRef == null) eyeOpenness else maxOf(prevRef * OPENNESS_REF_DECAY, eyeOpenness)

        val ref = opennessRef!!
        if (eyeGateActive) {
            if (eyeOpenness > ref * EYE_GATE_EXIT_RATIO) {
                eyeGateActive = false
                eyeGateFrameCount = 0
            } else {
                eyeGateFrameCount++
            }
        } else if (eyeOpenness < ref * EYE_GATE_ENTER_RATIO) {
            eyeGateActive = true
            eyeGateFrameCount = 1
        }

        val isBlink = isLidTransitioning || eyeGateActive ||
                (smoothedOpenness != null && eyeOpenness < smoothedOpenness!! * BLINK_DIP_RATIO)
        if (isBlink) {
            lastBlinkFrame = frameCount
        }

        // ── Adaptive droop-down lane ─────────────────────────────────────────
        val irisBase = baselineIrisY
        val lidBase = baselineLidDrop
        val irisOver = irisBase != null && irisY > irisBase + DROOP_IRIS_DELTA
        val lidOver = lidBase != null && upperLidDropRatio > lidBase + DROOP_LID_DELTA
        val droopDown = irisBase != null && lidBase != null && irisOver && lidOver &&
                !isLidTransitioning &&
                frameCount - lastBlinkFrame > DOWN_BLINK_IMMUNITY_FRAMES
        droopDownHoldFrames = if (droopDown) droopDownHoldFrames + 1 else 0
        droopDownActive = droopDownHoldFrames >= DROOP_DOWN_HOLD_FRAMES
        if (droopDownActive) {
            return applyHysteresis(GazeStatus.OFF_SCREEN_DOWN)
        }

        // Learn the per-user baseline only from settled, mid-screen reading frames.
        if (!isLidTransitioning && !eyeGateActive && droopDownHoldFrames == 0 &&
            lastRawScreenY in 0.30f..0.70f &&
            !(irisOver && irisY > (irisBase ?: 0f) + DROOP_IRIS_DELTA * 0.5f)
        ) {
            baselineIrisY = ema(baselineIrisY, irisY, DROOP_BASELINE_ALPHA)
            baselineLidDrop = ema(baselineLidDrop, upperLidDropRatio, DROOP_BASELINE_ALPHA)
        }

        if (isLidTransitioning || eyeGateActive) {
            return if (eyeGateFrameCount > GATE_SUSTAINED_FRAMES) {
                lastBlinkFrame = frameCount
                applyHysteresis(GazeStatus.OFF_SCREEN_DOWN)
            } else {
                lastStableStatus
            }
        }

        // ── EMA smoothing -> regression -> screen coordinates ────────────────
        smoothedIrisX = ema(smoothedIrisX, irisX, smoothingAlpha)
        smoothedIrisY = ema(smoothedIrisY, irisY, smoothingAlpha)
        smoothedOpenness = ema(smoothedOpenness, eyeOpenness, smoothingAlpha)
        smoothedLidDrop = ema(smoothedLidDrop, upperLidDropRatio, smoothingAlpha)

        val screenX = predictScreenX(smoothedIrisX!!, smoothedIrisY!!)
        val rawScreenY = predictScreenY(smoothedIrisY!!, smoothedOpenness!!, smoothedLidDrop!!)
        val screenY = amplifyDownReach(rawScreenY)

        lastScreenX = screenX
        lastRawScreenY = rawScreenY
        lastScreenY = screenY

        // ── Thresholds ───────────────────────────────────────────────────────
        val hStatus = when {
            screenX < 0f - sensitivity -> GazeStatus.OFF_SCREEN_LEFT
            screenX > 1f + sensitivity -> GazeStatus.OFF_SCREEN_RIGHT
            screenX < 0f               -> GazeStatus.WARNING_LEFT
            screenX > 1f               -> GazeStatus.WARNING_RIGHT
            else                       -> null
        }

        val vStatus = when {
            screenY < 0f - upGazeBand                -> GazeStatus.OFF_SCREEN_UP
            screenY > 1f - DOWN_OFF_SCREEN_THRESHOLD -> GazeStatus.OFF_SCREEN_DOWN
            screenY < 0f                             -> GazeStatus.WARNING_UP
            else                                     -> null
        }

        // ── Head-pose override (relative to calibrated baseline) ─────────────
        val relativeYaw = headPose.yaw - calibrationData.baselineYaw
        val relativePitch = headPose.pitch - calibrationData.baselinePitch

        val tiltedUp = relativePitch < -headUpPitchThreshold
        upPitchHoldFrames = if (tiltedUp) upPitchHoldFrames + 1 else 0
        val headStatus = when {
            relativeYaw > headYawThreshold                            -> GazeStatus.OFF_SCREEN_RIGHT
            relativeYaw < -headYawThreshold                           -> GazeStatus.OFF_SCREEN_LEFT
            relativePitch > HEAD_PITCH_OFF_SCREEN_THRESHOLD           -> GazeStatus.OFF_SCREEN_DOWN
            tiltedUp && upPitchHoldFrames >= HEAD_PITCH_UP_HOLD_FRAMES -> GazeStatus.OFF_SCREEN_UP
            else                                                      -> null
        }

        val rawStatus = when {
            headStatus != null                     -> headStatus
            hStatus == GazeStatus.OFF_SCREEN_LEFT  -> GazeStatus.OFF_SCREEN_LEFT
            hStatus == GazeStatus.OFF_SCREEN_RIGHT -> GazeStatus.OFF_SCREEN_RIGHT
            vStatus == GazeStatus.OFF_SCREEN_UP    -> GazeStatus.OFF_SCREEN_UP
            vStatus == GazeStatus.OFF_SCREEN_DOWN  -> GazeStatus.OFF_SCREEN_DOWN
            hStatus == GazeStatus.WARNING_LEFT     -> GazeStatus.WARNING_LEFT
            hStatus == GazeStatus.WARNING_RIGHT    -> GazeStatus.WARNING_RIGHT
            vStatus == GazeStatus.WARNING_UP       -> GazeStatus.WARNING_UP
            vStatus == GazeStatus.WARNING_DOWN     -> GazeStatus.WARNING_DOWN
            else                                   -> GazeStatus.ON_SCREEN
        }

        // ── Blink immunity: a blink can neither cause nor keep alive a down verdict ──
        val blinkImmunityActive = frameCount - lastBlinkFrame <= DOWN_BLINK_IMMUNITY_FRAMES
        if (blinkImmunityActive &&
            (rawStatus == GazeStatus.OFF_SCREEN_DOWN || rawStatus == GazeStatus.WARNING_DOWN)) {
            return lastStableStatus
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
        val screenY = amplifyDownReach(predictScreenY(sY, sO, sL))

        val relativeYaw = headPose.yaw - calibrationData.baselineYaw
        val relativePitch = headPose.pitch - calibrationData.baselinePitch

        return when {
            relativeYaw > headYawThreshold                   -> GazeDirection.RIGHT
            relativeYaw < -headYawThreshold                  -> GazeDirection.LEFT
            relativePitch > HEAD_PITCH_OFF_SCREEN_THRESHOLD  -> GazeDirection.DOWN
            relativePitch < -headUpPitchThreshold            -> GazeDirection.UP
            screenX < 0f - sensitivity                       -> GazeDirection.LEFT
            screenX > 1f + sensitivity                       -> GazeDirection.RIGHT
            screenY < 0f - upGazeBand                        -> GazeDirection.UP
            screenY > 1f - DOWN_OFF_SCREEN_THRESHOLD         -> GazeDirection.DOWN
            else                                             -> GazeDirection.CENTER
        }
    }

    private fun amplifyDownReach(screenY: Float): Float {
        if (screenY <= DOWN_REACH_PIVOT) return screenY
        return DOWN_REACH_PIVOT + (screenY - DOWN_REACH_PIVOT) * DOWN_REACH_GAIN
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
            putFloat("baselineYaw", calibrationData.baselineYaw)
            putFloat("baselinePitch", calibrationData.baselinePitch)
            putBoolean("isCalibrated", true)
            apply()
        }
    }

    private fun loadCalibration() {
        // A calibration saved by the older pipeline has no head-pose baseline; using it
        // would compare head pose against 0 instead of the student's real baseline.
        // Treat it as "not calibrated" so the student recalibrates.
        if (prefs.getBoolean("isCalibrated", false) && prefs.contains("baselineYaw")) {
            val countX = prefs.getInt("weightsX_count", 0)
            val countY = prefs.getInt("weightsY_count", 0)
            if (countX > 0 && countY > 0) {
                calibrationData = CalibrationData(
                    weightsX = FloatArray(countX) { prefs.getFloat("weightX_$it", 0f) },
                    weightsY = FloatArray(countY) { prefs.getFloat("weightY_$it", 0f) },
                    isCalibrated = true,
                    baselineYaw = prefs.getFloat("baselineYaw", 0f),
                    baselinePitch = prefs.getFloat("baselinePitch", 0f)
                )
            }
        }
    }

    /** Debug dump of the regression weights, training samples and live values. */
    fun getCalibrationTable(): String {
        if (!calibrationData.isCalibrated) return "Not calibrated yet."

        val sb = StringBuilder()

        sb.appendLine("=== X MODEL  (5 features: bias, irisX, irisY, irisX^2, irisX*irisY) ===")
        sb.appendLine("weightsX = [${calibrationData.weightsX.joinToString(", ") { "%.6f".format(it) }}]")
        sb.appendLine()
        if (allSamplesX.isEmpty()) {
            sb.appendLine("Samples not in memory (loaded from prefs). Re-calibrate to see training data.")
        } else {
            sb.appendLine(
                "%6s %8s %8s %8s %8s %8s %8s %8s %8s %8s".format(
                    "#", "irisX", "irisY", "target", "f_bias", "f_irisX", "f_irisY", "f_irisX2", "f_irisXY", "pred"
                )
            )
            for ((i, s) in allSamplesX.withIndex()) {
                val f = buildFeaturesX(s.irisX, s.irisY)
                val pred = calibrationData.weightsX.indices.sumOf {
                    (calibrationData.weightsX[it] * f[it]).toDouble()
                }.toFloat()
                sb.appendLine(
                    "%6d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f".format(
                        i, s.irisX, s.irisY, s.targetScreenX,
                        f[0], f[1], f[2], f[3], f[4], pred
                    )
                )
            }
        }

        sb.appendLine()
        sb.appendLine("=== Y MODEL  (6 features: bias, irisY, open, lidDrop, irisY^2, irisY*lid) ===")
        sb.appendLine("weightsY = [${calibrationData.weightsY.joinToString(", ") { "%.6f".format(it) }}]")
        sb.appendLine()
        if (allSamplesY.isEmpty()) {
            sb.appendLine("Samples not in memory (loaded from prefs). Re-calibrate to see training data.")
        } else {
            sb.appendLine(
                "%6s %8s %8s %8s %8s %8s %8s %8s %8s %8s %8s %8s".format(
                    "#", "irisY", "open", "lidDrop", "target",
                    "f_bias", "f_irisY", "f_open", "f_lid", "f_irisY2", "f_irisLid", "pred"
                )
            )
            for ((i, s) in allSamplesY.withIndex()) {
                val f = buildFeaturesY(s.irisY, s.eyeOpenness, s.upperLidDropRatio)
                val pred = calibrationData.weightsY.indices.sumOf {
                    (calibrationData.weightsY[it] * f[it]).toDouble()
                }.toFloat()
                sb.appendLine(
                    "%6d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f".format(
                        i, s.irisY, s.eyeOpenness, s.upperLidDropRatio, s.targetScreenY,
                        f[0], f[1], f[2], f[3], f[4], f[5], pred
                    )
                )
            }
        }

        sb.appendLine()
        sb.appendLine("baselines: yaw=%.2f  pitch=%.2f".format(
            calibrationData.baselineYaw, calibrationData.baselinePitch
        ))

        val sx = smoothedIrisX
        val sy = smoothedIrisY
        val so = smoothedOpenness
        val sl = smoothedLidDrop
        if (sx != null && sy != null && so != null && sl != null) {
            val fX = buildFeaturesX(sx, sy)
            val fY = buildFeaturesY(sy, so, sl)
            val predX = calibrationData.weightsX.indices.sumOf {
                (calibrationData.weightsX[it] * fX[it]).toDouble()
            }.toFloat()
            val predY = calibrationData.weightsY.indices.sumOf {
                (calibrationData.weightsY[it] * fY[it]).toDouble()
            }.toFloat()
            sb.appendLine()
            sb.appendLine("=== LIVE (smoothed values, current frame) ===")
            sb.appendLine("irisX=%.4f  irisY=%.4f  open=%.4f  lid=%.4f".format(sx, sy, so, sl))
            sb.appendLine(
                "%6s %8s %8s %8s %8s %8s %8s %8s %8s %8s".format(
                    "X", "sx", "sy", "screenX", "f0", "f1", "f2", "f3", "f4", "w*x"
                )
            )
            sb.appendLine(
                "%6s %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f".format(
                    "X", sx, sy, predX, fX[0], fX[1], fX[2], fX[3], fX[4], predX
                )
            )
            sb.appendLine()
            sb.appendLine(
                "%6s %8s %8s %8s %8s %8s %8s %8s %8s %8s %8s %8s".format(
                    "Y", "sy", "open", "lid", "screenY", "f0", "f1", "f2", "f3", "f4", "f5", "w*y"
                )
            )
            sb.appendLine(
                "%6s %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f".format(
                    "Y", sy, so, sl, predY, fY[0], fY[1], fY[2], fY[3], fY[4], fY[5], predY
                )
            )
        }

        return sb.toString()
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