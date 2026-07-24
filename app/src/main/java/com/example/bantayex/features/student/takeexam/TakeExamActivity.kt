package com.example.bantayex.features.student.takeexam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.example.bantayex.R
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.model.Question
import com.example.bantayex.data.model.Violation
import com.example.bantayex.databinding.ActivityTakeExamBinding
import com.example.bantayex.features.student.result.ResultActivity
import com.example.bantayex.monitoring.BlinkDetector
import com.example.bantayex.monitoring.CalibrationManager
import com.example.bantayex.monitoring.EyeState
import com.example.bantayex.monitoring.GazeCalculator
import com.example.bantayex.monitoring.GazeStatus
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * This is the file that used to be one 566-line god Activity mixing exam mechanics with
 * proctoring. Steps 5a-5c rebuilt the exam-taking mechanics clean, with zero monitoring
 * code. This step adds monitoring back — deliberately as a second, clearly-marked layer
 * on top of the already-working exam flow, not woven back through it.
 *
 * BUG FIX: the old version loaded the model from "models/face_landmarker.task", but
 * CalibrationActivity (Step 6b) loads the same file from "face_landmarker.task" — and the
 * actual asset sits at app/src/main/assets/face_landmarker.task, no "models/" subfolder.
 * The old TakeExamActivity's face landmarker would have silently failed to initialize
 * every single time. Fixed to match the real path.
 */
