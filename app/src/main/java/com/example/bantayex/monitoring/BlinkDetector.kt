package com.example.bantayex.monitoring

/**
 * Unchanged from the old version — already a single well-contained file doing exactly
 * one job, with genuinely useful comments explaining the tuning decisions (rolling
 * baseline, velocity-based lid-transition detection). Only the package path changed.
 */
enum class EyeState {
    OPEN,
    LID_TRANSITIONING, // eyelid is actively moving (closing or reopening) — gaze samples during
    // this state are unreliable and must not feed the gaze hysteresis buffer
    BLINKING,
    SUSTAINED_CLOSED
}

class BlinkDetector {

    // Rolling baseline tracks "openness when eyes are open," updated continuously
    // so it follows gaze-direction-related drift (e.g. looking down) instead of
    // staying pinned to one calibration-time number.
    private var rollingBaseline: Float = DEFAULT_BASELINE
    private val baselineAlpha = 0.05f // slow-moving average — ignores the blink dip itself

    private var closedFrameCount = 0
    private var lastState = EyeState.OPEN

    // Previous frame's openness, used purely to compute velocity. This is independent
    // of rollingBaseline (which is deliberately slow/smoothed) so it reacts immediately.
    private var previousOpenness: Float? = null

    companion object {
        private const val DEFAULT_BASELINE = 0.30f
        private const val DROP_RATIO = 0.55f   // openness must drop below 55% of current baseline
        private const val MAX_BLINK_FRAMES = 12

        // A frame-over-frame openness change larger than this fraction of the rolling
        // baseline indicates the eyelid is actively moving (closing or reopening), even
        // before the absolute openness ratio crosses DROP_RATIO. This fires several
        // frames earlier than the openness-threshold check, closing the lag during which
        // a half-closed eyelid was distorting irisY / upperLidDropRatio in GazeCalculator.
        private const val VELOCITY_RATIO_THRESHOLD = 0.12f
    }

    fun setBaseline(averageOpenness: Float) {
        if (averageOpenness > 0.05f) {
            rollingBaseline = averageOpenness
        }
    }

    fun update(eyeOpenness: Float): EyeState {
        val dynamicThreshold = rollingBaseline * DROP_RATIO
        val isLow = eyeOpenness < dynamicThreshold

        val prev = previousOpenness
        val velocity = if (prev != null) eyeOpenness - prev else 0f
        val velocityMagnitudeRatio = if (rollingBaseline > 0.001f) kotlin.math.abs(velocity) / rollingBaseline else 0f
        val isLidMoving = velocityMagnitudeRatio > VELOCITY_RATIO_THRESHOLD
        previousOpenness = eyeOpenness

        if (isLow) {
            closedFrameCount++
        } else {
            closedFrameCount = 0
            // Only update the rolling baseline while eyes are clearly open AND stable
            // (not actively transitioning). This lets it follow gradual gaze-direction
            // drift (looking down) without being dragged down by the blink dip itself,
            // and without being polluted by the transition frames either.
            if (!isLidMoving) {
                rollingBaseline = rollingBaseline + baselineAlpha * (eyeOpenness - rollingBaseline)
            }
        }

        lastState = when {
            // Sustained closure always wins — eyes have been below threshold too long
            // to still be a blink, regardless of current frame velocity.
            isLow && closedFrameCount > MAX_BLINK_FRAMES -> EyeState.SUSTAINED_CLOSED
            isLow -> EyeState.BLINKING
            // Eyes are "open" by the openness-ratio test, but the lid is still actively
            // moving (e.g. blink is just starting, or just finishing reopening). Treat
            // this the same as BLINKING for downstream gaze purposes: don't trust this
            // frame's gaze sample.
            isLidMoving -> EyeState.LID_TRANSITIONING
            else -> EyeState.OPEN
        }

        return lastState
    }

    fun reset() {
        closedFrameCount = 0
        lastState = EyeState.OPEN
        previousOpenness = null
    }
}