package com.example.bantayex.features.student.calibration

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.bantayex.databinding.ActivityCalibrationBinding
import com.example.bantayex.features.student.takeexam.TakeExamActivity
import com.example.bantayex.monitoring.CalibrationManager
import com.example.bantayex.monitoring.CalibrationPoint
import com.example.bantayex.monitoring.CalibrationState
import com.example.bantayex.monitoring.GazeCalculator
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * No ViewModel here on purpose — CameraX's bindToLifecycle(this, ...) genuinely needs
 * the Activity itself, there's no Firestore call anywhere in this screen, and the real
 * business logic (the 9-point calibration state machine, ridge regression fitting) is
 * already correctly isolated in CalibrationManager, not scattered in this file. A
 * ViewModel here would just be an empty pass-through wrapper around CalibrationManager
 * calls — same reasoning as WelcomeActivity and ResultActivity.
 *
 * Logic is otherwise unchanged from the old version — only the package and import
 * paths changed.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var calibrationManager: CalibrationManager
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isCalibrating = false
    private var currentPointIndex = 0
    private var isPointTransitioning = false
    @Volatile private var imageWidth = 1
    @Volatile private var imageHeight = 1

    private lateinit var examId: String
    private lateinit var examCode: String
    private lateinit var studentName: String
    private val openinessSamples = mutableListOf<Float>()

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Force full screen brightness for the entire calibration flow.
        // The white backdrop during dot calibration needs to actually be
        // bright, and consistent lighting also helps face-landmark
        // detection accuracy. Restored in onDestroy().
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }

        // ── Go edge-to-edge: hide status bar + nav bar so calibration dots can
        // use the FULL screen, all the way into the corners. Without this, the
        // dots sit inset from where the user's eyes actually need to look,
        // which throws off calibration accuracy. Restored in onDestroy().
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        examId = intent.getStringExtra("examId") ?: ""
        examCode = intent.getStringExtra("examCode") ?: ""
        studentName = intent.getStringExtra("studentName") ?: ""

        cameraExecutor = Executors.newSingleThreadExecutor()
        calibrationManager = CalibrationManager(this)

        calibrationManager.onPointComplete = { point ->
            mainHandler.post { onCalibrationPointComplete(point) }
        }
        calibrationManager.onFailed = {
            mainHandler.post { onCalibrationFailed() }
        }

        binding.btnRetry.setOnClickListener { startCalibrationSequence() }
        binding.btnStartCalibration.setOnClickListener { onStartCalibrationClicked() }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupFaceLandmarker()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-hide system bars if the user swipes them back in mid-calibration
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupFaceLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { }
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(this, options)

        // Show the idle "position your head" prompt instead of auto-starting.
        // The live camera preview stays visible behind it; no dots yet.
        binding.preCalibrationContainer.visibility = View.VISIBLE
    }

    private fun onStartCalibrationClicked() {
        binding.preCalibrationContainer.visibility = View.GONE
        binding.previewView.visibility = View.GONE
        binding.whiteBackground.visibility = View.VISIBLE

        // Small beat so the white backdrop is actually rendered/at full
        // brightness before the first dot appears.
        mainHandler.postDelayed({ startCalibrationSequence() }, 200)
    }

    private fun startCalibrationSequence() {
        binding.tvCalibrationFailed.visibility = View.GONE
        binding.btnRetry.visibility = View.GONE
        isCalibrating = true
        currentPointIndex = 0
        isPointTransitioning = false
        openinessSamples.clear()
        calibrationManager.reset()
        mainHandler.postDelayed({ startCurrentPoint() }, 500)
    }

    private fun startCurrentPoint() {
        val sequence = calibrationManager.calibrationSequence
        if (currentPointIndex >= sequence.size) {
            completeCalibration()
            return
        }
        isPointTransitioning = false
        calibrationManager.startPoint(sequence[currentPointIndex])
    }

    private fun onCalibrationPointComplete(point: CalibrationPoint) {
        if (isPointTransitioning) return
        isPointTransitioning = true
        calibrationManager.markTransitioning()
        mainHandler.postDelayed({
            currentPointIndex++
            if (currentPointIndex >= calibrationManager.calibrationSequence.size) {
                completeCalibration()
            } else {
                startCurrentPoint()
            }
        }, 900)
    }

    private fun onCalibrationFailed() {
        isCalibrating = false
        binding.tvCalibrationFailed.visibility = View.VISIBLE
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun completeCalibration() {
        isCalibrating = false
        isPointTransitioning = false
        val success = calibrationManager.finishCalibration()

        runOnUiThread {
            binding.overlayView.hideCalibration()
            if (success) {
                val avgOpenness = if (openinessSamples.isNotEmpty())
                    openinessSamples.average().toFloat() else 0.30f
                proceedToExam(baselineOpenness = avgOpenness)
            } else {
                // Back to the white screen with a retry option — matches prior
                // behavior of retrying in-place rather than returning to the
                // pre-calibration camera prompt.
                binding.tvCalibrationFailed.visibility = View.VISIBLE
                binding.btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun proceedToExam(baselineOpenness: Float = 0.30f) {
        val intent = Intent(this, TakeExamActivity::class.java)
        intent.putExtra("examId", examId)
        intent.putExtra("examCode", examCode)
        intent.putExtra("studentName", studentName)
        intent.putExtra("monitoringEnabled", true)
        intent.putExtra("baselineOpenness", baselineOpenness)
        startActivity(intent)
        finish()
    }

    private fun onResult(result: FaceLandmarkerResult) {
        if (!isCalibrating) return
        val gaze = result.faceLandmarks().firstOrNull()?.let { GazeCalculator.calculate(it) }

        if (!isPointTransitioning && gaze != null) {
            calibrationManager.processSample(
                gaze.irisX, gaze.irisY, gaze.eyeOpenness, gaze.upperLidDropRatio, gaze.headPose
            )
            openinessSamples.add(gaze.eyeOpenness)
        }

        val point = calibrationManager.getCurrentPoint()
        val state = calibrationManager.state
        val progress = calibrationManager.getProgress()

        runOnUiThread {
            if (point != null) {
                binding.overlayView.showCalibration(
                    point,
                    if (isPointTransitioning) CalibrationState.TRANSITIONING else state,
                    progress,
                    currentPointIndex,
                    calibrationManager.calibrationSequence.size
                )
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> detectFace(imageProxy) } }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectFace(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
            val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            imageWidth = corrected.width
            imageHeight = corrected.height
            faceLandmarker?.detectAsync(BitmapImageBuilder(corrected).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            setupFaceLandmarker()
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Restore brightness to whatever the system/user setting was
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        // Restore system bars for whatever screen comes next
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        faceLandmarker?.close()
        cameraExecutor.shutdown()
    }
}