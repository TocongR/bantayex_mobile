package com.example.bantayex.features.professor.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.session.SessionManager
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import com.example.bantayex.data.model.Exam
import com.example.bantayex.data.repository.ExamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfessorDashboardViewModel @Inject constructor(
    private val examRepository: ExamRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    // Unfiltered list kept in memory so search filtering doesn't need another Firestore call.
    private var fullList: List<Exam> = emptyList()

    private val _uiState = MutableStateFlow<UiState<List<Exam>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<Exam>>> = _uiState.asStateFlow()

    // One-off events (delete succeeded/failed) as a SharedFlow instead of LiveData —
    // this is the fix for a real bug class: LiveData replays its last value to new
    // observers, so "Exam deleted" could re-toast after a screen rotation. SharedFlow
    // with no replay doesn't have that problem.
    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun loadExams() {
        val professorId = sessionManager.currentUserId ?: return
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            _uiState.value = when (val result = examRepository.getExamsByProfessor(professorId)) {
                is Result.Success -> {
                    fullList = result.data
                    UiState.Success(result.data)
                }
                is Result.Error -> UiState.Error(result.exception.message ?: "Couldn't load exams")
            }
        }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            fullList
        } else {
            fullList.filter { it.title.lowercase().contains(q) || it.examCode.lowercase().contains(q) }
        }
        _uiState.value = UiState.Success(filtered)
    }

    fun deleteExam(examId: String) {
        viewModelScope.launch {
            when (examRepository.deleteExam(examId)) {
                is Result.Success -> {
                    _events.emit("Exam deleted")
                    loadExams()
                }
                is Result.Error -> _events.emit("Failed to delete exam")
            }
        }
    }

    fun logout() {
        sessionManager.logout()
    }
}