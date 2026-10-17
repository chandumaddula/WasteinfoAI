package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wasteinfoai.ai.rules.HazardSeverity
import com.wasteinfoai.core.theme.AmberWarning
import com.wasteinfoai.core.theme.RedHazard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    imagePath: String,
    viewModel: ScanResultViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToCreateReport: (String, String) -> Unit,
    onNavigateToAssistant: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(imagePath) {
        viewModel.processImage(imagePath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Classification & Guidance") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState is ScanResultUiState.Success) {
                        IconButton(onClick = { viewModel.speakGuidance() }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "Read Aloud")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is ScanResultUiState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Running on-device ExecuTorch inference...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is ScanResultUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Classification Error",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onNavigateBack) {
                            Text("Try Another Photo")
                        }
                    }
                }
                is ScanResultUiState.Success -> {
                    SuccessContent(
                        state = state,
                        onReportClicked = {
                            onNavigateToCreateReport(state.classification.label, state.imageFile.absolutePath)
                        },
                        onAssistantClicked = {
                            onNavigateToAssistant(state.classification.label)
                        },
                        onDoneClicked = onNavigateBack,
                        onSpeakClicked = { viewModel.speakGuidance() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuccessContent(
    state: ScanResultUiState.Success,
    onReportClicked: () -> Unit,
    onAssistantClicked: () -> Unit,
    onDoneClicked: () -> Unit,
    onSpeakClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Image preview with overlay badges
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (state.bitmap != null) {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = "Scanned item",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                }

                // Confidence Chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    Text(
                        text = "${String.format("%.1f%%", state.classification.confidence * 100)} conf • ${state.classification.inferenceDurationMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Identified Category Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.safety.isHazardous) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.safety.isHazardous) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.safety.isHazardous) Icons.Default.Warning else Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.classification.isUncertain) "Uncertain Prediction" else state.classification.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.safety.isHazardous) "Hazardous Material" else "Non-Hazardous Item",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onSpeakClicked) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Speak")
                }
            }
        }

        // Safety Warnings (if any)
        if (state.safety.severityLevel != HazardSeverity.LOW) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (state.safety.isHazardous) RedHazard else AmberWarning
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Safety Guidance",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(state.safety.primaryWarning, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.safety.recommendedActions.forEach { action ->
                        Text("• $action", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Disposal Rules Card
        if (state.rule != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Disposal & Segregation",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Target Bin", style = MaterialTheme.typography.labelSmall)
                            Text(
                                state.rule.targetBinColor,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("Recyclable", style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (state.rule.isRecyclable) "Yes" else "No",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("Jurisdiction", style = MaterialTheme.typography.labelSmall)
                            Text(state.rule.jurisdiction, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Preparation Instructions:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(state.rule.preparationInstructions, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Recommended Method:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(state.rule.disposalMethod, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onAssistantClicked,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ask AI")
            }
            Button(
                onClick = onReportClicked,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Report")
            }
        }

        Button(
            onClick = onDoneClicked,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Done", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
