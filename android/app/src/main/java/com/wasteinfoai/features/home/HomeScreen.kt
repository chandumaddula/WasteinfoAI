package com.wasteinfoai.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasteinfoai.core.theme.EmeraldPrimary
import com.wasteinfoai.core.theme.MintAccent
import com.wasteinfoai.data.local.entity.ScanHistoryEntity
import java.text.SimpleDateFormat
import java.util.*

import com.wasteinfoai.core.navigation.Screen
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.features.common.DashboardSideDrawer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    sessionManager: SessionManager,
    onNavigateToCamera: () -> Unit,
    onNavigateToCreateReport: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToMap: () -> Unit,
    onNavigateToAssistant: () -> Unit,
    onNavigateToTasks: () -> Unit,
    onNavigateToReviewer: () -> Unit,
    onNavigateToAdmin: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToCategorySelection: () -> Unit = {},
    onNavigateToIndustry: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    DashboardSideDrawer(
        drawerState = drawerState,
        currentRoute = Screen.Home.route,
        sessionManager = sessionManager,
        onNavigate = { route ->
            when (route) {
                Screen.Home.route -> {}
                Screen.IndustryDashboard.route -> onNavigateToIndustry?.invoke()
                Screen.Tasks.route -> onNavigateToTasks()
                Screen.ReviewerDashboard.route -> onNavigateToReviewer()
                Screen.AdminDashboard.route -> onNavigateToAdmin()
                Screen.Camera.route -> onNavigateToCamera()
                Screen.History.route -> onNavigateToHistory()
                Screen.Map.route -> onNavigateToMap()
                Screen.CategorySelection.route -> onNavigateToCategorySelection()
                else -> {
                    if (route.startsWith("report/create")) onNavigateToCreateReport()
                    else if (route.startsWith("assistant")) onNavigateToAssistant()
                }
            }
        },
        onLogout = { onLogout?.invoke() ?: onNavigateToLogin() }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Side Panel Navigation")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Recycling,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("wasteinfoAI", fontWeight = FontWeight.Bold)
                        }
                    },
                actions = {
                    // Role Badge (Static indicator)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = state.userCategory.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToLogin) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Hero Action Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "AI Waste Scanner",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "On-device ReXNet-150 classification & offline disposal guidance with zero cloud dependency.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToCamera,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = EmeraldPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Scan Waste Item", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Offline Sync Banner (if pending)
            if (state.pendingSyncCount > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Sync, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "${state.pendingSyncCount} report(s) pending sync",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            TextButton(onClick = { viewModel.triggerSync() }) {
                                Text("Sync Now")
                            }
                        }
                    }
                }
            }

            // Quick Navigation Grid
            item {
                Text(
                    "Quick Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionCard(
                        title = "Report Hotspot",
                        icon = Icons.Default.AddLocationAlt,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToCreateReport
                    )
                    QuickActionCard(
                        title = "Hotspot Map",
                        icon = Icons.Default.Map,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToMap
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionCard(
                        title = "Bottle AI",
                        icon = Icons.Default.Psychology,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToAssistant
                    )
                    QuickActionCard(
                        title = "Scan History",
                        icon = Icons.Default.History,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToHistory
                    )
                }
            }

            // Role-specific Shortcuts (Only for staff / admin roles)
            if (state.userRole != "RESIDENT" && state.userRole in listOf("SANITATION_WORKER", "REVIEWER", "ADMIN")) {
                item {
                    Text(
                        "Administrative Shortcuts",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.userRole in listOf("SANITATION_WORKER", "ADMIN")) {
                            RoleDashboardRow(
                                title = "Sanitation Worker Tasks",
                                icon = Icons.Default.Assignment,
                                subtitle = "View assigned cleanups & update status",
                                onClick = onNavigateToTasks
                            )
                        }
                        if (state.userRole in listOf("REVIEWER", "ADMIN")) {
                            RoleDashboardRow(
                                title = "Reviewer Verification Dashboard",
                                icon = Icons.Default.RateReview,
                                subtitle = "Verify, reject, or duplicate citizen reports",
                                onClick = onNavigateToReviewer
                            )
                        }
                        if (state.userRole == "ADMIN") {
                            RoleDashboardRow(
                                title = "Administrator Operational Overview",
                                icon = Icons.Default.AdminPanelSettings,
                                subtitle = "Full system analytics & management",
                                onClick = onNavigateToAdmin
                            )
                        }
                    }
                }
            }

            // Recent Scans
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Scans",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.recentScans.isNotEmpty()) {
                        TextButton(onClick = onNavigateToHistory) {
                            Text("See All")
                        }
                    }
                }
            }

            if (state.recentScans.isEmpty()) {
                item {
                    Text(
                        "No waste scans recorded yet. Tap 'Scan Waste Item' to test on-device inference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(state.recentScans) { scan ->
                    RecentScanCard(scan = scan)
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    }
}

@Composable
fun QuickActionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun RoleDashboardRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
fun RecentScanCard(scan: ScanHistoryEntity) {
    val dateStr = remember(scan.timestamp) {
        SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(scan.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (scan.isHazardous) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (scan.isHazardous) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (scan.isHazardous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        scan.category,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "$dateStr • ${String.format("%.1f%%", scan.confidence * 100)} conf",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (scan.targetBinColor != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = scan.targetBinColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
