package com.example.bantayex.features.student.entercode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.ExamScheduleHelper
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.repository.ExamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EnterCodeViewModel @Inject constructor(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _codeState = MutableStateFlow<UiState<Exam>?>(null)
    val codeState: StateFlow<UiState<Exam>?> = _codeState.asStateFlow()

    fun validateCode(code: String) {
        if (code.length < 6) {
            _codeState.value = UiState.Error("Enter the full 6-character code")
            return
        }

        viewModelScope.launch {
            _codeState.value = UiState.Loading
            _codeState.value = when (val result = examRepository.getExamByCode(code)) {
                is Result.Success -> resolveScheduleState(result.data)
                is Result.Error -> UiState.Error(result.exception.message ?: "Exam not found.")
            }
        }
    }

    private fun resolveScheduleState(exam: Exam): UiState<Exam> {
        return when (ExamScheduleHelper.getStatus(exam)) {
            ExamScheduleHelper.ExamStatus.UPCOMING -> UiState.Error(
                "This exam is not available yet.\n" +
                        "Available on ${exam.availableDate} from ${exam.availableFrom} to ${exam.availableTo}"
            )
            ExamScheduleHelper.ExamStatus.EXPIRED -> UiState.Error("This exam has already ended.")
            ExamScheduleHelper.ExamStatus.ACTIVE -> UiState.Success(exam)
        }
    }

    /** Called from onResume so a student can retry after backing out of Exam Details. */
    fun resetState() {
        _codeState.value = null
    }
}