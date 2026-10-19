package com.wasteinfoai.features.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasteinfoai.data.remote.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Composable
fun ServerConfigDialog(
    sessionManager: SessionManager,
    onDismissRequest: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var urlInput by remember { mutableStateOf(sessionManager.getServerUrl()) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }

    val presets = remember {
        listOf(
            "Hotspot PC (Phone Wi-Fi)" to "http://192.168.137.1:8000/",
            "Home Wi-Fi PC (Router)" to "http://192.168.1.100:8000/",
            "Android Emulator" to "http://10.0.2.2:8000/",
            "Cloud Internet API" to "https://api.wasteinfo.ai/"
        )
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Server & Internet Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Configure backend address & network mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "When using a mobile phone, connect to your PC's Wi-Fi IP or an online internet URL. The app also works completely offline with on-device AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        testStatus = null
                    },
                    label = { Text("Server Base URL") },
                    placeholder = { Text("http://192.168.137.1:8000/") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Text(
                    text = "Quick Presets:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                presets.forEach { (label, presetUrl) ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                urlInput = presetUrl
                                testStatus = null
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = if (urlInput == presetUrl) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(presetUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (urlInput == presetUrl) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Test Connection Button
                OutlinedButton(
                    onClick = {
                        isTesting = true
                        testStatus = null
                        coroutineScope.launch {
                            val target = if (urlInput.endsWith("/")) urlInput else "$urlInput/"
                            val result = testUrlConnection(target)
                            isSuccess = result.first
                            testStatus = result.second
                            isTesting = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isTesting,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection", style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (testStatus != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = null,
                                tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = testStatus!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    sessionManager.setServerUrl(urlInput.trim())
                    onDismissRequest()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save & Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

private suspend fun testUrlConnection(baseUrl: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val testClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("${baseUrl}api/v1/health")
            .get()
            .build()

        val response = testClient.newCall(request).execute()
        if (response.isSuccessful) {
            true to "Connected successfully to backend!"
        } else {
            true to "Server reached (HTTP ${response.code})"
        }
    } catch (e: Exception) {
        false to "Could not connect: ${e.localizedMessage ?: "Timeout / unreachable"}. (Local mode will be used)"
    }
}
