package com.example.bantayex.features.student.examrules

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

/**
 * The schedule re-check used to live directly inside ExamRulesActivity as a
 * lifecycleScope.launch calling ExamRepository() by hand. That's business logic
 * (a Firestore call + a scheduling decision), so it moved here — same rule as every
 * other screen. The Activity's job is just: tap Continue, show the result.
 *
 * The comment explaining WHY this check exists (schedule is validated once here, not
 * re-checked after calibration/mid-flow) is preserved below since it documents a real
 * decision, not just what the code does.
 */
@HiltViewModel
class ExamRulesViewModel @Inject constructor(
    private val examRepository: ExamRepository
) : ViewModel() {

    private val _proceedState = MutableStateFlow<UiState<Exam>?>(null)
    val proceedState: StateFlow<UiState<Exam>?> = _proceedState.asStateFlow()

    /**
     * Validate the exam's schedule window ONCE, here, before entering the exam.
     * Calibration (in Step 6) and any retries can take several minutes, and re-running
     * a live clock comparison afterward meant students could get bounced back to
     * ExamDetails simply because time passed while they were mid-calibration, even
     * though they were already let in fairly at this point. TakeExamViewModel does not
     * re-check schedule — this is the only gate.
     */
    fun checkScheduleAndProceed(examCode: String) {
        viewModelScope.launch {
            _proceedState.value = UiState.Loading
            _proceedState.value = when (val result = examRepository.getExamByCode(examCode)) {
                is Result.Success -> {
                    val exam = result.data
                    when (ExamScheduleHelper.getStatus(exam)) {
                        ExamScheduleHelper.ExamStatus.ACTIVE -> UiState.Success(exam)
                        ExamScheduleHelper.ExamStatus.UPCOMING -> UiState.Error("Exam not available yet.")
                        ExamScheduleHelper.ExamStatus.EXPIRED -> UiState.Error("This exam has already ended.")
                    }
                }
                is Result.Error -> UiState.Error(result.exception.message ?: "Failed to load exam.")
            }
        }
    }

    fun resetState() {
        _proceedState.value = null
    }
}