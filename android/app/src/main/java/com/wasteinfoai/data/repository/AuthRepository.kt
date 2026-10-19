package com.wasteinfoai.data.repository

import com.wasteinfoai.core.common.Resource
import com.wasteinfoai.data.remote.ApiService
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.data.remote.dto.LoginRequest
import com.wasteinfoai.data.remote.dto.RegisterRequest
import com.wasteinfoai.data.remote.dto.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val apiService: ApiService,
    private val sessionManager: SessionManager
) {

    suspend fun login(email: String, password: String?): Resource<UserResponse> = withContext(Dispatchers.IO) {
        try {
            val tokenRes = apiService.login(LoginRequest(email = email, password = password))
            if (!tokenRes.isSuccessful || tokenRes.body() == null) {
                return@withContext Resource.Error("Login failed: ${tokenRes.message()} (${tokenRes.code()})")
            }

            val token = tokenRes.body()!!.token
            // Temporarily store token so getProfile can use it
            sessionManager.saveSession(token, UserResponse("temp", email, "RESIDENT", null, email.substringBefore("@"), true))

            val profileRes = apiService.getProfile()
            if (profileRes.isSuccessful && profileRes.body() != null) {
                val profile = profileRes.body()!!
                sessionManager.saveSession(token, profile)
                Resource.Success(profile)
            } else {
                // If profile lookup fails, keep default profile
                val fallbackUser = UserResponse("user_local", email, "RESIDENT", null, email.substringBefore("@"), true)
                sessionManager.saveSession(token, fallbackUser)
                Resource.Success(fallbackUser)
            }
        } catch (e: Exception) {
            Resource.Error("Network error: ${e.localizedMessage ?: "Could not connect to server"}", e)
        }
    }

    suspend fun register(
        email: String,
        password: String?,
        fullName: String? = null
    ): Resource<UserResponse> = withContext(Dispatchers.IO) {
        try {
            val regRes = apiService.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password?.ifBlank { null },
                    fullName = fullName?.trim()?.ifBlank { null },
                    displayName = fullName?.trim()?.split(" ")?.firstOrNull() ?: email.substringBefore("@")
                )
            )
            if (regRes.isSuccessful && regRes.body() != null) {
                Resource.Success(regRes.body()!!)
            } else if (regRes.code() == 409) {
                Resource.Error("An account with this email already exists. Please log in.")
            } else {
                Resource.Error("Registration failed: ${regRes.message()} (${regRes.code()})")
            }
        } catch (e: Exception) {
            Resource.Error("Network error: ${e.localizedMessage ?: "Could not connect to server"}", e)
        }
    }

    fun logout() {
        sessionManager.clearSession()
    }
}
