package com.example.bantayex.features.student.examdetails

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.Exam
import com.example.bantayex.databinding.ActivityExamDetailsBinding
import com.example.bantayex.features.student.examrules.ExamRulesActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ExamDetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamDetailsBinding
    private val viewModel: ExamDetailsViewModel by viewModels()

    // Held so btnStartExam can read it without touching viewModel.uiState.value directly
    // from a click listener (StateFlow's .value is fine to read, but going through a
    // dedicated field keeps the "what exam is loaded" concept in one obvious place).
    private var loadedExam: Exam? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val examCode = intent.getStringExtra("examCode") ?: ""

        binding.progressBar.visibility = View.VISIBLE
        viewModel.loadExam(examCode)

        binding.btnStartExam.setOnClickListener {
            val studentName = binding.etStudentName.text.toString().trim()
            if (studentName.isEmpty()) {
                Toast.makeText(this, "Please enter your full name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val exam = loadedExam ?: return@setOnClickListener
            val intent = Intent(this, ExamRulesActivity::class.java)
            intent.putExtra("examId", exam.id)
            intent.putExtra("examCode", examCode)
            intent.putExtra("studentName", studentName)
            startActivity(intent)
        }

        binding.btnBack.setOnClickListener { finish() }

        observeUiState()
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            loadedExam = state.data
                            renderExam(state.data)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@ExamDetailsActivity, state.message, Toast.LENGTH_LONG).show()
                            finish() // student cannot proceed past an invalid/unavailable exam
                        }
                    }
                }
            }
        }
    }

    private fun renderExam(exam: Exam) {
        binding.tvTitle.text = exam.title
        binding.tvDescription.text = exam.description.ifEmpty { "No description provided." }

        // Exams created before the professorName field existed will read as "" —
        // hide the row entirely rather than showing "Professor: " with nothing after it.
        if (exam.professorName.isNotBlank()) {
            binding.tvProfessorName.text = "Professor: ${exam.professorName}"
            binding.tvProfessorName.visibility = View.VISIBLE
        } else {
            binding.tvProfessorName.visibility = View.GONE
        }

        binding.tvTimeLimit.text = "Time Limit: ${exam.timeLimitMinutes} minutes"
        binding.tvQuestionCount.text = "Questions: ${exam.questions.size}"
        binding.tvPassingScore.text = "Passing Score: ${exam.passingScore} of ${exam.questions.size} correct"
        binding.cardDetails.visibility = View.VISIBLE
        binding.cardName.visibility = View.VISIBLE
        binding.btnStartExam.visibility = View.VISIBLE
    }
}