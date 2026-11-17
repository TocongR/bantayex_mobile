package com.example.bantayex.features.professor.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.ExamResult
import com.example.bantayex.data.model.ViolationProof
import com.example.bantayex.data.repository.ProofRepository
import com.example.bantayex.data.repository.ResultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExamResultsViewModel @Inject constructor(
    private val resultRepository: ResultRepository,
    private val proofRepository: ProofRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<ExamResult>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<ExamResult>>> = _uiState.asStateFlow()

    fun loadResults(examCode: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            _uiState.value = when (val result = resultRepository.getResultsByExamCode(examCode)) {
                is Result.Success -> UiState.Success(result.data)
                is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't load results")
            }
        }
    }

    /** Evidence photos are only downloaded when the professor taps "View evidence". */
    suspend fun fetchProofs(ids: List<String>): Result<List<ViolationProof>> =
        proofRepository.getProofsByIds(ids)
}