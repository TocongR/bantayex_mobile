package com.example.bantayex.features.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.bantayex.core.util.Result
import com.example.bantayex.core.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * loginState starts as `null` (not UiState.Loading) on purpose: there's nothing to show
 * before the user has tapped the button. If we defaulted to Loading, the Activity would
 * need extra logic to suppress a progress bar that flashes at screen-open for no reason.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _loginState = MutableStateFlow<UiState<Unit>?>(null)
    val loginState: StateFlow<UiState<Unit>?> = _loginState.asStateFlow()

    fun login(email: String, password: String) {
        val trimmedEmail = email.trim()
        val trimmedPassword = password.trim()

        if (trimmedEmail.isEmpty() || trimmedPassword.isEmpty()) {
            _loginState.value = UiState.Error("Enter email and password")
            return
        }

        viewModelScope.launch {
            _loginState.value = UiState.Loading
            _loginState.value = when (val result = authRepository.login(trimmedEmail, trimmedPassword)) {
                is Result.Success -> UiState.Success(Unit)
                is Result.Error -> UiState.Error(result.exception.message ?: "Login failed")
            }
        }
    }
}