@AndroidEntryPoint
class TakeExamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTakeExamBinding
    private val viewModel: TakeExamViewModel by viewModels()
    private var countDownTimer: CountDownTimer? = null

    private var questions: List<Question> = emptyList()

    private var examStarted = false
    private var isSubmitting = false
    private var isPaused = false
    private val violationHandler = Handler(Looper.getMainLooper())
    private val violationGraceMs = 1500L
    private var violationRunnable: Runnable? = null

    // ── Monitoring fields ────────────────────────────────────────────────────
    private var monitoringEnabled = false
    private lateinit var calibrationManager: CalibrationManager
    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var cameraExecutor: ExecutorService
    @Volatile private var isClosing = false
    private val violationLog = mutableListOf<Violation>()
    private var lastGazeViolationTime = 0L
    private val gazeViolationCooldownMs = 5000L
    private val blinkDetector = BlinkDetector()

    // ── Away-time & repeated-glance abuse detection ─────────────────────────
    private var isCurrentlyAway = false
    private var awayStartTime = 0L
    private var awayViolationFired = false
    private val shortGlanceTimestamps = mutableListOf<Long>()

    private val awayViolationThresholdMs = 3000L
    private val minGlanceDurationMs = 250L
    private val rapidGlanceWindowMs = 10000L
    private val rapidGlanceMaxCount = 3

    // ── No-face grace period — a brief camera hiccup or blink-adjacent frame
    // shouldn't immediately read as a violation. Same 3s grace pattern as
    // look-away detection above.
    private var isCurrentlyFaceless = false
    private var facelessStartTime = 0L
    private var facelessViolationFired = false
    private val facelessViolationThresholdMs = 3000L

    // ── Banner hold — a violation/no-face banner must stay visible for at
    // least this long before a milder state (OK/WARNING) is allowed to
    // overwrite it. Without this, a brief repeated-look-away flag gets wiped
    // by the very next "back on screen" frame before the user can read it.
    private var bannerHoldUntil = 0L
    private val bannerHoldMs = 2000L

    private enum class GazeBannerState { OK, WARNING, VIOLATION, NO_FACE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Force full screen brightness for the whole exam session — keeps
        // gaze/face detection lighting consistent throughout. Restored in
        // onDestroy().
        window.attributes = window.attributes.apply {
            screenBrightness = 1.0f
        }

        binding = ActivityTakeExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val examCode = intent.getStringExtra("examCode") ?: ""
        val studentName = intent.getStringExtra("studentName") ?: ""

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSubmitting) return
                AlertDialog.Builder(this@TakeExamActivity)
                    .setTitle("Exam In Progress")
                    .setMessage("You cannot exit during an active exam session.\n\nYou must submit the exam to leave.")
                    .setPositiveButton("Return to Exam") { dialog, _ -> dialog.dismiss() }
                    .setNegativeButton("Submit Exam") { _, _ -> confirmSubmit() }
                    .setCancelable(false)
                    .show()
            }
        })

        binding.progressBar.visibility = View.VISIBLE
        viewModel.loadExam(examCode, studentName)

        binding.btnSubmit.setOnClickListener { confirmSubmit() }

        observeExamState()
        observeViolations()
        observeSubmitState()
    }

    private fun observeExamState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.examState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            binding.contentLayout.visibility = View.VISIBLE
                            startExam(state.data)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@TakeExamActivity, state.message, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun observeViolations() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.violations.collect { count ->
                    if (count > 0) {
                        binding.tvViolationCount.text = "⚠ $count violation(s) logged"
                        binding.tvViolationCount.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun observeSubmitState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.submitState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            val examResult = state.data
                            val intent = Intent(this@TakeExamActivity, ResultActivity::class.java)
                            intent.putExtra("score", examResult.score)
                            intent.putExtra("total", examResult.totalQuestions)
                            intent.putExtra("passed", examResult.passed)
                            intent.putExtra("studentName", examResult.studentName)
                            intent.putExtra("examTitle", examResult.examTitle)
                            intent.putExtra("violationLog", ArrayList(examResult.violationLog))
                            startActivity(intent)
                            finish()
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSubmit.isEnabled = true
                            isSubmitting = false
                            Toast.makeText(this@TakeExamActivity, "Submit failed: ${state.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun startExam(exam: Exam) {
        binding.tvExamTitle.text = exam.title
        questions = exam.questions
        setupPager()
        binding.tvQuestionCounter.text = "Question 1 of ${questions.size}"
        startTimer(exam.timeLimitMinutes)
        examStarted = true
        setupMonitoring()
    }

    // ── Question pager ───────────────────────────────────────────────────────

    private fun setupPager() {
        val adapter = QuestionPagerAdapter(
            questions = questions,
            getAnswer = { qId -> viewModel.answers.value[qId] },
            onAnswerSelected = { position, question, answer ->
                viewModel.setAnswer(question.id, answer)
                if (position < questions.size - 1) {
                    binding.viewPagerQuestions.postDelayed({
                        binding.viewPagerQuestions.setCurrentItem(position + 1, true)
                    }, 250)
                }
            }
        )
        binding.viewPagerQuestions.adapter = adapter
        binding.viewPagerQuestions.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.tvQuestionCounter.text = "Question ${position + 1} of ${questions.size}"
            }
        })
    }

    // ── Monitoring setup ─────────────────────────────────────────────────────

    private fun setupMonitoring() {
        monitoringEnabled = intent.getBooleanExtra("monitoringEnabled", false)
        if (!monitoringEnabled) {
            binding.cameraPreviewContainer.visibility = View.GONE
            binding.tvGazeStatus.visibility = View.GONE
            return
        }

        binding.cameraPreviewContainer.visibility = View.VISIBLE
        calibrationManager = CalibrationManager(this)
        val baselineOpenness = intent.getFloatExtra("baselineOpenness", 0.30f)
        blinkDetector.setBaseline(baselineOpenness)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Was "models/face_landmarker.task" — see class doc comment. Fixed to match
        // the actual asset path (also used by CalibrationActivity).
        val baseOptions = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> onGazeResult(result) }
            .setErrorListener { }
            .build()

        faceLandmarker = try {
            FaceLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            monitoringEnabled = false
            binding.cameraPreviewContainer.visibility = View.GONE
            binding.tvGazeStatus.visibility = View.GONE
            null
        }

        if (faceLandmarker != null) {
            startMonitoringCamera()
        }
    }

    private fun startMonitoringCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewViewExam.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { imageProxy -> detectFaceForMonitoring(imageProxy) } }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectFaceForMonitoring(imageProxy: ImageProxy) {
        if (isClosing) {
            imageProxy.close()
            return
        }
        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
            val corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Re-check right before the native call — isClosing may have
            // flipped while toBitmap()/createBitmap() were running.
            if (!isClosing) {
                faceLandmarker?.detectAsync(BitmapImageBuilder(corrected).build(), SystemClock.uptimeMillis())
            }
        } catch (e: Exception) {
        } finally {
            imageProxy.close()
        }
    }

    private fun onGazeResult(result: FaceLandmarkerResult) {
        if (isSubmitting) return
        val landmarks = result.faceLandmarks().firstOrNull()

        if (landmarks == null) {
            registerFaceless()
            return
        }
        registerFacePresent()

        val gaze = GazeCalculator.calculate(landmarks) ?: return

        val eyeState = blinkDetector.update(gaze.eyeOpenness)
        val isLidTransitioning = eyeState == EyeState.BLINKING || eyeState == EyeState.LID_TRANSITIONING

        val status = calibrationManager.checkGazeStatus(
            gaze.irisX, gaze.irisY, gaze.eyeOpenness, gaze.upperLidDropRatio, gaze.headPose,
            isLidTransitioning = isLidTransitioning
        )

        val alreadyLookingDown = status == GazeStatus.WARNING_DOWN ||
                status == GazeStatus.OFF_SCREEN_DOWN

        if (isLidTransitioning && !alreadyLookingDown) {
            return
        }

        when (status) {
            GazeStatus.ON_SCREEN,
            GazeStatus.WARNING_LEFT,
            GazeStatus.WARNING_RIGHT,
            GazeStatus.WARNING_UP,
            GazeStatus.WARNING_DOWN -> {
                updateGazeBanner(GazeBannerState.OK)
                registerGazeOnScreen()
            }
            GazeStatus.OFF_SCREEN_LEFT,
            GazeStatus.OFF_SCREEN_RIGHT,
            GazeStatus.OFF_SCREEN_UP,
            GazeStatus.OFF_SCREEN_DOWN -> registerGazeAway("LOOK_AWAY", "Looking away")
            GazeStatus.UNKNOWN -> { /* calibration not ready */ }
        }
    }

    // ── No-face grace period (3s), mirrors the look-away grace logic ────────

    private fun registerFaceless() {
        val now = System.currentTimeMillis()

        if (!isCurrentlyFaceless) {
            isCurrentlyFaceless = true
            facelessStartTime = now
            facelessViolationFired = false
        }

        val facelessDuration = now - facelessStartTime

        if (facelessDuration >= facelessViolationThresholdMs) {
            updateGazeBanner(GazeBannerState.NO_FACE, "Face not detected")
            if (!facelessViolationFired) {
                facelessViolationFired = true
                logGazeEvent("NO_FACE", isViolation = true)
            }
        } else {
            val remainingSec = (facelessViolationThresholdMs - facelessDuration) / 1000f
            updateGazeBanner(
                GazeBannerState.WARNING,
                "Face not visible — violation in ${"%.1f".format(remainingSec)}s"
            )
        }
    }

    private fun registerFacePresent() {
        isCurrentlyFaceless = false
        facelessViolationFired = false
    }

    private fun registerGazeAway(violationType: String, label: String) {
        val now = System.currentTimeMillis()

        if (!isCurrentlyAway) {
            isCurrentlyAway = true
            awayStartTime = now
            awayViolationFired = false
        }

        val awayDuration = now - awayStartTime

        if (awayDuration >= awayViolationThresholdMs) {
            updateGazeBanner(GazeBannerState.VIOLATION, label)
            if (!awayViolationFired) {
                awayViolationFired = true
                logGazeEvent(violationType, isViolation = true)
            }
        } else {
            val remainingSec = (awayViolationThresholdMs - awayDuration) / 1000f
            updateGazeBanner(GazeBannerState.WARNING, "$label — violation in ${"%.1f".format(remainingSec)}s")
        }
    }

    private fun registerGazeOnScreen() {
        if (isCurrentlyAway) {
            val awayDuration = System.currentTimeMillis() - awayStartTime
            if (!awayViolationFired && awayDuration >= minGlanceDurationMs) {
                recordShortGlance()
            }
            isCurrentlyAway = false
            awayViolationFired = false
        }
    }

    private fun recordShortGlance() {
        val now = System.currentTimeMillis()
        shortGlanceTimestamps.add(now)
        shortGlanceTimestamps.removeAll { now - it > rapidGlanceWindowMs }

        if (shortGlanceTimestamps.size >= rapidGlanceMaxCount) {
            shortGlanceTimestamps.clear()
            logGazeEvent("REPEATED_LOOK_AWAY", isViolation = true)
            updateGazeBanner(GazeBannerState.VIOLATION, "Repeated look-away pattern detected")
        }
    }

    private fun updateGazeBanner(state: GazeBannerState, message: String? = null) {
        val now = System.currentTimeMillis()

        // If we're still inside the hold window from a prior violation/no-face
        // banner, ignore attempts to downgrade to OK/WARNING.
        if ((state == GazeBannerState.OK || state == GazeBannerState.WARNING) && now < bannerHoldUntil) {
            return
        }

        // Any violation or no-face banner refreshes (and extends) the hold —
        // so a sustained violation stays fully visible, and a one-off flag
        // like "repeated look-away" still gets its full 2s on screen even
        // though the underlying state clears immediately after.
        if (state == GazeBannerState.VIOLATION || state == GazeBannerState.NO_FACE) {
            bannerHoldUntil = now + bannerHoldMs
        }

        runOnUiThread {
            when (state) {
                GazeBannerState.OK -> {
                    binding.tvGazeStatus.visibility = View.VISIBLE
                    binding.tvGazeStatus.text = "Looking at screen"
                    binding.tvGazeStatus.setBackgroundResource(R.drawable.gaze_status_ok)
                    binding.screenBorderOverlay.visibility = View.GONE
                    binding.gazeIndicatorOverlay.hide()
                }
                GazeBannerState.WARNING -> {
                    binding.tvGazeStatus.visibility = View.VISIBLE
                    binding.tvGazeStatus.text = "${message ?: "Near edge"}"
                    binding.tvGazeStatus.setBackgroundResource(R.drawable.gaze_status_warning)
                    binding.screenBorderOverlay.setBackgroundResource(R.drawable.screen_border_warning)
                    binding.screenBorderOverlay.visibility = View.VISIBLE
                    binding.gazeIndicatorOverlay.show()
                }
                GazeBannerState.VIOLATION -> {
                    binding.tvGazeStatus.visibility = View.VISIBLE
                    binding.tvGazeStatus.text = "${message ?: "Violation detected"}"
                    binding.tvGazeStatus.setBackgroundResource(R.drawable.gaze_status_violation)
                    binding.screenBorderOverlay.setBackgroundResource(R.drawable.screen_border_violation)
                    binding.screenBorderOverlay.visibility = View.VISIBLE
                    binding.gazeIndicatorOverlay.show()
                }
                GazeBannerState.NO_FACE -> {
                    binding.tvGazeStatus.visibility = View.VISIBLE
                    binding.tvGazeStatus.text = "${message ?: "Face not detected"}"
                    binding.tvGazeStatus.setBackgroundResource(R.drawable.gaze_status_violation)
                    binding.screenBorderOverlay.setBackgroundResource(R.drawable.screen_border_violation)
                    binding.screenBorderOverlay.visibility = View.VISIBLE
                    binding.gazeIndicatorOverlay.show()
                }
            }
        }
    }

    private fun logGazeEvent(type: String, isViolation: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastGazeViolationTime < gazeViolationCooldownMs) return
        lastGazeViolationTime = now

        violationLog.add(Violation(type = type, timestamp = now))
        if (isViolation) {
            runOnUiThread { viewModel.recordViolation() }
        }
    }

    private fun stopMonitoring() {
        if (!monitoringEnabled) return
        isClosing = true
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
            try {
                cameraExecutor.awaitTermination(300, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
            }
        }
        faceLandmarker?.close()
    }

    // ── App focus violation tracking ─────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        if (!examStarted || isSubmitting) return
        isPaused = true
        val runnable = Runnable {
            if (isPaused && !isSubmitting) {
                viewModel.recordViolation()
                violationLog.add(Violation(type = "APP_EXIT", timestamp = System.currentTimeMillis()))
            }
        }
        violationRunnable = runnable
        violationHandler.postDelayed(runnable, violationGraceMs)
    }

    override fun onResume() {
        super.onResume()
        violationRunnable?.let { violationHandler.removeCallbacks(it) }
        violationRunnable = null
        isPaused = false
    }

    private fun startTimer(minutes: Int) {
        val millis = minutes * 60 * 1000L
        countDownTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val m = millisUntilFinished / 1000 / 60
                val s = (millisUntilFinished / 1000) % 60
                binding.tvTimer.text = String.format("%02d:%02d", m, s)
                if (millisUntilFinished < 60_000) {
                    binding.tvTimer.setTextColor(Color.parseColor("#FF5252"))
                }
            }
            override fun onFinish() {
                binding.tvTimer.text = "00:00"
                Toast.makeText(this@TakeExamActivity, "Time's up! Submitting…", Toast.LENGTH_SHORT).show()
                submitExam()
            }
        }.start()
    }

    private fun confirmSubmit() {
        val answered = viewModel.answers.value.size
        val total = questions.size
        val unanswered = total - answered
        val msg = if (unanswered > 0)
            "You have $unanswered unanswered question(s).\nSubmit anyway?"
        else
            "All $total questions answered.\nReady to submit?"

        AlertDialog.Builder(this)
            .setTitle("Submit Exam")
            .setMessage(msg)
            .setPositiveButton("Submit") { _, _ -> submitExam() }
            .setNegativeButton("Review", null)
            .show()
    }

    private fun submitExam() {
        if (isSubmitting) return
        isSubmitting = true
        countDownTimer?.cancel()
        violationRunnable?.let { violationHandler.removeCallbacks(it) }
        stopMonitoring()
        binding.progressBar.visibility = View.VISIBLE
        binding.btnSubmit.isEnabled = false
        viewModel.submitExam(violationLog, monitoringEnabled)
    }

    override fun onDestroy() {
        super.onDestroy()
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        countDownTimer?.cancel()
        violationHandler.removeCallbacksAndMessages(null)
        stopMonitoring()
    }
}