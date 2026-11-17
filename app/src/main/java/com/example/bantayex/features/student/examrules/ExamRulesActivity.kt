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
import com.example.bantayex.features.student.setuptips.SetupTipsActivity
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

        // The consent gate lives on this screen because it sits right under the
        // privacy explanation and before the schedule check proceeds, so nobody can
        // reach the exam without acknowledging that proof photos may be saved.
        binding.cbConsent.setOnCheckedChangeListener { _, _ -> updateContinueButton() }
        updateContinueButton()

        observeProceedState()
    }

    private fun updateContinueButton() {
        binding.btnContinue.isEnabled = binding.cbConsent.isChecked
    }

    private fun observeProceedState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.proceedState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.btnContinue.isEnabled = false
                        is UiState.Success -> {
                            val intent = Intent(this@ExamRulesActivity, SetupTipsActivity::class.java)
                            intent.putExtra("examId", examId)
                            intent.putExtra("examCode", examCode)
                            intent.putExtra("studentName", studentName)
                            startActivity(intent)
                            finish()
                        }
                        is UiState.Error -> {
                            updateContinueButton()
                            Toast.makeText(this@ExamRulesActivity, state.message, Toast.LENGTH_LONG).show()
                            viewModel.resetState()
                        }
                    }
                }
            }
        }
    }
}