package com.wasteinfoai.features.tasks

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wasteinfoai.data.local.entity.TaskEntity

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import com.wasteinfoai.core.navigation.Screen
import com.wasteinfoai.data.remote.SessionManager
import com.wasteinfoai.features.common.DashboardSideDrawer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerTasksScreen(
    viewModel: WorkerTasksViewModel,
    sessionManager: SessionManager? = null,
    onNavigateBack: () -> Unit,
    onNavigateToCamera: (() -> Unit)? = null,
    onNavigateToMap: (() -> Unit)? = null,
    onNavigateToCategorySelection: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null
) {
    val tasks by viewModel.tasksFlow.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var taskToResolve by remember { mutableStateOf<TaskEntity?>(null) }
    var resolveNote by remember { mutableStateOf("") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Municipal Tasks", fontWeight = FontWeight.Bold)
                        }
                    },
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
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "MUNICIPAL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.refreshTasks() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Quick Operational Shortcuts Row
            if (onNavigateToCamera != null || onNavigateToMap != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (onNavigateToCamera != null) {
                        OutlinedButton(
                            onClick = onNavigateToCamera,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Waste")
                        }
                    }
                    if (onNavigateToMap != null) {
                        Button(
                            onClick = onNavigateToMap,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Hotspot Map")
                        }
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No pending assigned tasks", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Cleanups assigned by municipal reviewers will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (task.status == "RESOLVED") MaterialTheme.colorScheme.primaryContainer
                                                    else MaterialTheme.colorScheme.secondaryContainer
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (task.status == "RESOLVED") Icons.Default.Check else Icons.Default.CleaningServices,
                                                contentDescription = null,
                                                tint = if (task.status == "RESOLVED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(task.wasteCategory, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = task.status,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(task.description, style = MaterialTheme.typography.bodyMedium)

                                if (task.latitude != null && task.longitude != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "GPS: ${String.format("%.4f, %.4f", task.latitude, task.longitude)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else if (!task.manualLocation.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        "Location: ${task.manualLocation}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (task.status != "RESOLVED") {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            taskToResolve = task
                                            resolveNote = ""
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Mark as Cleaned / Resolved")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (taskToResolve != null) {
        AlertDialog(
            onDismissRequest = { taskToResolve = null },
            title = { Text("Resolve Sanitation Task") },
            text = {
                Column {
                    Text("Confirm cleanup completion for ${taskToResolve!!.wasteCategory}.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resolveNote,
                        onValueChange = { resolveNote = it },
                        label = { Text("Resolution Note (Optional)") },
                        placeholder = { Text("E.g., Site cleared and waste deposited at sorting bin.") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val id = taskToResolve!!.id
                    viewModel.resolveTask(id, resolveNote.ifBlank { "Cleaned by sanitation worker" })
                    taskToResolve = null
                }) {
                    Text("Confirm Resolution")
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToResolve = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    }

    if (sessionManager != null) {
        DashboardSideDrawer(
            drawerState = drawerState,
            currentRoute = Screen.Tasks.route,
            sessionManager = sessionManager,
            onNavigate = { route ->
                when (route) {
                    Screen.Tasks.route -> {}
                    Screen.Camera.route -> onNavigateToCamera?.invoke()
                    Screen.Map.route -> onNavigateToMap?.invoke()
                    Screen.CategorySelection.route -> onNavigateToCategorySelection?.invoke()
                    else -> onNavigateBack()
                }
            },
            onLogout = { onLogout?.invoke() ?: onNavigateBack() },
            content = content
        )
    } else {
        content()
    }
}
