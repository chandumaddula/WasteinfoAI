package com.wasteinfoai.features.common

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.wasteinfoai.R
import com.wasteinfoai.core.language.LanguageManager
import com.wasteinfoai.core.language.SupportedLanguage
import com.wasteinfoai.data.remote.SessionManager

/**
 * In-app language selection dialog allowing users to choose between:
 * 1. English
 * 2. हिन्दी (Hindi)
 * 3. తెలుగు (Telugu)
 * 4. தமிழ் (Tamil)
 * 5. मराठी (Marathi)
 * Displays both the native script name and English name side-by-side.
 */
@Composable
fun LanguageSelectionDialog(
    languageManager: LanguageManager? = null,
    sessionManager: SessionManager? = null,
    onLanguageSelected: ((String) -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val effectiveSessionManager = remember {
        sessionManager ?: (context.applicationContext as? com.wasteinfoai.WasteInfoAIApp)?.appContainer?.sessionManager
        ?: SessionManager(context)
    }
    val effectiveLanguageManager = remember {
        languageManager ?: (context.applicationContext as? com.wasteinfoai.WasteInfoAIApp)?.appContainer?.languageManager
        ?: LanguageManager(context, effectiveSessionManager)
    }

    val currentLangCode by effectiveLanguageManager.currentLanguage.collectAsState()
    var selectedCode by remember { mutableStateOf(currentLangCode) }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.language_select_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Choose preferred app language",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

                // List of 5 languages
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(weight = 1f, fill = false)
                ) {
                    items(LanguageManager.SUPPORTED_LANGUAGES) { lang ->
                        val isSelected = lang.code == selectedCode

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                            tonalElevation = if (isSelected) 2.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedCode = lang.code
                                    effectiveLanguageManager.setLanguage(lang.code)
                                    val toastMsg = context.getString(R.string.language_changed_toast, lang.displayName)
                                    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                    if (onLanguageSelected != null) {
                                        onLanguageSelected(lang.code)
                                    } else {
                                        onDismissRequest()
                                    }
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = lang.nativeName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (lang.code != "en") {
                                        Text(
                                            text = lang.englishName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedCode = lang.code
                                        effectiveLanguageManager.setLanguage(lang.code)
                                        val toastMsg = context.getString(R.string.language_changed_toast, lang.displayName)
                                        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                        if (onLanguageSelected != null) {
                                            onLanguageSelected(lang.code)
                                        } else {
                                            onDismissRequest()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        if (onLanguageSelected != null) {
                            onLanguageSelected(selectedCode)
                        } else {
                            onDismissRequest()
                        }
                    }) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
        }
    }
}

