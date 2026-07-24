package com.example.bantayex.core.util

/**
 * Wraps what a screen should render, exposed by every ViewModel as a StateFlow<UiState<T>>.
 * Used ONLY at the ViewModel -> Activity/Fragment boundary (never inside a Repository).
 *
 * One consistent shape across every screen (Login, ExamList, TakeExam, etc.) means every
 * Activity's `collect` block looks the same, which is the whole point of not letting each
 * screen invent its own Loading/error convention.
 */
sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}