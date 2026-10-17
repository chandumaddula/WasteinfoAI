package com.wasteinfoai.data.remote.dto

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String? = null
)

data class TokenResponse(
    @SerializedName("token") val token: String,
    @SerializedName("expires_at") val expiresAt: String
)

data class UserResponse(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("role") val role: String,
    @SerializedName("full_name") val fullName: String?,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("is_active") val isActive: Boolean
)
