package com.wasteinfoai

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.compose.rememberNavController
import com.wasteinfoai.core.navigation.AppNavGraph
import com.wasteinfoai.core.theme.WasteInfoAITheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WasteInfoAIApp
        val languageManager = app.appContainer.languageManager
        languageManager.applyLocaleToSystem(languageManager.getCurrentLanguageOption().code)

        setContent {
            val currentLang by languageManager.currentLanguage.collectAsState()
            val navController = rememberNavController()

            val configuration = LocalConfiguration.current
            val localizedConfiguration = remember(currentLang, configuration) {
                Configuration(configuration).apply {
                    setLocale(Locale.forLanguageTag(currentLang))
                }
            }

            CompositionLocalProvider(LocalConfiguration provides localizedConfiguration) {
                WasteInfoAITheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppNavGraph(
                            navController = navController,
                            container = app.appContainer
                        )
                    }
                }
            }
        }
    }
}
