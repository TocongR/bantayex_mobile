package com.example.bantayex.monitoring

/**
 * Synced from GazeDebug. Changes vs. the previous BantayEx version:
 *  - VELOCITY_RATIO_THRESHOLD 0.12 -> 0.08 (catches the lid closing earlier)
 *  - POST_BLINK_COOLDOWN_FRAMES: after the eye reopens, the iris/lid geometry is
 *    still distorted for a few frames, so the state stays LID_TRANSITIONING
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

    // Frames left in the post-blink cooldown (eye has reopened but geometry is still settling).
    private var postBlinkCooldown = 0

    // Previous frame's openness, used purely to compute velocity. This is independent
    // of rollingBaseline (which is deliberately slow/smoothed) so it reacts immediately.
    private var previousOpenness: Float? = null

    companion object {
        private const val DEFAULT_BASELINE = 0.30f
        private const val DROP_RATIO = 0.55f   // openness must drop below 55% of current baseline
        private const val MAX_BLINK_FRAMES = 12

        // Even after the eye reopens, iris/lid geometry stays distorted for a few frames.
        private const val POST_BLINK_COOLDOWN_FRAMES = 5

        // A frame-over-frame openness change larger than this fraction of the rolling
        // baseline indicates the eyelid is actively moving (closing or reopening), even
        // before the absolute openness ratio crosses DROP_RATIO.
        private const val VELOCITY_RATIO_THRESHOLD = 0.08f
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
            // (not actively transitioning), so it follows gradual drift without being
            // dragged down by the blink dip or polluted by transition frames.
            if (!isLidMoving) {
                rollingBaseline = rollingBaseline + baselineAlpha * (eyeOpenness - rollingBaseline)
            }
        }

        lastState = when {
            // Sustained closure always wins — eyes have been below threshold too long
            // to still be a blink, regardless of current frame velocity.
            isLow && closedFrameCount > MAX_BLINK_FRAMES -> EyeState.SUSTAINED_CLOSED
            isLow -> {
                postBlinkCooldown = POST_BLINK_COOLDOWN_FRAMES
                EyeState.BLINKING
            }
            // Eyes are "open" by the ratio test but the lid is still moving (a blink is
            // just starting or just finishing): don't trust this frame's gaze sample.
            isLidMoving -> {
                postBlinkCooldown = POST_BLINK_COOLDOWN_FRAMES
                EyeState.LID_TRANSITIONING
            }
            postBlinkCooldown > 0 -> {
                postBlinkCooldown--
                EyeState.LID_TRANSITIONING
            }
            else -> EyeState.OPEN
        }

        return lastState
    }

    fun reset() {
        closedFrameCount = 0
        lastState = EyeState.OPEN
        previousOpenness = null
        postBlinkCooldown = 0
    }
}