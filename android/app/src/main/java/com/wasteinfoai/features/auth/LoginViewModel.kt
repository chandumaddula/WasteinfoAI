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
    val serverUrl: String = "http://10.0.2.2:8000/",
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AuthUiState(
            email = sessionManager.emailState.value,
            role = sessionManager.getRole(),
            category = sessionManager.getCategory(),
            serverUrl = sessionManager.getServerUrl(),
            isLoggedIn = sessionManager.isLoggedIn
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun updateServerUrl(url: String) {
        sessionManager.setServerUrl(url)
        _uiState.value = _uiState.value.copy(serverUrl = sessionManager.getServerUrl())
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, successMessage = null)
    }

    private fun resolveDefaultRole(category: String): String = when (category.lowercase()) {
        "municipal worker", "municipal_worker" -> "SANITATION_WORKER"
        "industries", "industry" -> "INSTITUTION"
        "other" -> "REVIEWER"
        else -> "RESIDENT"
    }

    fun login(email: String, pass: String, category: String = "Households", onDone: (() -> Unit)? = null) {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email / Identifier is required")
            return
        }
        val defaultRole = resolveDefaultRole(category)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val res = authRepository.login(cleanEmail, pass.ifBlank { null })
            when (res) {
                is Resource.Success -> {
                    sessionManager.setCategory(category)
                    sessionManager.setDevRoleOverride(res.data.role.ifBlank { defaultRole })
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        email = res.data.email,
                        role = res.data.role.ifBlank { defaultRole },
                        category = category,
                        errorMessage = null,
                        successMessage = "Logged in successfully as ${res.data.role}"
                    )
                    onDone?.invoke()
                }
                is Resource.Error -> {
                    val isConnectionError = res.cause != null ||
                        res.message?.contains("connect", ignoreCase = true) == true ||
                        res.message?.contains("timeout", ignoreCase = true) == true ||
                        res.message?.contains("network", ignoreCase = true) == true ||
                        res.message?.contains("10.0.2.2", ignoreCase = true) == true

                    if (isConnectionError) {
                        // Offline / Standalone Fallback: authenticate locally
                        if (sessionManager.hasLocalAccount(cleanEmail)) {
                            if (!sessionManager.verifyLocalAccount(cleanEmail, pass)) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    errorMessage = "Invalid password for this account."
                                )
                                return@launch
                            }
                        }
                        sessionManager.saveLocalSession(cleanEmail, category, defaultRole)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            email = cleanEmail,
                            role = defaultRole,
                            category = category,
                            errorMessage = null,
                            successMessage = "Server unreachable. Running in Local Mode."
                        )
                        onDone?.invoke()
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                    }
                }
                else -> {}
            }
        }
    }

    fun register(
        fullName: String,
        email: String,
        pass: String,
        confirmPass: String,
        category: String = "Households",
        onSuccess: ((registeredEmail: String) -> Unit)? = null
    ) {
        val cleanName = fullName.trim()
        val cleanEmail = email.trim()

        if (cleanName.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Full Name is required for registration")
            return
        }
        if (cleanName.length < 2) {
            _uiState.value = _uiState.value.copy(errorMessage = "Full Name must be at least 2 characters")
            return
        }
        if (cleanEmail.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Email Address is required")
            return
        }
        val emailPattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        if (!emailPattern.matches(cleanEmail)) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a valid email address (e.g. name@domain.com)")
            return
        }
        if (pass.length < 6) {
            _uiState.value = _uiState.value.copy(errorMessage = "Password must be at least 6 characters")
            return
        }
        if (pass != confirmPass) {
            _uiState.value = _uiState.value.copy(errorMessage = "Passwords do not match. Please verify both password fields.")
            return
        }

        val defaultRole = resolveDefaultRole(category)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, successMessage = null)
            val res = authRepository.register(cleanEmail, pass.ifBlank { null }, cleanName)
            when (res) {
                is Resource.Success -> {
                    // Registration succeeded. Do NOT log in automatically.
                    // Switch to login mode so user enters their credentials.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        email = cleanEmail,
                        role = res.data.role.ifBlank { defaultRole },
                        category = category,
                        errorMessage = null,
                        successMessage = "Account created successfully! Please sign in with your credentials to open dashboard."
                    )
                    onSuccess?.invoke(cleanEmail)
                }
                is Resource.Error -> {
                    val isConnectionError = res.cause != null ||
                        res.message?.contains("connect", ignoreCase = true) == true ||
                        res.message?.contains("timeout", ignoreCase = true) == true ||
                        res.message?.contains("network", ignoreCase = true) == true ||
                        res.message?.contains("10.0.2.2", ignoreCase = true) == true

                    if (isConnectionError) {
                        // Offline registration fallback: save credentials locally
                        sessionManager.registerLocalAccount(cleanEmail, pass, cleanName, category, defaultRole)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isLoggedIn = false,
                            email = cleanEmail,
                            role = defaultRole,
                            category = category,
                            errorMessage = null,
                            successMessage = "Account registered locally (offline mode). Please sign in with your password."
                        )
                        onSuccess?.invoke(cleanEmail)
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = res.message)
                    }
                }
                else -> {}
            }
        }
    }

    fun register(email: String, pass: String, category: String = "Households", onDone: (() -> Unit)? = null) {
        register(
            fullName = email.substringBefore("@").replaceFirstChar { it.uppercase() },
            email = email,
            pass = pass,
            confirmPass = pass,
            category = category,
            onSuccess = { onDone?.invoke() }
        )
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
