package com.example.bantayex.features.student.examrules

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bantayex.core.util.UiState
import com.example.bantayex.databinding.ActivityExamRulesBinding
import com.example.bantayex.features.student.calibration.CalibrationActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ExamRulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamRulesBinding
    private val viewModel: ExamRulesViewModel by viewModels()

    private lateinit var examId: String
    private lateinit var examCode: String
    private lateinit var studentName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        examId = intent.getStringExtra("examId") ?: ""
        examCode = intent.getStringExtra("examCode") ?: ""
        studentName = intent.getStringExtra("studentName") ?: ""

        binding.btnContinue.setOnClickListener {
            viewModel.checkScheduleAndProceed(examCode)
        }

        binding.btnBack.setOnClickListener { finish() }

        observeProceedState()
    }

    private fun observeProceedState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.proceedState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.btnContinue.isEnabled = false
                        is UiState.Success -> {
                            val intent = Intent(this@ExamRulesActivity, CalibrationActivity::class.java)
                            intent.putExtra("examId", examId)
                            intent.putExtra("examCode", examCode)
                            intent.putExtra("studentName", studentName)
                            startActivity(intent)
                            finish()
                        }
                        is UiState.Error -> {
                            binding.btnContinue.isEnabled = true
                            Toast.makeText(this@ExamRulesActivity, state.message, Toast.LENGTH_LONG).show()
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }
}