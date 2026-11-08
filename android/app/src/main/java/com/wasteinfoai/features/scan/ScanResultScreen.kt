package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasteinfoai.R
import com.wasteinfoai.core.language.CategoryLocalization
import androidx.compose.ui.res.stringResource
import com.wasteinfoai.ai.rules.HazardSeverity
import com.wasteinfoai.ai.taxonomy.IdentificationSource
import com.wasteinfoai.ai.taxonomy.SpecificWasteCandidate
import com.wasteinfoai.ai.vision.QualityStatus
import com.wasteinfoai.core.theme.AmberWarning
import com.wasteinfoai.core.theme.RedHazard
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    imagePath: String,
    viewModel: ScanResultViewModel,
    userCategory: String = "",
    onNavigateBack: () -> Unit,
    onDone: (() -> Unit)? = null,
    onNavigateToCreateReport: (String, String) -> Unit,
    onNavigateToAssistant: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val canReport = com.wasteinfoai.features.onboarding.UserCategory.isWasteLocationCategory(userCategory)
    val currentLang = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]?.language ?: "en"

    LaunchedEffect(imagePath) {
        viewModel.processImage(imagePath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_result_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    val current = uiState
                    if (current is ScanResultUiState.Success) {
                        IconButton(onClick = {
                            if (current.items.size > 1) viewModel.speakAllItems(currentLang) else viewModel.speakGuidance(currentLang)
                        }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.action_read_aloud))
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
                            stringResource(R.string.scan_finding_classifying),
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
                            stringResource(R.string.scan_classification_error),
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
                            Text(stringResource(R.string.scan_try_another))
                        }
                    }
                }
                is ScanResultUiState.Success -> {
                    SuccessContent(
                        state = state,
                        canReport = canReport,
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
                        onDoneClicked = onDone ?: onNavigateBack,
                        onSpeakClicked = { viewModel.speakGuidance(currentLang) },
                        onSelectCategory = { cat, subItem ->
                            viewModel.confirmCategory(cat, subItem)
                        },
                        onSelectObject = { idx ->
                            viewModel.selectDetectedObject(idx)
                        },
                        onSubmitFeedback = { choice, cat, item, consent ->
                            viewModel.submitFeedback(choice, cat, item, consent)
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
    canReport: Boolean = false,
    onReportClicked: () -> Unit,
    onAssistantClicked: () -> Unit,
    onDoneClicked: () -> Unit,
    onSpeakClicked: () -> Unit,
    onSelectCategory: (String, String?) -> Unit,
    onSelectObject: (Int) -> Unit = {},
    onSubmitFeedback: (String, String?, String?, Boolean) -> Unit = { _, _, _, _ -> }
) {
    var showCategorySelector by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Image preview: upright photo with numbered, tappable boxes around every detected item
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val bmp = state.bitmap
                if (bmp != null) {
                    // Exact aspect ratio + FillBounds = no cropping, so normalized boxes line up with the pixels.
                    BoxWithConstraints(
                        modifier = Modifier.aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    ) {
                        val boxW = maxWidth
                        val boxH = maxHeight
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Scanned items",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                        // Bounding boxes on detected objects styled with candidates scores
                        val displayBoxes = if (state.items.isNotEmpty()) {
                            state.items.mapIndexedNotNull { idx, it ->
                                val box = it.source?.boundingBox ?: state.detectedObjects.getOrNull(idx)?.boundingBox
                                if (box != null) Triple(idx, it, box) else null
                            }
                        } else {
                            state.detectedObjects.mapIndexed { idx, obj ->
                                Triple(idx, null, obj.boundingBox)
                            }
                        }

                        displayBoxes.forEach { (idx, item, b) ->
                            val isSelected = idx == state.selectedObjectIndex
                            val isUserConfirmed = item?.isUserConfirmed == true || (isSelected && state.isUserConfirmed)
                            val confirmedLabel = item?.userConfirmedLabel ?: if (isSelected) state.userConfirmedLabel else null

                            val topCandidate = item?.classification?.topKResults?.firstOrNull()
                                ?: state.classification.topKResults.firstOrNull()
                            val score = if (isUserConfirmed && !confirmedLabel.isNullOrBlank()) {
                                1.0f
                            } else {
                                topCandidate?.score ?: item?.classification?.confidence ?: state.classification.confidence
                            }
                            val rawLabel = if (isUserConfirmed && !confirmedLabel.isNullOrBlank()) {
                                confirmedLabel
                            } else {
                                topCandidate?.label?.ifEmpty { null }
                                    ?: item?.classification?.label?.ifEmpty { null }
                                    ?: state.classification.label
                            }
                            val displayLabel = if (rawLabel.isNotEmpty()) {
                                CategoryLocalization.getCategoryDisplayName(rawLabel, Locale.getDefault().language)
                            } else "Item"

                            // Tint based on candidates scores
                            val tint = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                score >= 0.90f -> Color(0xFF2E7D32) // High confidence candidate (Green)
                                score >= 0.70f -> Color(0xFFF9A825) // Moderate confidence candidate (Amber)
                                else -> Color(0xFFD32F2F)           // Low confidence candidate (Red)
                            }

                            val boxLeft = (boxW * b.left).coerceIn(0.dp, boxW)
                            val boxTop = (boxH * b.top).coerceIn(0.dp, boxH)
                            val boxWidth = (boxW * (b.right - b.left)).coerceIn(12.dp, boxW - boxLeft)
                            val boxHeight = (boxH * (b.bottom - b.top)).coerceIn(12.dp, boxH - boxTop)

                            Box(
                                modifier = Modifier
                                    .offset(x = boxLeft, y = boxTop)
                                    .size(width = boxWidth, height = boxHeight)
                                    .background(tint.copy(alpha = if (isSelected) 0.16f else 0.08f), RoundedCornerShape(6.dp))
                                    .border(if (isSelected) 3.dp else 2.dp, tint, RoundedCornerShape(6.dp))
                                    .clickable { onSelectObject(idx) }
                            ) {
                                Surface(
                                    color = tint,
                                    shape = RoundedCornerShape(bottomEnd = 6.dp)
                                ) {
                                    val badgeText = if (isUserConfirmed && !confirmedLabel.isNullOrBlank()) {
                                        if (displayBoxes.size > 1) {
                                            "${idx + 1}. $displayLabel"
                                        } else {
                                            displayLabel
                                        }
                                    } else {
                                        if (displayBoxes.size > 1) {
                                            "${idx + 1}. $displayLabel ${(score * 100).toInt()}%"
                                        } else {
                                            "$displayLabel ${(score * 100).toInt()}%"
                                        }
                                    }
                                    Text(
                                        text = badgeText,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(48.dp))
                    }
                }

                // Confidence & Detection Mode Chip (for the selected item)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                ) {
                    val detectionBadge = if (state.detectedObjectsCount > 0) stringResource(R.string.scan_item_badge, state.selectedObjectIndex + 1) else stringResource(R.string.scan_full_image)
                    Text(
                        text = "${String.format(Locale.US, "%.1f%%", state.classification.confidence * 100)} conf • $detectionBadge",
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

        if (state.ignoredObjectCount > 0) {
            Text(
                text = stringResource(R.string.scan_ignored_objects, state.ignoredObjectCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1.1 Every item found in the photo, each with its own bin and guidance
        if (state.items.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.scan_items_found, state.items.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                state.items.forEachIndexed { idx, item ->
                    ItemSummaryCard(
                        index = idx,
                        item = item,
                        selected = idx == state.selectedObjectIndex,
                        onSelect = { onSelectObject(idx) },
                        onQuickConfirm = { label ->
                            onSelectObject(idx)            // select first (synchronous) ...
                            onSelectCategory(label, null)  // ... then confirm for that item
                        }
                    )
                }
                Text(
                    text = stringResource(R.string.scan_details_for_item, state.selectedObjectIndex + 1),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 1.5 Conflicting Evidence Banner (if detected)
        if (state.hasEvidenceConflict && state.conflictExplanation != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Conflict",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Conflicting Evidence Detected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = state.conflictExplanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Please verify and select the correct material below.",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // 2. Primary Identified Category / Guidance Card
        val isRejected = !state.classification.isAccepted && !state.isUserConfirmed
        val policy = state.classification.policyResult

        val hasDetectedBox = state.detectedObjectsCount > 0 || state.detectedObjects.isNotEmpty() || state.items.any { it.source?.boundingBox != null }
        val candidateLabel = state.classification.topKResults.firstOrNull()?.label?.takeIf { !it.equals("not_waste", ignoreCase = true) }
            ?: state.classification.label.takeIf { it != "No Clear Waste Detected" }

        val rawDisplayTitle = state.userConfirmedLabel
            ?: if (hasDetectedBox && candidateLabel != null) {
                candidateLabel
            } else if (isRejected) {
                stringResource(R.string.scan_no_clear_waste)
            } else {
                state.classification.label
            }
        val displayTitle = if (rawDisplayTitle == stringResource(R.string.scan_no_clear_waste)) {
            rawDisplayTitle
        } else {
            CategoryLocalization.localizedCategory(rawDisplayTitle)
        }
        val detectedSubItem = state.hierarchicalIdentification?.specificItem?.takeIf {
            !it.equals(rawDisplayTitle, ignoreCase = true) && !it.equals(state.classification.label, ignoreCase = true)
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    hasDetectedBox && candidateLabel != null && !state.safety.isHazardous -> MaterialTheme.colorScheme.primaryContainer
                    isRejected -> MaterialTheme.colorScheme.surfaceVariant
                    state.safety.isHazardous -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    state.safety.isHazardous -> MaterialTheme.colorScheme.error
                                    hasDetectedBox && candidateLabel != null -> MaterialTheme.colorScheme.primary
                                    isRejected -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                state.safety.isHazardous -> Icons.Default.Warning
                                hasDetectedBox && candidateLabel != null -> Icons.Default.Check
                                isRejected -> Icons.Default.HelpOutline
                                else -> Icons.Default.Check
                            },
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
                        if (detectedSubItem != null) {
                            Text(
                                text = "Item: $detectedSubItem",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                        val sourceText = when {
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.BARCODE_LOOKUP -> stringResource(R.string.scan_source_barcode)
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.USER_CONFIRMED -> stringResource(R.string.scan_source_user_confirmed)
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.OBJECT_CROP_CLASSIFIER -> stringResource(R.string.scan_source_roi_classifier)
                            hasDetectedBox && candidateLabel != null && isRejected -> "Status: DETECTED (${String.format(Locale.US, "%.1f%%", state.classification.confidence * 100)})"
                            isRejected -> stringResource(R.string.scan_status_prefix, state.classification.acceptanceState.name.replace('_', ' '))
                            else -> stringResource(R.string.scan_source_on_device)
                        }
                        Text(
                            text = sourceText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (state.safety.isHazardous) {
                                stringResource(R.string.scan_hazardous_warning)
                            } else if (hasDetectedBox && candidateLabel != null && isRejected) {
                                "Identified as $displayTitle with ${(state.classification.confidence * 100).toInt()}% confidence. Please confirm below."
                            } else if (isRejected) {
                                policy?.diagnosticDetails ?: stringResource(R.string.scan_rejected_default)
                            } else {
                                stringResource(R.string.scan_non_hazardous)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.safety.isHazardous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSpeakClicked) {
                        Icon(Icons.Default.VolumeUp, contentDescription = stringResource(R.string.scan_speak_guidance))
                    }
                }

                if (isRejected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val messageText = if (hasDetectedBox && candidateLabel != null) {
                            "Object detected in frame as $displayTitle (${String.format(Locale.US, "%.1f%%", state.classification.confidence * 100)}). Please confirm the item type below."
                        } else {
                            policy?.userFacingMessage
                                ?: "No clear waste item detected. Please position the item inside the frame and try again."
                        }
                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDoneClicked,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.scan_again))
                        }
                        if (hasDetectedBox && candidateLabel != null) {
                            Button(
                                onClick = { onSelectCategory(candidateLabel, null) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Confirm $displayTitle")
                            }
                        } else {
                            Button(
                                onClick = { showCategorySelector = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.scan_select_manually))
                            }
                        }
                    }
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
                        text = stringResource(R.string.scan_top_candidates),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.scan_tap_select),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.scan_candidates_subtitle),
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
                                text = CategoryLocalization.localizedCategory(candidate.label),
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
                        text = stringResource(R.string.scan_not_what_you_scanned),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { showCategorySelector = !showCategorySelector }) {
                        Text(if (showCategorySelector) stringResource(R.string.scan_hide) else stringResource(R.string.scan_choose_another))
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

        // 5.5 Packaging OCR & Symbol Evidence Card (if detected)
        state.ocrEvidence?.let { ocr ->
            if (ocr.materialCode != null || ocr.detectedKeywords.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Packaging Markings & Symbol Evidence",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        ocr.materialCode?.let { code ->
                            Text(
                                text = "Resin Code: $code (${ocr.materialName ?: ""})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (ocr.detectedKeywords.isNotEmpty()) {
                            Text(
                                text = "Detected packaging text: ${ocr.detectedKeywords.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
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
                            stringResource(R.string.scan_safety_guidance),
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
                        stringResource(R.string.scan_disposal_segregation),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val binColor = state.rule.targetBinColor
                    val binBg = when (binColor.uppercase(Locale.US)) {
                        "BLUE" -> Color(0xFF1976D2)
                        "GREEN" -> Color(0xFF2E7D32)
                        "RED" -> Color(0xFFC62828)
                        "ORANGE" -> Color(0xFFE65100)
                        else -> Color(0xFF37474F)
                    }
                    val binFullName = when (binColor.uppercase(Locale.US)) {
                        "BLUE" -> stringResource(R.string.bin_blue)
                        "GREEN" -> stringResource(R.string.bin_green)
                        "RED" -> stringResource(R.string.bin_red)
                        "BLACK" -> stringResource(R.string.bin_black)
                        "ORANGE" -> stringResource(R.string.bin_orange)
                        "SPECIAL_DEPOT" -> stringResource(R.string.bin_special)
                        else -> "${CategoryLocalization.localizedBinColor(binColor)} Bin"
                    }

                    // Featured Target Bin Card with Real Bin Graphic and Full Bin Name
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = binBg.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, binBg.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RealBinGraphic(
                                binColor = binColor,
                                modifier = Modifier.size(width = 48.dp, height = 58.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.scan_target_bin).uppercase(Locale.US),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = binBg
                                )
                                Text(
                                    text = binFullName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = binBg
                                    ) {
                                        Text(
                                            text = CategoryLocalization.localizedBinColor(binColor),
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = "• " + (if (state.rule.isRecyclable) stringResource(R.string.scan_recyclable) else stringResource(R.string.scan_non_recyclable)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.scan_jurisdiction), style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = state.resolvedJurisdiction.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stringResource(R.string.scan_recyclable), style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = if (state.rule.isRecyclable) stringResource(R.string.scan_yes) else stringResource(R.string.scan_no),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.rule.isRecyclable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(stringResource(R.string.scan_prep_instructions), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(state.rule.preparationInstructions, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.scan_recommended_method), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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

        // 7.5 Human-in-the-Loop Feedback Card (P4)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Feedback, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.scan_feedback_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.scan_feedback_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state.feedbackSubmitted) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.scan_feedback_thank_you),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    var consentGiven by remember { mutableStateOf(false) }
                    var showWrongCategoryPicker by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onSubmitFeedback("CORRECT", null, null, consentGiven)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("✓ Correct", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                showWrongCategoryPicker = !showWrongCategoryPicker
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = if (showWrongCategoryPicker) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text("✕ Wrong", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                onSubmitFeedback("NOT_SURE", null, null, consentGiven)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("? Not sure", fontSize = 12.sp)
                        }
                    }

                    AnimatedVisibility(visible = showWrongCategoryPicker) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        ) {
                            Text(
                                text = "What is the correct category?",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val correctionCategories = listOf(
                                "biodegradable", "organic", "plastic", "paper", "cardboard",
                                "metal", "glass", "E-waste", "Battery", "trash"
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                correctionCategories.forEach { cat ->
                                    SuggestionChip(
                                        onClick = {
                                            onSelectCategory(cat, null)
                                            onSubmitFeedback("WRONG_ITEM", cat, null, consentGiven)
                                            showWrongCategoryPicker = false
                                        },
                                        label = {
                                            Text(
                                                CategoryLocalization.getCategoryDisplayName(cat, Locale.getDefault().language),
                                                fontSize = 12.sp
                                            )
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        onSubmitFeedback("WRONG_ITEM", null, null, consentGiven)
                                        showWrongCategoryPicker = false
                                    }
                                ) {
                                    Text("Just mark as wrong", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = consentGiven,
                            onCheckedChange = { consentGiven = it }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.scan_feedback_consent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                Text(stringResource(R.string.scan_ask_ai))
            }
            if (canReport) {
                Button(
                    onClick = onReportClicked,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.scan_report))
                }
            }
        }

        Button(
            onClick = onDoneClicked,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.scan_done),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}


private fun binColorFor(name: String): Color = when (name.uppercase()) {
    "BLUE" -> Color(0xFF1976D2)
    "GREEN" -> Color(0xFF388E3C)
    "RED" -> Color(0xFFD32F2F)
    "ORANGE" -> Color(0xFFF57C00)
    else -> Color(0xFF424242)
}

private fun ItemAnalysis.summaryTitle(): String =
    userConfirmedLabel
        ?: if (!identification.needsUserConfirmation) identification.coarseCategory
        else identification.specificItem
        ?: classification.topKResults.firstOrNull()?.let { "Unsure \u2014 maybe ${it.label}" }
        ?: "Unidentified item"

@Composable
private fun MiniChip(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** Compact result for ONE item: label, bin, hazard/recyclable flags, or quick-confirm chips when unsure. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemSummaryCard(
    index: Int,
    item: ItemAnalysis,
    selected: Boolean,
    onSelect: () -> Unit,
    onQuickConfirm: (String) -> Unit
) {
    val ready = !item.identification.needsUserConfirmation
    val accent = when {
        ready && item.safety.isHazardous -> MaterialTheme.colorScheme.error
        ready -> Color(0xFF2E7D32)
        else -> Color(0xFFF9A825)
    }
    val top = item.classification.topKResults.firstOrNull()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (selected) 0.8f else 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.summaryTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (ready) {
                            item.rule.disposalMethod
                        } else if (top != null) {
                            "Best guess: ${top.label} (${String.format(Locale.US, "%.0f%%", top.score * 100)}) \u2014 please confirm"
                        } else {
                            "Could not identify \u2014 please choose a category"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (ready) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MiniChip(CategoryLocalization.localizedBinColor(item.rule.targetBinColor), binColorFor(item.rule.targetBinColor), Color.White)
                }
            }

            if (ready) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.safety.isHazardous) {
                        MiniChip(
                            "\u26A0 Hazardous",
                            MaterialTheme.colorScheme.errorContainer,
                            MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    MiniChip(
                        if (item.rule.isRecyclable) "Recyclable" else "Not recyclable",
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.onSurface
                    )
                    if (item.productInfo != null) {
                        MiniChip(
                            "Barcode matched",
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else if (item.classification.topKResults.isNotEmpty()) {
                Text(
                    text = "Is it one of these?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item.classification.topKResults.take(3).forEach { c ->
                        SuggestionChip(
                            onClick = { onQuickConfirm(c.label) },
                            label = { Text("${c.label} ${String.format(Locale.US, "%.0f%%", c.score * 100)}") }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Realistic municipal segregation bin graphic with lid, handle, contoured body,
 * wheels, ground shadow, and stream-specific emblem.
 */
@Composable
fun RealBinGraphic(
    binColor: String,
    modifier: Modifier = Modifier
) {
    val (primaryColor, darkShade, lightShade) = when (binColor.uppercase(Locale.US)) {
        "ORANGE" -> Triple(Color(0xFFFF8A00), Color(0xFFD35400), Color(0xFFFFB74D))
        "BLUE" -> Triple(Color(0xFF1E88E5), Color(0xFF1565C0), Color(0xFF64B5F6))
        "GREEN" -> Triple(Color(0xFF43A047), Color(0xFF2E7D32), Color(0xFF81C784))
        "RED" -> Triple(Color(0xFFE53935), Color(0xFFC62828), Color(0xFFEF9A9A))
        else -> Triple(Color(0xFF546E7A), Color(0xFF37474F), Color(0xFF90A4AE))
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Ground shadow
        drawOval(
            color = Color.Black.copy(alpha = 0.22f),
            topLeft = Offset(w * 0.10f, h * 0.90f),
            size = Size(w * 0.80f, h * 0.08f)
        )

        // Wheels at bottom
        val wheelRadius = w * 0.11f
        val wheelY = h * 0.86f
        // Left wheel
        drawCircle(
            color = Color(0xFF212121),
            radius = wheelRadius,
            center = Offset(w * 0.22f, wheelY)
        )
        drawCircle(
            color = Color(0xFF757575),
            radius = wheelRadius * 0.45f,
            center = Offset(w * 0.22f, wheelY)
        )
        // Right wheel
        drawCircle(
            color = Color(0xFF212121),
            radius = wheelRadius,
            center = Offset(w * 0.78f, wheelY)
        )
        drawCircle(
            color = Color(0xFF757575),
            radius = wheelRadius * 0.45f,
            center = Offset(w * 0.78f, wheelY)
        )

        // Bin tapered body path
        val bodyPath = Path().apply {
            moveTo(w * 0.18f, h * 0.22f) // top left
            lineTo(w * 0.82f, h * 0.22f) // top right
            lineTo(w * 0.74f, h * 0.86f) // bottom right
            lineTo(w * 0.26f, h * 0.86f) // bottom left
            close()
        }

        // Fill body with gradient
        drawPath(
            path = bodyPath,
            brush = Brush.horizontalGradient(
                colors = listOf(darkShade, primaryColor, lightShade, primaryColor, darkShade),
                startX = w * 0.18f,
                endX = w * 0.82f
            )
        )

        // Body vertical reinforcement ribs
        val ribBrush = Brush.verticalGradient(
            colors = listOf(darkShade.copy(alpha = 0.7f), lightShade.copy(alpha = 0.3f))
        )
        drawLine(
            brush = ribBrush,
            start = Offset(w * 0.38f, h * 0.30f),
            end = Offset(w * 0.40f, h * 0.78f),
            strokeWidth = w * 0.035f,
            cap = StrokeCap.Round
        )
        drawLine(
            brush = ribBrush,
            start = Offset(w * 0.62f, h * 0.30f),
            end = Offset(w * 0.60f, h * 0.78f),
            strokeWidth = w * 0.035f,
            cap = StrokeCap.Round
        )

        // Bin Collar / Upper Rim
        val rimPath = Path().apply {
            moveTo(w * 0.14f, h * 0.19f)
            lineTo(w * 0.86f, h * 0.19f)
            lineTo(w * 0.84f, h * 0.25f)
            lineTo(w * 0.16f, h * 0.25f)
            close()
        }
        drawPath(
            path = rimPath,
            color = darkShade
        )

        // Bin Lid
        val lidPath = Path().apply {
            moveTo(w * 0.12f, h * 0.19f)
            lineTo(w * 0.88f, h * 0.19f)
            lineTo(w * 0.82f, h * 0.11f)
            lineTo(w * 0.18f, h * 0.11f)
            close()
        }
        drawPath(
            path = lidPath,
            brush = Brush.verticalGradient(
                colors = listOf(lightShade, primaryColor),
                startY = h * 0.11f,
                endY = h * 0.19f
            )
        )

        // Lid Top Handle
        drawRoundRect(
            color = darkShade,
            topLeft = Offset(w * 0.38f, h * 0.06f),
            size = Size(w * 0.24f, h * 0.06f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
        )

        // Bin Front Emblem: e.g. Recycle / E-waste symbol / icon
        val emblemCenter = Offset(w * 0.50f, h * 0.52f)
        drawCircle(
            color = Color.White.copy(alpha = 0.92f),
            radius = w * 0.14f,
            center = emblemCenter
        )

        // Symbol on emblem
        when (binColor.uppercase(Locale.US)) {
            "ORANGE" -> {
                // E-waste plug / lightning indicator
                val boltPath = Path().apply {
                    moveTo(w * 0.52f, h * 0.43f)
                    lineTo(w * 0.44f, h * 0.52f)
                    lineTo(w * 0.50f, h * 0.52f)
                    lineTo(w * 0.48f, h * 0.61f)
                    lineTo(w * 0.56f, h * 0.51f)
                    lineTo(w * 0.50f, h * 0.51f)
                    close()
                }
                drawPath(path = boltPath, color = darkShade)
            }
            "GREEN" -> {
                // Organic leaf indicator
                drawOval(
                    color = darkShade,
                    topLeft = Offset(w * 0.44f, h * 0.46f),
                    size = Size(w * 0.12f, h * 0.12f)
                )
            }
            "RED" -> {
                // Hazard warning exclamation mark
                drawLine(
                    color = darkShade,
                    start = Offset(w * 0.50f, h * 0.45f),
                    end = Offset(w * 0.50f, h * 0.54f),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = darkShade,
                    radius = w * 0.02f,
                    center = Offset(w * 0.50f, h * 0.58f)
                )
            }
            else -> {
                // 3-point cycle indicator dots
                drawCircle(color = darkShade, radius = w * 0.025f, center = Offset(w * 0.50f, h * 0.46f))
                drawCircle(color = darkShade, radius = w * 0.025f, center = Offset(w * 0.44f, h * 0.56f))
                drawCircle(color = darkShade, radius = w * 0.025f, center = Offset(w * 0.56f, h * 0.56f))
            }
        }
    }
}
