package com.wasteinfoai.core.language

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import com.wasteinfoai.data.remote.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SupportedLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val displayName: String,
    val speechLocaleTag: String,
    val ttsLocale: Locale
)

/**
 * Single source of truth for multilingual application state and Android locale synchronization.
 * Supports:
 * 1. English (en) — Default
 * 2. Hindi (hi) — हिन्दी
 * 3. Telugu (te) — తెలుగు
 * 4. Tamil (ta) — தமிழ்
 * 5. Marathi (mr) — मराठी
 * 6. Kannada (kn) — ಕನ್ನಡ
 * 7. Malayalam (ml) — മലയാളം
 * 8. Bengali (bn) — বাংলা
 * 9. Gujarati (gu) — ગુજરાતી
 */
class LanguageManager(
    private val context: Context,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "LanguageManager"

        val SUPPORTED_LANGUAGES = listOf(
            SupportedLanguage(
                code = "en",
                englishName = "English",
                nativeName = "English",
                displayName = "English",
                speechLocaleTag = "en-IN",
                ttsLocale = Locale("en", "IN")
            ),
            SupportedLanguage(
                code = "hi",
                englishName = "Hindi",
                nativeName = "हिन्दी",
                displayName = "हिन्दी (Hindi)",
                speechLocaleTag = "hi-IN",
                ttsLocale = Locale("hi", "IN")
            ),
            SupportedLanguage(
                code = "te",
                englishName = "Telugu",
                nativeName = "తెలుగు",
                displayName = "తెలుగు (Telugu)",
                speechLocaleTag = "te-IN",
                ttsLocale = Locale("te", "IN")
            ),
            SupportedLanguage(
                code = "ta",
                englishName = "Tamil",
                nativeName = "தமிழ்",
                displayName = "தமிழ் (Tamil)",
                speechLocaleTag = "ta-IN",
                ttsLocale = Locale("ta", "IN")
            ),
            SupportedLanguage(
                code = "mr",
                englishName = "Marathi",
                nativeName = "मराठी",
                displayName = "मराठी (Marathi)",
                speechLocaleTag = "mr-IN",
                ttsLocale = Locale("mr", "IN")
            ),
            SupportedLanguage(
                code = "kn",
                englishName = "Kannada",
                nativeName = "ಕನ್ನಡ",
                displayName = "ಕನ್ನಡ (Kannada)",
                speechLocaleTag = "kn-IN",
                ttsLocale = Locale("kn", "IN")
            ),
            SupportedLanguage(
                code = "ml",
                englishName = "Malayalam",
                nativeName = "മലയാളം",
                displayName = "മലയാളം (Malayalam)",
                speechLocaleTag = "ml-IN",
                ttsLocale = Locale("ml", "IN")
            ),
            SupportedLanguage(
                code = "bn",
                englishName = "Bengali",
                nativeName = "বাংলা",
                displayName = "বাংলা (Bengali)",
                speechLocaleTag = "bn-IN",
                ttsLocale = Locale("bn", "IN")
            ),
            SupportedLanguage(
                code = "gu",
                englishName = "Gujarati",
                nativeName = "ગુજરાતી",
                displayName = "ગુજરાતી (Gujarati)",
                speechLocaleTag = "gu-IN",
                ttsLocale = Locale("gu", "IN")
            )
        )

        fun findSupportedLanguage(code: String?): SupportedLanguage {
            val clean = code?.trim()?.lowercase()?.take(2) ?: "en"
            return SUPPORTED_LANGUAGES.firstOrNull { it.code == clean } ?: SUPPORTED_LANGUAGES[0]
        }
    }

    private val _currentLanguage = MutableStateFlow(
        findSupportedLanguage(sessionManager.getLanguage()).code
    )
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    init {
        applyLocaleToSystem(_currentLanguage.value)
    }

    fun getCurrentLanguageOption(): SupportedLanguage {
        return findSupportedLanguage(_currentLanguage.value)
    }

    fun setLanguage(languageCode: String) {
        val target = findSupportedLanguage(languageCode)
        if (target.code == _currentLanguage.value) {
            // Re-apply to ensure consistency
            applyLocaleToSystem(target.code)
            return
        }

        sessionManager.setLanguage(target.code)
        _currentLanguage.value = target.code
        applyLocaleToSystem(target.code)

        // Immediately reload DisposalRuleEngine in target language
        try {
            (context.applicationContext as? com.wasteinfoai.WasteInfoAIApp)?.appContainer?.disposalRuleEngine?.loadRulesForLanguage(target.code)
        } catch (e: Exception) {
            Log.w(TAG, "Could not reload disposal rules on language change", e)
        }

        Log.i(TAG, "Language changed to ${target.displayName} (${target.code})")
    }

    fun applyLocaleToSystem(code: String) {
        val target = findSupportedLanguage(code)
        val locale = target.ttsLocale

        Locale.setDefault(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeManager = context.getSystemService(LocaleManager::class.java)
                localeManager?.applicationLocales = LocaleList.forLanguageTags(target.code)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting applicationLocales via LocaleManager", e)
            }
        }

        // Apply configuration to resources for backward compatibility & immediate Compose effect
        try {
            val resources = context.resources
            val config = Configuration(resources.configuration)
            config.setLocale(locale)
            resources.updateConfiguration(config, resources.displayMetrics)
        } catch (e: Exception) {
            Log.w(TAG, "Failed updating Resources configuration", e)
        }
    }

    /**
     * Creates a localized context for configuration changes and UI wrappers.
     */
    fun createLocalizedContext(baseContext: Context, languageCode: String? = null): Context {
        val code = languageCode ?: _currentLanguage.value
        val target = findSupportedLanguage(code)
        val locale = target.ttsLocale

        val config = Configuration(baseContext.resources.configuration)
        config.setLocale(locale)
        return baseContext.createConfigurationContext(config)
    }
}

