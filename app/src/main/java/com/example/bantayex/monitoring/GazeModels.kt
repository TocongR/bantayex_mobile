package com.example.bantayex.monitoring

/**
 * Plain data shapes the monitoring package operates on.
 *
 * Changed for the GazeDebug pipeline sync: CalibrationData now carries baselineYaw /
 * baselinePitch (head pose captured while looking at the centre calibration dot).
 * GazeData no longer has `direction` (matches GazeDebug; nothing in BantayEx read it —
 * use CalibrationManager.getGazeDirection() if a direction is needed).
 */

enum class GazeDirection {
    CENTER, LEFT, RIGHT, UP, DOWN
}

enum class GazeStatus {
    ON_SCREEN,
    OFF_SCREEN_LEFT, OFF_SCREEN_RIGHT,
    OFF_SCREEN_UP, OFF_SCREEN_DOWN,
    WARNING_LEFT, WARNING_RIGHT,
    WARNING_UP, WARNING_DOWN,
    UNKNOWN
}

enum class CalibrationPoint {
    TOP_LEFT,    TOP_CENTER,    TOP_RIGHT,
    MIDDLE_LEFT, MIDDLE_CENTER, MIDDLE_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

enum class CalibrationState {
    NOT_STARTED, WAITING_FOR_USER, COLLECTING, POINT_COMPLETE, TRANSITIONING, ALL_DONE, FAILED
}

data class GazeData(
    val irisX: Float,
    val irisY: Float,
    val eyeOpenness: Float,
    val upperLidDropRatio: Float,
    val headPose: HeadPose
)

data class HeadPose(
    val pitch: Float,
    val yaw: Float,
    val roll: Float
)

data class CalibrationSampleX(
    val irisX: Float,
    val irisY: Float,
    val targetScreenX: Float
)

data class CalibrationSampleY(
    val irisY: Float,
    val eyeOpenness: Float,
    val upperLidDropRatio: Float,
    val targetScreenY: Float
)

data class CalibrationData(
    var weightsX: FloatArray = floatArrayOf(0f, 1f, 0f, 0f, 0f),
    var weightsY: FloatArray = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f),
    var isCalibrated: Boolean = false,
    // Head pose while looking straight at the screen (centre calibration dot).
    var baselineYaw: Float = 0f,
    var baselinePitch: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationData) return false
        return weightsX.contentEquals(other.weightsX) &&
                weightsY.contentEquals(other.weightsY) &&
                isCalibrated == other.isCalibrated &&
                baselineYaw == other.baselineYaw &&
                baselinePitch == other.baselinePitch
    }
    override fun hashCode(): Int {
        var result = weightsX.contentHashCode()
        result = 31 * result + weightsY.contentHashCode()
        result = 31 * result + isCalibrated.hashCode()
        result = 31 * result + baselineYaw.hashCode()
        result = 31 * result + baselinePitch.hashCode()
        return result
    }
}