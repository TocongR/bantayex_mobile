package com.example.bantayex.monitoring

/**
 * Was one 691-line file bundling 4 enums, 5 data classes, GazeCalculator, RidgeRegression,
 * and CalibrationManager all together. Split into one file per concern — this file now
 * only holds the plain data shapes everything else operates on. Logic is byte-for-byte
 * unchanged; only file organization changed.
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
    val direction: GazeDirection,
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
    var isCalibrated: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CalibrationData) return false
        return weightsX.contentEquals(other.weightsX) &&
                weightsY.contentEquals(other.weightsY) &&
                isCalibrated == other.isCalibrated
    }
    override fun hashCode(): Int {
        var result = weightsX.contentHashCode()
        result = 31 * result + weightsY.contentHashCode()
        result = 31 * result + isCalibrated.hashCode()
        return result
    }
}