package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasteinfoai.ai.rules.HazardSeverity
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.taxonomy.SpecificWasteCandidate
import com.wasteinfoai.ai.vision.QualityStatus
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
                            "Running on-device ExecuTorch & OpenCV analysis...",
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
                            val label = state.userConfirmedLabel
                                ?: state.hierarchicalIdentification?.specificItem
                                ?: state.classification.label
                            onNavigateToCreateReport(label, state.imageFile.absolutePath)
                        },
                        onAssistantClicked = {
                            val label = state.userConfirmedLabel
                                ?: state.hierarchicalIdentification?.specificItem
                                ?: state.classification.label
                            onNavigateToAssistant(label)
                        },
                        onDoneClicked = onNavigateBack,
                        onSpeakClicked = { viewModel.speakGuidance() },
                        onSelectCategory = { cat, subItem ->
                            viewModel.confirmCategory(cat, subItem)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuccessContent(
    state: ScanResultUiState.Success,
    onReportClicked: () -> Unit,
    onAssistantClicked: () -> Unit,
    onDoneClicked: () -> Unit,
    onSpeakClicked: () -> Unit,
    onSelectCategory: (String, String?) -> Unit
) {
    var showCategorySelector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Image preview with overlay badges
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp),
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

                // Confidence & Detection Mode Chip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    val detectionBadge = if (state.detectedObjectsCount > 0) "OpenCV ROI Crop" else "Letterbox"
                    Text(
                        text = "${String.format("%.1f%%", state.classification.confidence * 100)} conf • $detectionBadge",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Quality Badge if warning
                state.qualityAssessment?.let { qa ->
                    if (qa.status != QualityStatus.ACCEPTABLE) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE65100).copy(alpha = 0.9f),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "⚠️ Quality: ${qa.status.name}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Primary Identified Category / Item Header Card
        val displayTitle = state.userConfirmedLabel
            ?: state.hierarchicalIdentification?.specificItem
            ?: (if (state.classification.isUncertain) "Uncertain Prediction" else state.classification.label)

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
                        text = displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sourceText = when (state.hierarchicalIdentification?.identificationSource) {
                        IdentificationSource.BARCODE_LOOKUP -> "Source: Barcode packaging database"
                        IdentificationSource.USER_CONFIRMED -> "Source: User confirmed"
                        IdentificationSource.OBJECT_CROP_CLASSIFIER -> "Source: Object ROI ReXNet-150"
                        else -> "Source: ReXNet-150 on-device"
                    }
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (state.safety.isHazardous) "Hazardous Material • Handle with Care" else "Non-Hazardous Solid Waste",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.safety.isHazardous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                IconButton(onClick = onSpeakClicked) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Speak Guidance")
                }
            }
        }

        // 3. Top-3 Candidate Predictions with Confidence Bars
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Top-3 Candidates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap to select",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scores are softmax-normalized classifier outputs across 17 categories.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val candidates = state.classification.topKResults.take(3)
                candidates.forEach { candidate ->
                    val isSelected = candidate.label.equals(state.classification.label, ignoreCase = true)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectCategory(candidate.label, null) }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = candidate.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = String.format("%.1f%% (%.2f)", candidate.score * 100, candidate.score),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { candidate.score },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        // 4. Specific Item Candidates & User Correction Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Not what you scanned?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showCategorySelector = !showCategorySelector }) {
                        Text(if (showCategorySelector) "Hide" else "Choose another")
                    }
                }

                // If specific item candidates exist for this category, display chips
                if (state.specificCandidates.isNotEmpty()) {
                    Text(
                        text = "Refine specific item identity:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(state.specificCandidates) { itemCandidate ->
                            FilterChip(
                                selected = state.userConfirmedLabel == itemCandidate.displayName,
                                onClick = {
                                    onSelectCategory(itemCandidate.coarseCategory, itemCandidate.displayName)
                                },
                                label = { Text(itemCandidate.displayName, maxLines = 1) }
                            )
                        }
                    }
                }

                // Expandable coarse category selector
                AnimatedVisibility(visible = showCategorySelector) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Select canonical category:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val canonical17 = listOf(
                            "plastic", "metal", "cardboard", "glass", "paper", "organic",
                            "Battery", "Mobile", "Keyboard", "Mouse", "Microwave",
                            "Television", "Printer", "Washing Machine", "PCB", "Player", "trash"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            canonical17.forEach { cat ->
                                SuggestionChip(
                                    onClick = {
                                        onSelectCategory(cat, null)
                                        showCategorySelector = false
                                    },
                                    label = { Text(cat) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Product Packaging Breakdown (if resolved from barcode)
        state.productPackagingInfo?.let { prod ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Barcode Identified: ${prod.productName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    prod.brands?.let {
                        Text("Brand: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    prod.materialCode?.let {
                        Text("Material Code: $it", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    prod.packagingDescription?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Packaging: $it", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 6. Safety Warnings Card (if non-low)
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

        // 7. Disposal & Segregation Card
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
                            val binColor = state.rule.targetBinColor
                            val binBg = when (binColor.uppercase()) {
                                "BLUE" -> Color(0xFF1976D2)
                                "GREEN" -> Color(0xFF388E3C)
                                "RED" -> Color(0xFFD32F2F)
                                "ORANGE" -> Color(0xFFF57C00)
                                else -> Color(0xFF424242)
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = binBg,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = binColor,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
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
                            Text(state.rule.jurisdiction, style = MaterialTheme.typography.bodySmall)
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

                    if (state.rule.acceptedPathways.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Accepted Pathways: ${state.rule.acceptedPathways.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.rule.prohibitedPathways.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Prohibited: ${state.rule.prohibitedPathways.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // 8. Action Buttons
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
