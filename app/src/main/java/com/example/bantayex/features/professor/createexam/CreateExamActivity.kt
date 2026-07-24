package com.example.bantayex.features.professor.createexam

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.bantayex.core.util.CodeGenerator
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.ExamScheduleHelper
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.model.Question
import com.example.bantayex.databinding.ActivityCreateExamBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

/**
 * Compare this to the old CreateExamActivity: the ~20-line validation block that used to
 * live in saveExam() here is GONE. It moved to CreateExamViewModel — this Activity's only
 * job now is reading fields, showing pickers, and rendering whatever state the ViewModel
 * hands back.
 */
@AndroidEntryPoint
class CreateExamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateExamBinding
    private val viewModel: CreateExamViewModel by viewModels()
    private val questions = mutableListOf<Question>()
    private lateinit var questionAdapter: QuestionCreateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()

        val editExamId = intent.getStringExtra("editExamId")
        if (editExamId != null) {
            binding.progressBar.visibility = View.VISIBLE
            viewModel.loadExam(editExamId)
        } else {
            generateCode()
            binding.etAvailableDate.setText(ExamScheduleHelper.todayDate())
        }

        setupDateTimePickers()

        binding.btnGenerateCode.setOnClickListener { generateCode() }

        binding.btnAddQuestion.setOnClickListener {
            hideKeyboard()
            questions.add(Question(id = UUID.randomUUID().toString()))
            questionAdapter.notifyItemInserted(questions.size - 1)
            binding.nestedScrollView.post {
                binding.nestedScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        binding.btnSaveExam.setOnClickListener { onSaveClicked() }
        binding.btnBack.setOnClickListener { finish() }

        observeLoadState()
        observeSaveState()
    }

    private fun onSaveClicked() {
        // Flush visible ViewHolder text into the in-memory list before handing it
        // to the ViewModel — options/correctAnswer are already live-synced by the
        // adapter's listeners, only question text needs this explicit flush.
        for (i in questions.indices) {
            questionAdapter.syncQuestion(i, binding.rvQuestions)
        }

        viewModel.saveExam(
            title = binding.etTitle.text.toString().trim(),
            description = binding.etDescription.text.toString().trim(),
            timeLimitRaw = binding.etTimeLimit.text.toString().trim(),
            passingScoreRaw = binding.etPassingScore.text.toString().trim(),
            examCode = binding.etExamCode.text.toString().trim(),
            availableDate = binding.etAvailableDate.text.toString().trim(),
            availableFrom = binding.etAvailableFrom.text.toString().trim(),
            availableTo = binding.etAvailableTo.text.toString().trim(),
            questions = questions.toList()
        )
    }

    private fun observeLoadState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> binding.progressBar.visibility = View.VISIBLE
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            populateFields(state.data)
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(this@CreateExamActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun observeSaveState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saveState.collect { state ->
                    when (state) {
                        null -> Unit
                        is UiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.btnSaveExam.isEnabled = false
                        }
                        is UiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            val msg = if (viewModel.existingExam != null) "Exam updated!" else "Exam created!"
                            Toast.makeText(this@CreateExamActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        }
                        is UiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            binding.btnSaveExam.isEnabled = true
                            Toast.makeText(this@CreateExamActivity, state.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun populateFields(exam: Exam) {
        binding.etTitle.setText(exam.title)
        binding.etDescription.setText(exam.description)
        binding.etTimeLimit.setText(exam.timeLimitMinutes.toString())
        binding.etPassingScore.setText(exam.passingScore.toString())
        binding.etExamCode.setText(exam.examCode)
        binding.etAvailableDate.setText(exam.availableDate)
        binding.etAvailableFrom.setText(exam.availableFrom)
        binding.etAvailableTo.setText(exam.availableTo)

        questions.clear()
        questions.addAll(exam.questions)
        questionAdapter.notifyDataSetChanged()
    }

    private fun setupDateTimePickers() {
        binding.etAvailableDate.setOnClickListener { showDatePicker() }
        binding.etAvailableFrom.setOnClickListener { showTimePicker(isFrom = true) }
        binding.etAvailableTo.setOnClickListener { showTimePicker(isFrom = false) }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                binding.etAvailableDate.setText(String.format("%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showTimePicker(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val time = String.format("%02d:%02d", hour, minute)
                if (isFrom) binding.etAvailableFrom.setText(time) else binding.etAvailableTo.setText(time)
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    // Was a hand-copied duplicate of CodeGenerator's logic in the old Activity.
    // Now calls the actual utility.
    private fun generateCode() {
        binding.etExamCode.setText(CodeGenerator.generate())
    }

    private fun setupRecyclerView() {
        questionAdapter = QuestionCreateAdapter(
            questions = questions,
            onDelete = { position ->
                questions.removeAt(position)
                questionAdapter.notifyItemRemoved(position)
                questionAdapter.notifyItemRangeChanged(position, questions.size)
            }
        )
        binding.rvQuestions.apply {
            layoutManager = LinearLayoutManager(this@CreateExamActivity)
            adapter = questionAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
}