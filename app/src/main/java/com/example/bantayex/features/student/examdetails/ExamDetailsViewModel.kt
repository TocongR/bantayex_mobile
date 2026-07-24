package com.example.bantayex.features.student.examdetails

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
class ExamDetailsViewModel @Inject constructor(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<Exam>?>(null)
    val uiState: StateFlow<UiState<Exam>?> = _uiState.asStateFlow()

    fun loadExam(examCode: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            _uiState.value = when (val result = examRepository.getExamByCode(examCode)) {
                is Result.Success -> {
                    val exam = result.data
                    when (ExamScheduleHelper.getStatus(exam)) {
                        ExamScheduleHelper.ExamStatus.UPCOMING ->
                            UiState.Error("This exam hasn't started yet.\nCome back at ${exam.availableFrom}.")
                        ExamScheduleHelper.ExamStatus.EXPIRED ->
                            UiState.Error("This exam has already ended.")
                        ExamScheduleHelper.ExamStatus.ACTIVE ->
                            UiState.Success(exam)
                    }
                }
                is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't load exam")
            }
        }
    }
}