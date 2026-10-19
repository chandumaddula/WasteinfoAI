package com.wasteinfoai.features.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasteinfoai.core.navigation.Screen
import com.wasteinfoai.core.theme.EmeraldDark
import com.wasteinfoai.core.theme.EmeraldPrimary
import com.wasteinfoai.core.theme.MintAccent
import com.wasteinfoai.data.remote.SessionManager
import kotlinx.coroutines.launch

@Composable
fun DashboardSideDrawer(
    drawerState: DrawerState,
    currentRoute: String,
    sessionManager: SessionManager,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showServerDialog by remember { mutableStateOf(false) }

    val userRole by sessionManager.roleState.collectAsState()
    val userCategory by sessionManager.categoryState.collectAsState()
    val userEmail by sessionManager.emailState.collectAsState()
    val serverUrl by sessionManager.serverUrlState.collectAsState()

    if (showServerDialog) {
        ServerConfigDialog(
            sessionManager = sessionManager,
            onDismissRequest = { showServerDialog = false }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerTonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header Banner with Gradient
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(EmeraldDark, Color(0xFF00382E))
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(44.dp),
                                    shape = CircleShape,
                                    color = MintAccent
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Recycling,
                                            contentDescription = null,
                                            tint = EmeraldDark,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "WasteinfoAI",
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.5).sp
                                        ),
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Intelligent Segregation",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MintAccent.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // User Profile Info Box
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (userEmail.isNotBlank()) userEmail else "Guest / Offline Account",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MintAccent.copy(alpha = 0.25f)
                                        ) {
                                            Text(
                                                text = userCategory.uppercase(),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MintAccent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color.White.copy(alpha = 0.18f)
                                        ) {
                                            Text(
                                                text = userRole,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.9f),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Active Workspace Dashboard
                    DrawerSectionHeader("WORKSPACE DASHBOARD")

                    val normCat = userCategory.lowercase()
                    val normRole = userRole.uppercase()

                    val isHousehold = normCat.contains("household") || normRole == "RESIDENT"
                    val isWorker = normCat.contains("worker") || normCat.contains("sanitation") || normRole == "SANITATION_WORKER"
                    val isIndustry = normCat.contains("industr") || normRole == "INSTITUTION"
                    val isReviewer = normCat.contains("reviewer") || normCat.contains("other") || normRole == "REVIEWER"
                    val isAdmin = normRole == "ADMIN"

                    if (isHousehold) {
                        DrawerItem(
                            icon = Icons.Default.Home,
                            label = "Households Dashboard",
                            isSelected = currentRoute == Screen.Home.route,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(Screen.Home.route)
                            }
                        )
                    }

                    if (isIndustry) {
                        DrawerItem(
                            icon = Icons.Default.Factory,
                            label = "Industrial Portal",
                            isSelected = currentRoute == Screen.IndustryDashboard.route,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(Screen.IndustryDashboard.route)
                            }
                        )
                    }

                    if (isWorker) {
                        DrawerItem(
                            icon = Icons.Default.Engineering,
                            label = "Municipal Worker Tasks",
                            isSelected = currentRoute == Screen.Tasks.route,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(Screen.Tasks.route)
                            }
                        )
                    }

                    if (isReviewer) {
                        DrawerItem(
                            icon = Icons.Default.Shield,
                            label = "Reviewer & Audit Portal",
                            isSelected = currentRoute == Screen.ReviewerDashboard.route,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(Screen.ReviewerDashboard.route)
                            }
                        )
                    }

                    if (isAdmin) {
                        DrawerItem(
                            icon = Icons.Default.AdminPanelSettings,
                            label = "City Administrator",
                            isSelected = currentRoute == Screen.AdminDashboard.route,
                            onClick = {
                                coroutineScope.launch { drawerState.close() }
                                onNavigate(Screen.AdminDashboard.route)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))

                    // Core AI Tools
                    DrawerSectionHeader("ENVIRONMENTAL TOOLS")

                    DrawerItem(
                        icon = Icons.Default.CameraAlt,
                        label = "AI Waste Scanner",
                        isSelected = currentRoute == Screen.Camera.route,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.Camera.route)
                        }
                    )

                    DrawerItem(
                        icon = Icons.Default.Report,
                        label = "Report Waste Incident",
                        isSelected = currentRoute.startsWith("report/create"),
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.CreateReport.createRoute())
                        }
                    )

                    DrawerItem(
                        icon = Icons.Default.Map,
                        label = "Eco Map & Cleanups",
                        isSelected = currentRoute == Screen.Map.route,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.Map.route)
                        }
                    )

                    DrawerItem(
                        icon = Icons.Default.Psychology,
                        label = "AI Eco-Assistant",
                        isSelected = currentRoute.startsWith("assistant"),
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.Assistant.createRoute())
                        }
                    )

                    DrawerItem(
                        icon = Icons.Default.History,
                        label = "Scan & Rule History",
                        isSelected = currentRoute == Screen.History.route,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.History.route)
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))

                    // System & Settings
                    DrawerSectionHeader("SYSTEM & NETWORK")

                    DrawerItem(
                        icon = Icons.Default.AccountCircle,
                        label = "User Profile",
                        isSelected = currentRoute == Screen.Login.route,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onNavigate(Screen.Login.route)
                        }
                    )

                    DrawerItem(
                        icon = Icons.Default.Public,
                        label = "Server & Internet Settings",
                        subtitle = if (serverUrl.contains("10.0.2.2")) "Emulator (10.0.2.2)" else serverUrl,
                        isSelected = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            showServerDialog = true
                        }
                    )

                    DrawerItem(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        label = "Log Out",
                        isSelected = false,
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            onLogout()
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        },
        content = content
    )
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun DrawerItem(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    isSelected: Boolean,
    iconTint: Color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
