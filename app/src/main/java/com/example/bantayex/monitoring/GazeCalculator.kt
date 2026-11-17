package com.example.bantayex.monitoring

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

object GazeCalculator {

    fun calculate(landmarks: List<NormalizedLandmark>): GazeData? {
        if (landmarks.size < 478) return null

        val leftIris = landmarks[468]
        val rightIris = landmarks[473]

        val leftEyeLeft = landmarks[33]
        val leftEyeRight = landmarks[133]
        val leftEyeTop = landmarks[159]
        val leftEyeBottom = landmarks[145]
        val leftEyeTopOuter = landmarks[158]
        val leftEyeTopInner = landmarks[160]
        val leftEyeBottomOuter = landmarks[144]
        val leftEyeBottomInner = landmarks[153]

        val rightEyeLeft = landmarks[362]
        val rightEyeRight = landmarks[263]
        val rightEyeTop = landmarks[386]
        val rightEyeBottom = landmarks[374]
        val rightEyeTopOuter = landmarks[385]
        val rightEyeTopInner = landmarks[387]
        val rightEyeBottomOuter = landmarks[373]
        val rightEyeBottomInner = landmarks[380]

        val leftMinX = min(leftEyeLeft.x(), leftEyeRight.x())
        val leftMaxX = max(leftEyeLeft.x(), leftEyeRight.x())
        val leftMinY = minOf(leftEyeTop.y(), leftEyeTopOuter.y(), leftEyeTopInner.y())
        val leftMaxY = maxOf(leftEyeBottom.y(), leftEyeBottomOuter.y(), leftEyeBottomInner.y())

        val leftW = (leftMaxX - leftMinX).coerceAtLeast(0.001f)
        val leftH = (leftMaxY - leftMinY).coerceAtLeast(0.001f)

        val leftIrisX = ((leftIris.x() - leftMinX) / leftW).coerceIn(0f, 1f)
        val leftIrisY = ((leftIris.y() - leftMinY) / leftH).coerceIn(0f, 1f)

        val rightMinX = min(rightEyeLeft.x(), rightEyeRight.x())
        val rightMaxX = max(rightEyeLeft.x(), rightEyeRight.x())
        val rightMinY = minOf(rightEyeTop.y(), rightEyeTopOuter.y(), rightEyeTopInner.y())
        val rightMaxY = maxOf(rightEyeBottom.y(), rightEyeBottomOuter.y(), rightEyeBottomInner.y())

        val rightW = (rightMaxX - rightMinX).coerceAtLeast(0.001f)
        val rightH = (rightMaxY - rightMinY).coerceAtLeast(0.001f)

        val rightIrisX = ((rightIris.x() - rightMinX) / rightW).coerceIn(0f, 1f)
        val rightIrisY = ((rightIris.y() - rightMinY) / rightH).coerceIn(0f, 1f)

        val avgX = (leftIrisX + rightIrisX) / 2f
        val avgY = (leftIrisY + rightIrisY) / 2f

        val leftOpenness = leftH / leftW.coerceAtLeast(0.001f)
        val rightOpenness = rightH / rightW.coerceAtLeast(0.001f)
        val eyeOpenness = (leftOpenness + rightOpenness) / 2f

        val leftGapAbove = ((leftIris.y() - leftMinY) / leftH).coerceIn(0f, 1f)
        val rightGapAbove = ((rightIris.y() - rightMinY) / rightH).coerceIn(0f, 1f)
        val upperLidDropRatio = 1f - ((leftGapAbove + rightGapAbove) / 2f)

        val headPose = calculateHeadPose(landmarks)

        return GazeData(
            irisX = avgX,
            irisY = avgY,
            eyeOpenness = eyeOpenness,
            upperLidDropRatio = upperLidDropRatio,
            headPose = headPose
        )
    }

    private fun calculateHeadPose(landmarks: List<NormalizedLandmark>): HeadPose {
        if (landmarks.size < 468) return HeadPose(0f, 0f, 0f)

        return try {
            val noseTip = landmarks[1]
            val leftEye = landmarks[33]
            val rightEye = landmarks[263]
            val leftMouth = landmarks[61]
            val rightMouth = landmarks[291]

            val eyeMidX = (leftEye.x() + rightEye.x()) / 2f
            val yaw = (noseTip.x() - eyeMidX) * 600f

            val eyeMidY = (leftEye.y() + rightEye.y()) / 2f
            val mouthMidY = (leftMouth.y() + rightMouth.y()) / 2f
            val faceMidY = (eyeMidY + mouthMidY) / 2f
            val pitch = (noseTip.y() - faceMidY) * 400f

            val eyeDx = rightEye.x() - leftEye.x()
            val eyeDy = rightEye.y() - leftEye.y()
            val roll = Math.toDegrees(atan2(eyeDy.toDouble(), eyeDx.toDouble())).toFloat()

            HeadPose(pitch, yaw, roll)
        } catch (e: Exception) {
            HeadPose(0f, 0f, 0f)
        }
    }
}