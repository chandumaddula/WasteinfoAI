package com.wasteinfoai.features.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val role: String = "RESIDENT",
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(
            email = sessionManager.emailState.value,
            role = sessionManager.getRole(),
            isLoggedIn = sessionManager.isLoggedIn
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, pass: String) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.login(email, pass.ifBlank { null })
            when (res) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        email = res.data.email,
                        role = res.data.role,
                        successMessage = "Logged in successfully as ${res.data.role}"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> {}
            }
        }
    }

    fun register(email: String, pass: String) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.register(email, pass.ifBlank { null })
            when (res) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        email = res.data.email,
                        role = res.data.role,
                        successMessage = "Account created and logged in!"
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> {}
            }
        }
    }

    fun switchRole(role: String) {
        sessionManager.setDevRoleOverride(role)
        _uiState.value = _uiState.value.copy(role = role)
    }

    fun logout() {
        authRepository.logout()
        _uiState.value = AuthUiState()
    }
}
