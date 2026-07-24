package com.example.bantayex.features.student.takeexam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.model.ExamResult
import com.example.bantayex.data.model.Violation
import com.example.bantayex.data.repository.ExamRepository
import com.example.bantayex.data.repository.ResultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * No schedule re-check here on purpose — eligibility (ACTIVE/UPCOMING/EXPIRED) is
 * validated once in ExamRulesViewModel, before the exam starts. See the comment there
 * for why re-checking a live clock here would unfairly bounce students.
 *
 * `violations`/`recordViolation()` and the violationLog/monitoringEnabled parameters on
 * submitExam() are kept here even though nothing calls them yet — Step 6 (proctoring)
 * wires them up from TakeExamActivity without needing to touch this ViewModel again.
 * Until then they just stay at their defaults (0 violations, empty log, monitoring off).
 */
@HiltViewModel
class TakeExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val resultRepository: ResultRepository
) : ViewModel() {

    private val _examState = MutableStateFlow<UiState<Exam>?>(null)
    val examState: StateFlow<UiState<Exam>?> = _examState.asStateFlow()

    private val _answers = MutableStateFlow<Map<String, String>>(emptyMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    private val _violations = MutableStateFlow(0)
    val violations: StateFlow<Int> = _violations.asStateFlow()

    private val _submitState = MutableStateFlow<UiState<ExamResult>?>(null)
    val submitState: StateFlow<UiState<ExamResult>?> = _submitState.asStateFlow()

    private var studentName = ""
    private var examCode = ""
    private var loadedExam: Exam? = null

    fun loadExam(code: String, name: String) {
        examCode = code
        studentName = name
        viewModelScope.launch {
            _examState.value = UiState.Loading
            _examState.value = when (val result = examRepository.getExamByCode(code)) {
                is Result.Success -> {
                    loadedExam = result.data
                    UiState.Success(result.data)
                }
                is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't load exam")
            }
        }
    }

    fun setAnswer(questionId: String, answer: String) {
        _answers.value = _answers.value.toMutableMap().apply { put(questionId, answer) }
    }

    fun recordViolation() {
        _violations.value += 1
    }

    fun submitExam(violationLog: List<Violation> = emptyList(), monitoringEnabled: Boolean = false) {
        val exam = loadedExam ?: return
        val currentAnswers = _answers.value

        var score = 0
        for (question in exam.questions) {
            val selectedIndex = currentAnswers[question.id]?.toIntOrNull()
            if (selectedIndex != null && selectedIndex == question.correctAnswer) score++
        }

        val totalQuestions = exam.questions.size
        val passed = score >= exam.passingScore // raw score comparison, not percentage

        val result = ExamResult(
            examCode = examCode,
            examId = exam.id,
            examTitle = exam.title,
            studentName = studentName,
            score = score,
            totalQuestions = totalQuestions,
            answers = currentAnswers,
            passed = passed,
            violations = _violations.value,
            violationLog = violationLog,
            gazeMonitoringEnabled = monitoringEnabled,
            autoSubmitted = false,
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            _submitState.value = UiState.Loading
            _submitState.value = when (val saveResult = resultRepository.saveResult(result)) {
                is Result.Success -> UiState.Success(result)
                is Result.Error -> UiState.Error(saveResult.exception.message ?: "Submit failed")
            }
        }
    }
}