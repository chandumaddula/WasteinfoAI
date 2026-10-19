package com.wasteinfoai.features.dashboards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.wasteinfoai.core.navigation.Screen
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.features.common.DashboardSideDrawer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AdminDashboardViewModel,
    sessionManager: SessionManager? = null,
    onNavigateBack: () -> Unit,
    onNavigateToCategorySelection: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Admin Operations") },
                    navigationIcon = {
                        if (sessionManager != null) {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Side Panel Navigation")
                            }
                        } else {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                actions = {
                    if (onNavigateToCategorySelection != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable { onNavigateToCategorySelection() }
                                .padding(end = 4.dp)
                        ) {
                            Text(
                                text = "OTHER / ADMIN",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.loadMetrics() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val m = uiState.metrics
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "System Overview",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Metric Cards Grid
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricCard(
                            title = "Total Reports",
                            value = m.totalReports.toString(),
                            icon = Icons.Default.Assessment,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            title = "Under Review",
                            value = m.underReviewCount.toString(),
                            icon = Icons.Default.Pending,
                            color = Color(0xFFF57C00),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        MetricCard(
                            title = "Assigned",
                            value = m.assignedCount.toString(),
                            icon = Icons.Default.AssignmentInd,
                            color = Color(0xFF1976D2),
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            title = "Cleaned",
                            value = m.resolvedCount.toString(),
                            icon = Icons.Default.CheckCircle,
                            color = Color(0xFF388E3C),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Top Waste Categories
                if (m.topCategories.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "Most Frequently Reported Categories",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                m.topCategories.forEach { (cat, count) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(cat, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "$count reports",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { if (m.totalReports > 0) count.toFloat() / m.totalReports else 0f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp),
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    item {
                        Text(
                            text = "Backend status: ${uiState.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    }

    if (sessionManager != null) {
        DashboardSideDrawer(
            drawerState = drawerState,
            currentRoute = Screen.AdminDashboard.route,
            sessionManager = sessionManager,
            onNavigate = { route ->
                when (route) {
                    Screen.AdminDashboard.route -> {}
                    Screen.CategorySelection.route -> onNavigateToCategorySelection?.invoke()
                    else -> onNavigateBack()
                }
            },
            onLogout = onNavigateBack,
            content = content
        )
    } else {
        content()
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
