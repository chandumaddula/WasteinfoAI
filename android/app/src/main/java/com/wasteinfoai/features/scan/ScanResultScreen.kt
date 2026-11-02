package com.wasteinfoai.features.scan

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
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
                            if (current.items.size > 1) viewModel.speakAllItems() else viewModel.speakGuidance()
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
                        if (state.items.size > 1) {
                            state.items.forEachIndexed { idx, item ->
                                val b = item.source?.boundingBox ?: return@forEachIndexed
                                val isSelected = idx == state.selectedObjectIndex
                                val ready = !item.identification.needsUserConfirmation
                                val tint = when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    ready -> Color(0xFF2E7D32)
                                    else -> Color(0xFFF9A825)
                                }
                                Box(
                                    modifier = Modifier
                                        .offset(x = boxW * b.left, y = boxH * b.top)
                                        .size(width = boxW * (b.right - b.left), height = boxH * (b.bottom - b.top))
                                        .border(if (isSelected) 3.dp else 2.dp, tint, RoundedCornerShape(6.dp))
                                        .clickable { onSelectObject(idx) }
                                ) {
                                    Surface(color = tint, shape = RoundedCornerShape(bottomEnd = 6.dp)) {
                                        Text(
                                            text = "${idx + 1}",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
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

        // 2. Primary Identified Category / Rejection Guidance Card
        val isRejected = !state.classification.isAccepted && !state.isUserConfirmed
        val policy = state.classification.policyResult
        val rawDisplayTitle = state.userConfirmedLabel
            ?: state.hierarchicalIdentification?.specificItem
            ?: if (isRejected) stringResource(R.string.scan_no_clear_waste) else state.classification.label
        val displayTitle = if (isRejected) rawDisplayTitle else CategoryLocalization.localizedCategory(rawDisplayTitle)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
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
                                    isRejected -> MaterialTheme.colorScheme.outline
                                    state.safety.isHazardous -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isRejected -> Icons.Default.HelpOutline
                                state.safety.isHazardous -> Icons.Default.Warning
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
                        val sourceText = when {
                            isRejected -> stringResource(R.string.scan_status_prefix, state.classification.acceptanceState.name.replace('_', ' '))
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.BARCODE_LOOKUP -> stringResource(R.string.scan_source_barcode)
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.USER_CONFIRMED -> stringResource(R.string.scan_source_user_confirmed)
                            state.hierarchicalIdentification?.identificationSource == IdentificationSource.OBJECT_CROP_CLASSIFIER -> stringResource(R.string.scan_source_roi_classifier)
                            else -> stringResource(R.string.scan_source_on_device)
                        }
                        Text(
                            text = sourceText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isRejected) {
                                policy?.diagnosticDetails ?: stringResource(R.string.scan_rejected_default)
                            } else if (state.safety.isHazardous) {
                                stringResource(R.string.scan_hazardous_warning)
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
                        Text(
                            text = policy?.userFacingMessage
                                ?: "No clear waste item detected. Please position the item inside the frame and try again.",
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(stringResource(R.string.scan_target_bin), style = MaterialTheme.typography.labelSmall)
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
                                    text = CategoryLocalization.localizedBinColor(binColor),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Column {
                            Text(stringResource(R.string.scan_recyclable), style = MaterialTheme.typography.labelSmall)
                            Text(
                                if (state.rule.isRecyclable) stringResource(R.string.scan_yes) else stringResource(R.string.scan_no),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text(stringResource(R.string.scan_jurisdiction), style = MaterialTheme.typography.labelSmall)
                            Text(state.resolvedJurisdiction.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                                showCategorySelector = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
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

        Button(
            onClick = onDoneClicked,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(stringResource(R.string.scan_done), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        ?: identification.specificItem
        ?: if (!identification.needsUserConfirmation) identification.coarseCategory
        else classification.topKResults.firstOrNull()?.let { "Unsure \u2014 maybe ${it.label}" }
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
