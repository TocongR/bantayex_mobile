package com.example.bantayex.features.professor.createexam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.session.SessionManager
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.model.Question
import com.example.bantayex.data.repository.ExamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything in the ~20-line validation block that used to live inside CreateExamActivity
 * (title required, code required, schedule required, end-after-start, each question needs
 * text/2+ options/a correct answer) now lives here. That's the single biggest structural
 * fix in this step — validation is business logic, and business logic belongs in the
 * ViewModel, not the Activity, regardless of how "just a few if-checks" it looks like.
 */
@HiltViewModel
class CreateExamViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _loadState = MutableStateFlow<UiState<Exam>?>(null)
    val loadState: StateFlow<UiState<Exam>?> = _loadState.asStateFlow()

    private val _saveState = MutableStateFlow<UiState<Unit>?>(null)
    val saveState: StateFlow<UiState<Unit>?> = _saveState.asStateFlow()

    /** Set once an existing exam has loaded. Null means "creating a new exam." The
     *  Activity reads this (via loadState's Success) instead of tracking its own
     *  separate "am I editing?" boolean. */
    var existingExam: Exam? = null
        private set

    fun loadExam(examId: String) {
        viewModelScope.launch {
            _loadState.value = UiState.Loading
            _loadState.value = when (val result = examRepository.getExamById(examId)) {
                is Result.Success -> {
                    existingExam = result.data
                    UiState.Success(result.data)
                }
                is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't load exam")
            }
        }
    }

    fun saveExam(
        title: String,
        description: String,
        timeLimitRaw: String,
        passingScoreRaw: String,
        examCode: String,
        availableDate: String,
        availableFrom: String,
        availableTo: String,
        questions: List<Question>
    ) {
        if (title.isBlank()) return failSave("Title is required")
        if (examCode.isBlank()) return failSave("Exam code is required")
        if (availableDate.isBlank()) return failSave("Please pick an availability date")
        if (availableFrom.isBlank()) return failSave("Please pick a start time")
        if (availableTo.isBlank()) return failSave("Please pick an end time")
        if (availableFrom >= availableTo) return failSave("End time must be after start time")
        if (questions.isEmpty()) return failSave("Add at least one question")

        questions.forEachIndexed { index, q ->
            when {
                q.text.isBlank() -> return failSave("Q${index + 1} text is empty")
                q.options.size < 2 -> return failSave("Q${index + 1} needs at least 2 options")
                q.options.any { it.isBlank() } -> return failSave("Q${index + 1} has an empty option")
                q.correctAnswer !in q.options.indices ->
                    return failSave("Q${index + 1} needs a correct answer selected")
            }
        }

        val timeLimit = timeLimitRaw.toIntOrNull() ?: 30
        val passingScore = passingScoreRaw.toIntOrNull() ?: 1
        val professorId = sessionManager.currentUserId
            ?: return failSave("Session expired. Please log in again.")
        val professorName = resolveProfessorName()

        viewModelScope.launch {
            _saveState.value = UiState.Loading

            val current = existingExam
            val codeTaken = examRepository.isExamCodeTaken(examCode, excludeExamId = current?.id ?: "")
            if (codeTaken) {
                _saveState.value = UiState.Error("Exam code already in use.")
                return@launch
            }

            _saveState.value = if (current != null) {
                val updated = current.copy(
                    title = title,
                    description = description,
                    timeLimitMinutes = timeLimit,
                    passingScore = passingScore,
                    examCode = examCode.uppercase(),
                    professorName = professorName,
                    availableDate = availableDate,
                    availableFrom = availableFrom,
                    availableTo = availableTo,
                    questions = questions
                )
                when (val result = examRepository.updateExam(updated)) {
                    is Result.Success -> UiState.Success(Unit)
                    is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't save changes")
                }
            } else {
                val exam = Exam(
                    title = title,
                    description = description,
                    timeLimitMinutes = timeLimit,
                    passingScore = passingScore,
                    examCode = examCode.uppercase(),
                    professorId = professorId,
                    professorName = professorName,
                    availableDate = availableDate,
                    availableFrom = availableFrom,
                    availableTo = availableTo,
                    questions = questions
                )
                when (val result = examRepository.createExam(exam)) {
                    is Result.Success -> UiState.Success(Unit)
                    is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't create exam")
                }
            }
        }
    }

    /**
     * No signup flow in this app ever collects a professor's display name — accounts
     * only have an email/password via Firebase Auth. Uses displayName if it's ever been
     * set (e.g. manually via the Firebase console), otherwise falls back to the part of
     * the email before '@' so this never renders blank on the student's exam details.
     */
    private fun resolveProfessorName(): String {
        val user = sessionManager.currentUser.value ?: return ""
        val displayName = user.displayName
        if (!displayName.isNullOrBlank()) return displayName
        return user.email?.substringBefore("@") ?: ""
    }

    private fun failSave(message: String) {
        _saveState.value = UiState.Error(message)
    }
}