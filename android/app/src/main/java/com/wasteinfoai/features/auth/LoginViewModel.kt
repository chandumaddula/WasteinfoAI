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
    val category: String = "Households",
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
            category = sessionManager.getCategory(),
            isLoggedIn = sessionManager.isLoggedIn
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, pass: String, category: String = "Households", onDone: (() -> Unit)? = null) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email / Identifier is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.login(email, pass.ifBlank { null })
            when (res) {
                is Resource.Success -> {
                    sessionManager.setCategory(category)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        email = res.data.email,
                        role = res.data.role,
                        category = category,
                        successMessage = "Logged in successfully as ${res.data.role}"
                    )
                    onDone?.invoke()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> {}
            }
        }
    }

    fun register(email: String, pass: String, category: String = "Households", onDone: (() -> Unit)? = null) {
        if (email.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email / Identifier is required")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.register(email, pass.ifBlank { null })
            when (res) {
                is Resource.Success -> {
                    sessionManager.setCategory(category)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        email = res.data.email,
                        role = res.data.role,
                        category = category,
                        successMessage = "Account created and logged in!"
                    )
                    onDone?.invoke()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                }
                else -> {}
            }
        }
    }

    fun loginDemo(category: String, onDone: () -> Unit) {
        val (email, role) = when (category.lowercase()) {
            "municipal worker", "municipal_worker" -> "sanitation.worker@citygov.org" to "SANITATION_WORKER"
            "industries", "industry" -> "compliance@greenecoplant.com" to "INSTITUTION"
            "other" -> "lead.reviewer@wasteinfo.ai" to "REVIEWER"
            else -> "resident@wasteinfo.ai" to "RESIDENT"
        }
        val user = com.wasteinfoai.data.remote.dto.UserResponse(
            id = "demo_${category.lowercase().replace(" ", "_")}",
            email = email,
            role = role,
            fullName = "${category} User",
            displayName = "${category} User",
            isActive = true
        )
        sessionManager.saveSession(
            token = "demo_token_${System.currentTimeMillis()}",
            user = user,
            category = category
        )
        sessionManager.setDevRoleOverride(role)
        _uiState.value = _uiState.value.copy(
            isLoggedIn = true,
            email = email,
            role = role,
            category = category,
            errorMessage = null,
            successMessage = "Logged in as $category ($role)"
        )
        onDone()
    }

    fun switchRole(role: String) {
        sessionManager.setDevRoleOverride(role)
        _uiState.value = _uiState.value.copy(role = role)
    }

    fun switchCategory(category: String, role: String) {
        sessionManager.setCategory(category)
        sessionManager.setDevRoleOverride(role)
        _uiState.value = _uiState.value.copy(category = category, role = role)
    }

    fun logout() {
        authRepository.logout()
        _uiState.value = AuthUiState()
    }
}
