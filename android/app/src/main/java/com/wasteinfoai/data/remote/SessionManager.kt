package com.wasteinfoai.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.wasteinfoai.data.remote.dto.UserResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages authenticated user session and JWT token.
 */
class SessionManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "wasteinfoai_auth_prefs"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_ROLE = "user_role"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CATEGORY = "user_category"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tokenState = MutableStateFlow<String?>(prefs.getString(KEY_TOKEN, null))
    val tokenState: StateFlow<String?> = _tokenState.asStateFlow()

    private val _roleState = MutableStateFlow(prefs.getString(KEY_ROLE, "RESIDENT") ?: "RESIDENT")
    val roleState: StateFlow<String> = _roleState.asStateFlow()

    private val _categoryState = MutableStateFlow(prefs.getString(KEY_CATEGORY, "Households") ?: "Households")
    val categoryState: StateFlow<String> = _categoryState.asStateFlow()

    private val _emailState = MutableStateFlow(prefs.getString(KEY_EMAIL, "") ?: "")
    val emailState: StateFlow<String> = _emailState.asStateFlow()

    val isLoggedIn: Boolean
        get() = !_tokenState.value.isNullOrBlank()

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getRole(): String = prefs.getString(KEY_ROLE, "RESIDENT") ?: "RESIDENT"

    fun getCategory(): String = prefs.getString(KEY_CATEGORY, "Households") ?: "Households"

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getDisplayName(): String = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""

    fun setCategory(category: String) {
        prefs.edit().putString(KEY_CATEGORY, category).apply()
        _categoryState.value = category
    }

    fun saveSession(token: String, user: UserResponse, category: String? = null) {
        val editor = prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_ROLE, user.role)
            .putString(KEY_DISPLAY_NAME, user.displayName ?: user.fullName ?: user.email)
        
        if (category != null) {
            editor.putString(KEY_CATEGORY, category)
            _categoryState.value = category
        }
        editor.apply()

        _tokenState.value = token
        _roleState.value = user.role
        _emailState.value = user.email
    }

    fun setDevRoleOverride(role: String) {
        prefs.edit().putString(KEY_ROLE, role).apply()
        _roleState.value = role
    }

    fun clearSession() {
        prefs.edit().clear().apply()
        _tokenState.value = null
        _roleState.value = "RESIDENT"
        _categoryState.value = "Households"
        _emailState.value = ""
    }
}
