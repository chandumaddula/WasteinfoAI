package com.wasteinfoai.features.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.wasteinfoai.features.onboarding.UserCategory
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    userCategory: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToCreateReport: () -> Unit
) {
    val canAccessMap = UserCategory.isWasteLocationCategory(userCategory)

    if (!canAccessMap) {
        RestrictedMapScreen(
            userCategory = userCategory,
            onNavigateBack = onNavigateBack
        )
        return
    }

    val uiState by viewModel.uiState.collectAsState()
    val filterOptions = listOf("ALL", "UNDER_REVIEW", "VERIFIED", "ASSIGNED", "RESOLVED")
    var isMapView by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sanitization & Hotspot Map") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { isMapView = !isMapView }) {
                        Icon(
                            if (isMapView) Icons.Default.ViewList else Icons.Default.Map,
                            contentDescription = if (isMapView) "List View" else "Map View"
                        )
                    }
                    IconButton(onClick = { viewModel.loadHotspots() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreateReport,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.AddLocationAlt, contentDescription = "Report Waste Hotspot")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterOptions) { filter ->
                    FilterChip(
                        selected = uiState.selectedFilter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(filter.replace("_", " ")) }
                    )
                }
            }

            // Offline / Status Banner
            if (uiState.isOffline) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Offline Mode: Showing cached report locations and offline map tiles",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (isMapView) {
                // Real-world OpenStreetMap View
                RealWorldOsmMap(
                    hotspots = uiState.hotspots,
                    selectedHotspot = uiState.selectedHotspot,
                    onSelectHotspot = { viewModel.selectHotspot(it) },
                    onClearSelection = { viewModel.selectHotspot(null) }
                )
            } else {
                // List View
                if (uiState.hotspots.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LocationOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No verified hotspots found", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Hotspots with valid coordinates will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.hotspots, key = { it.id }) { hotspot ->
                            HotspotCard(
                                hotspot = hotspot,
                                isSelected = uiState.selectedHotspot?.id == hotspot.id,
                                onClick = {
                                    viewModel.selectHotspot(if (uiState.selectedHotspot?.id == hotspot.id) null else hotspot)
                                }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(72.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RealWorldOsmMap(
    hotspots: List<MapHotspot>,
    selectedHotspot: MapHotspot?,
    onSelectHotspot: (MapHotspot) -> Unit,
    onClearSelection: () -> Unit
) {
    val context = LocalContext.current
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                Configuration.getInstance().userAgentValue = ctx.packageName
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    controller.setZoom(13.0)

                    val centerPoint = if (hotspots.isNotEmpty()) {
                        GeoPoint(hotspots.first().coordinates.latitude, hotspots.first().coordinates.longitude)
                    } else {
                        GeoPoint(17.3850, 78.4867) // Default center (Hyderabad, India)
                    }
                    controller.setCenter(centerPoint)
                    mapViewInstance = this
                }
            },
            update = { mapView ->
                mapView.overlays.clear()
                hotspots.forEach { hotspot ->
                    val marker = Marker(mapView)
                    marker.position = GeoPoint(hotspot.coordinates.latitude, hotspot.coordinates.longitude)
                    marker.title = "${hotspot.category} (${hotspot.status})"
                    marker.snippet = hotspot.description
                    marker.setOnMarkerClickListener { _, _ ->
                        onSelectHotspot(hotspot)
                        true
                    }
                    mapView.overlays.add(marker)
                }

                selectedHotspot?.let { sel ->
                    mapView.controller.animateTo(GeoPoint(sel.coordinates.latitude, sel.coordinates.longitude))
                }
                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Selected Hotspot Sanitation & Details Bottom Card
        selectedHotspot?.let { hotspot ->
            val statusColor = when (hotspot.status) {
                "RESOLVED" -> MaterialTheme.colorScheme.primary
                "ASSIGNED" -> Color(0xFF1976D2)
                "VERIFIED" -> Color(0xFFF57C00)
                else -> Color(0xFFD32F2F)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                hotspot.category,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = onClearSelection, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "Status: ${hotspot.status.replace("_", " ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(hotspot.description, style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Coordinates: ${String.format("%.4f, %.4f", hotspot.coordinates.latitude, hotspot.coordinates.longitude)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    // Sanitization guidance banner
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Sanitization Action: Flagged for community clean-up drive and area decontamination.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestrictedMapScreen(
    userCategory: String?,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sanitization & Hotspot Map") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Sanitization Map Restricted",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Real-world hotspot mapping and waste accumulation tracking are designated specifically for Community Recycling and Plastic Waste Reduction coordinators to monitor waste accumulation and sanitize affected areas.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!userCategory.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Current role: $userCategory",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Return to Dashboard")
                    }
                }
            }
        }
    }
}

@Composable
fun HotspotCard(
    hotspot: MapHotspot,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val statusColor = when (hotspot.status) {
        "RESOLVED" -> MaterialTheme.colorScheme.primary
        "ASSIGNED" -> Color(0xFF1976D2)
        "VERIFIED" -> Color(0xFFF57C00)
        else -> Color(0xFFD32F2F)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        hotspot.category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = hotspot.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(hotspot.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Coordinates: ${String.format("%.4f, %.4f", hotspot.coordinates.latitude, hotspot.coordinates.longitude)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
