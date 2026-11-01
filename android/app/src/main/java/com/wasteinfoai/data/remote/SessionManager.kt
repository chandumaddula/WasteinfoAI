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
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_OFFLINE_MODE = "offline_mode"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000/"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tokenState = MutableStateFlow<String?>(prefs.getString(KEY_TOKEN, null))
    val tokenState: StateFlow<String?> = _tokenState.asStateFlow()

    private val _roleState = MutableStateFlow(prefs.getString(KEY_ROLE, "RESIDENT") ?: "RESIDENT")
    val roleState: StateFlow<String> = _roleState.asStateFlow()

    private val _categoryState = MutableStateFlow(prefs.getString(KEY_CATEGORY, "Household recycling") ?: "Household recycling")
    val categoryState: StateFlow<String> = _categoryState.asStateFlow()

    private val _emailState = MutableStateFlow(prefs.getString(KEY_EMAIL, "") ?: "")
    val emailState: StateFlow<String> = _emailState.asStateFlow()

    private val _serverUrlState = MutableStateFlow(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL)
    val serverUrlState: StateFlow<String> = _serverUrlState.asStateFlow()

    private val _isOfflineModeState = MutableStateFlow(prefs.getBoolean(KEY_OFFLINE_MODE, false))
    val isOfflineModeState: StateFlow<Boolean> = _isOfflineModeState.asStateFlow()

    val isLoggedIn: Boolean
        get() = !_tokenState.value.isNullOrBlank()

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getRole(): String = prefs.getString(KEY_ROLE, "RESIDENT") ?: "RESIDENT"

    fun getCategory(): String = prefs.getString(KEY_CATEGORY, "Household recycling") ?: "Household recycling"

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getDisplayName(): String = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""

    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL

    fun isOfflineMode(): Boolean = prefs.getBoolean(KEY_OFFLINE_MODE, false)

    fun setServerUrl(url: String) {
        val formatted = if (url.endsWith("/")) url else "$url/"
        prefs.edit().putString(KEY_SERVER_URL, formatted).apply()
        _serverUrlState.value = formatted
    }

    fun setOfflineMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
        _isOfflineModeState.value = enabled
    }

    fun setCategory(category: String) {
        prefs.edit().putString(KEY_CATEGORY, category).apply()
        _categoryState.value = category
    }

    fun saveSession(token: String, user: UserResponse, category: String? = null) {
        val safeRole = user.role.ifBlank { "RESIDENT" }
        val safeEmail = user.email
        val safeUserId = user.id
        val safeDisplayName = user.displayName ?: user.fullName ?: safeEmail

        val editor = prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, safeUserId)
            .putString(KEY_EMAIL, safeEmail)
            .putString(KEY_ROLE, safeRole)
            .putString(KEY_DISPLAY_NAME, safeDisplayName)
        
        if (category != null) {
            editor.putString(KEY_CATEGORY, category)
            _categoryState.value = category
        }
        editor.apply()

        _tokenState.value = token
        _roleState.value = safeRole
        _emailState.value = safeEmail
    }

    fun registerLocalAccount(email: String, password: String?, fullName: String?, category: String, role: String) {
        val cleanEmail = email.trim().lowercase()
        prefs.edit()
            .putString("local_acc_${cleanEmail}_pwd", password ?: "")
            .putString("local_acc_${cleanEmail}_name", fullName ?: cleanEmail.substringBefore("@"))
            .putString("local_acc_${cleanEmail}_category", category)
            .putString("local_acc_${cleanEmail}_role", role)
            .apply()
    }

    fun hasLocalAccount(email: String): Boolean {
        val cleanEmail = email.trim().lowercase()
        return prefs.contains("local_acc_${cleanEmail}_pwd")
    }

    fun verifyLocalAccount(email: String, password: String?): Boolean {
        val cleanEmail = email.trim().lowercase()
        val storedPwd = prefs.getString("local_acc_${cleanEmail}_pwd", null) ?: return true
        if (storedPwd.isBlank()) return true
        return storedPwd == (password ?: "")
    }

    fun saveLocalSession(email: String, category: String, role: String) {
        val cleanEmail = email.trim().lowercase()
        val savedName = prefs.getString("local_acc_${cleanEmail}_name", null)
        val displayName = savedName ?: email.substringBefore("@").replaceFirstChar { it.uppercase() }
        val localUser = UserResponse(
            id = "local_${System.currentTimeMillis()}",
            email = email.ifBlank { "guest@wasteinfo.ai" },
            role = role,
            fullName = displayName,
            displayName = displayName,
            isActive = true
        )
        saveSession(
            token = "local_token_${System.currentTimeMillis()}",
            user = localUser,
            category = category
        )
    }

    fun setDevRoleOverride(role: String) {
        prefs.edit().putString(KEY_ROLE, role).apply()
        _roleState.value = role
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_EMAIL)
            .remove(KEY_ROLE)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_CATEGORY)
            .apply()
        _tokenState.value = null
        _roleState.value = "RESIDENT"
        _categoryState.value = "Household recycling"
        _emailState.value = ""
    }
}
