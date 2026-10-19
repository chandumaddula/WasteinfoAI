package com.wasteinfoai.camera

import android.view.Surface
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import java.util.concurrent.Executors
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wasteinfoai.R
import com.wasteinfoai.core.permissions.CameraPermissionStatus
import com.wasteinfoai.core.permissions.rememberCameraPermissionState
import com.wasteinfoai.core.util.openAppSettings
import com.wasteinfoai.core.util.rememberHostLifecycleOwner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point of the capture flow: live preview -> capture or gallery -> review -> confirm.
 *
 * [onImageConfirmed] is the hand-off to the future classifier; it is invoked once with a validated
 * image whose file the receiver then owns. [onClose] leaves the flow (e.g. pops the back stack).
 */
@Composable
fun CameraRoute(
    onImageConfirmed: ConfirmedImageHandler,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CameraViewModel = viewModel(factory = CameraViewModelFactory.create(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsState()
    val picker = rememberImagePicker(viewModel::onImagePicked)
    val permission = rememberCameraPermissionState()
    val context = LocalContext.current

    // Ask once, automatically, the first time the live camera is needed.
    LaunchedEffect(permission.status, permission.hasRequested) {
        if (permission.status == CameraPermissionStatus.CAN_REQUEST && !permission.hasRequested) permission.request()
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        when (val s = state) {
            CameraUiState.CameraReady, CameraUiState.Capturing -> {
                if (permission.status == CameraPermissionStatus.GRANTED) {
                    CameraPreviewContent(
                        isCapturing = s == CameraUiState.Capturing,
                        beginCapture = viewModel::beginCapture,
                        onSaved = viewModel::onCaptureSaved,
                        onFailed = viewModel::onCaptureFailed,
                        onCameraUnavailable = viewModel::onCameraUnavailable,
                        onChooseFromGallery = picker::launch,
                        onClose = onClose,
                    )
                } else {
                    PermissionContent(
                        permanentlyDenied = permission.status == CameraPermissionStatus.PERMANENTLY_DENIED,
                        onRequest = permission::request,
                        onOpenSettings = context::openAppSettings,
                        onChooseFromGallery = picker::launch,
                        onClose = onClose,
                    )
                }
            }
            CameraUiState.LoadingImage -> LoadingContent()
            is CameraUiState.ImageSelected -> ImageReviewContent(
                image = s.image,
                onRetake = viewModel::retake,
                onChooseAnother = picker::launch,
                onConfirm = { viewModel.confirm()?.let(onImageConfirmed::onImageConfirmed) },
            )
            is CameraUiState.Failed -> ErrorContent(
                problem = s.problem,
                onTryAgain = viewModel::dismissError,
                onChooseFromGallery = picker::launch,
                onClose = onClose,
            )
            is CameraUiState.Confirmed -> ConfirmedContent(onScanAnother = viewModel::startOver, onClose = onClose)
        }
    }
}

// ------------------------------------------------------------------------------ live camera
@Composable
private fun CameraPreviewContent(
    isCapturing: Boolean,
    beginCapture: () -> File?,
    onSaved: (File) -> Unit,
    onFailed: (File?) -> Unit,
    onCameraUnavailable: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = rememberHostLifecycleOwner()
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var cameraBound by remember { mutableStateOf(false) }
    val currentOnUnavailable by rememberUpdatedState(onCameraUnavailable)

    val detector = remember { OpencvObjectDetector() }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var detectedBox by remember {
        mutableStateOf(
            DetectedObjectBox(
                left = 0.18f,
                top = 0.22f,
                right = 0.82f,
                bottom = 0.68f,
                hasTarget = false,
                label = "SCANNING OBJECT..."
            )
        )
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .apply {
                setAnalyzer(analyzerExecutor) { imageProxy ->
                    try {
                        val result = detector.analyzeFrame(imageProxy)
                        previewView.post {
                            detectedBox = result
                        }
                    } catch (e: Exception) {
                        // ignore dropped frames
                    } finally {
                        imageProxy.close()
                    }
                }
            }
    }

    // Binds Preview + ImageCapture + ImageAnalysis to the host lifecycle and releases the camera when this
    // composable leaves the composition (review screen, error, navigation away).
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner
        if (owner == null) {
            currentOnUnavailable()
            return@DisposableEffect onDispose { }
        }
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (disposed) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val selector = when {
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> {
                            currentOnUnavailable()
                            return@addListener
                        }
                    }
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(owner, selector, preview, imageCapture, imageAnalysis)
                    cameraBound = true
                } catch (e: Exception) {
                    cameraBound = false
                    currentOnUnavailable()
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed = true
            cameraBound = false
            provider?.unbindAll()
            analyzerExecutor.shutdown()
        }
    }

    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val onShutter: () -> Unit = {
        // beginCapture() returns null when a capture is already running, so rapid taps are ignored.
        val file = beginCapture()
        if (file != null) {
            try {
                imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved(file)
                        override fun onError(exception: ImageCaptureException) = onFailed(file)
                    },
                )
            } catch (e: Exception) {
                onFailed(file)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Real-time OpenCV indication Green color bounded box on the object
        ObjectBoundingBoxOverlay(
            box = detectedBox,
            isCapturing = isCapturing,
            modifier = Modifier.fillMaxSize()
        )

        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
        ) { Text(stringResource(R.string.camera_close), color = Color.White) }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                OutlinedButton(onClick = onChooseFromGallery, enabled = !isCapturing) {
                    Text(stringResource(R.string.camera_gallery), color = Color.White)
                }
            }
            CaptureButton(enabled = cameraBound && !isCapturing, loading = isCapturing, onClick = onShutter)
            Box(Modifier.weight(1f))
        }
    }
}

/**
 * Green color bounded box overlay on the scanned object powered by OpenCV analysis.
 * Displays high-visibility green corner reticles, boundary outline, animated laser scanning beam,
 * and an OpenCV object detection HUD status badge.
 */
@Composable
private fun ObjectBoundingBoxOverlay(
    box: DetectedObjectBox,
    isCapturing: Boolean,
    modifier: Modifier = Modifier
) {
    val animLeft by animateFloatAsState(targetValue = box.left, animationSpec = tween(120), label = "left")
    val animTop by animateFloatAsState(targetValue = box.top, animationSpec = tween(120), label = "top")
    val animRight by animateFloatAsState(targetValue = box.right, animationSpec = tween(120), label = "right")
    val animBottom by animateFloatAsState(targetValue = box.bottom, animationSpec = tween(120), label = "bottom")

    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanProgress"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val primaryGreen = Color(0xFF00E676)
    val cornerGreen = if (isCapturing) Color(0xFFB9F6CA) else Color(0xFF00E676)

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()

        val leftPx = animLeft * widthPx
        val topPx = animTop * heightPx
        val rightPx = animRight * widthPx
        val bottomPx = animBottom * heightPx

        val boxWidth = (rightPx - leftPx).coerceAtLeast(10f)
        val boxHeight = (bottomPx - topPx).coerceAtLeast(10f)

        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Subtle green translucent background fill inside the bounded box
            drawRect(
                color = primaryGreen.copy(alpha = if (box.hasTarget) 0.08f else 0.04f),
                topLeft = Offset(leftPx, topPx),
                size = Size(boxWidth, boxHeight)
            )

            // 2. Continuous green boundary rectangle
            drawRect(
                color = primaryGreen.copy(alpha = 0.65f * pulseAlpha),
                topLeft = Offset(leftPx, topPx),
                size = Size(boxWidth, boxHeight),
                style = Stroke(width = 2.dp.toPx())
            )

            // 3. Prominent green corner targeting brackets
            val cornerLength = 26.dp.toPx().coerceAtMost(minOf(boxWidth, boxHeight) * 0.35f)
            val cornerStroke = 4.dp.toPx()

            // Top-Left corner
            drawLine(cornerGreen, Offset(leftPx, topPx), Offset(leftPx + cornerLength, topPx), strokeWidth = cornerStroke)
            drawLine(cornerGreen, Offset(leftPx, topPx), Offset(leftPx, topPx + cornerLength), strokeWidth = cornerStroke)

            // Top-Right corner
            drawLine(cornerGreen, Offset(rightPx, topPx), Offset(rightPx - cornerLength, topPx), strokeWidth = cornerStroke)
            drawLine(cornerGreen, Offset(rightPx, topPx), Offset(rightPx, topPx + cornerLength), strokeWidth = cornerStroke)

            // Bottom-Left corner
            drawLine(cornerGreen, Offset(leftPx, bottomPx), Offset(leftPx + cornerLength, bottomPx), strokeWidth = cornerStroke)
            drawLine(cornerGreen, Offset(leftPx, bottomPx), Offset(leftPx, bottomPx - cornerLength), strokeWidth = cornerStroke)

            // Bottom-Right corner
            drawLine(cornerGreen, Offset(rightPx, bottomPx), Offset(rightPx - cornerLength, bottomPx), strokeWidth = cornerStroke)
            drawLine(cornerGreen, Offset(rightPx, bottomPx), Offset(rightPx, bottomPx - cornerLength), strokeWidth = cornerStroke)

            // 4. Center reticle crosshair
            val cx = leftPx + boxWidth / 2f
            val cy = topPx + boxHeight / 2f
            val chLength = 8.dp.toPx()
            drawLine(primaryGreen.copy(alpha = 0.7f), Offset(cx - chLength, cy), Offset(cx + chLength, cy), strokeWidth = 1.5.dp.toPx())
            drawLine(primaryGreen.copy(alpha = 0.7f), Offset(cx, cy - chLength), Offset(cx, cy + chLength), strokeWidth = 1.5.dp.toPx())

            // 5. Active scanning laser beam across the bounding box
            val scanY = topPx + boxHeight * scanProgress
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primaryGreen.copy(alpha = 0f),
                        primaryGreen.copy(alpha = 0.9f),
                        primaryGreen.copy(alpha = 0f)
                    ),
                    startX = leftPx,
                    endX = rightPx
                ),
                start = Offset(leftPx, scanY),
                end = Offset(rightPx, scanY),
                strokeWidth = 2.5.dp.toPx()
            )
        }

        // 6. Modern HUD indicator badge over the bounded box
        val density = LocalDensity.current
        val badgeX = with(density) { leftPx.toDp() }
        val badgeY = with(density) { (topPx - 32.dp.toPx()).coerceAtLeast(16.dp.toPx()).toDp() }

        Box(
            modifier = Modifier
                .offset(x = badgeX, y = badgeY)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                .border(1.dp, primaryGreen.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (box.hasTarget) primaryGreen else Color(0xFFFFB300))
                )
                Text(
                    text = if (isCapturing) "CAPTURING OBJECT..." else "OPENCV: ${box.label}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}


@Composable
private fun CaptureButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.camera_capture)
    Box(
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(if (enabled || loading) Color.White else Color.White.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
    }
}

// ------------------------------------------------------------------------------ permission
@Composable
private fun PermissionContent(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onClose: () -> Unit,
) {
    CenteredColumn {
        Text(stringResource(R.string.camera_permission_title), color = Color.White)
        Text(
            stringResource(
                if (permanentlyDenied) R.string.camera_permission_denied_permanently
                else R.string.camera_permission_rationale,
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.camera_permission_open_settings)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.camera_permission_grant)) }
        }
        OutlinedButton(onClick = onChooseFromGallery) {
            Text(stringResource(R.string.camera_choose_from_gallery), color = Color.White)
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.camera_close), color = Color.White) }
    }
}

// ------------------------------------------------------------------------------ review
private sealed interface PreviewResult {
    object Loading : PreviewResult
    class Ready(val bitmap: ImageBitmap) : PreviewResult
    object Failed : PreviewResult
}

@Composable
private fun ImageReviewContent(
    image: ValidatedImage,
    onRetake: () -> Unit,
    onChooseAnother: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Decoding happens on a background dispatcher at preview resolution, never on the UI thread.
    var preview by remember(image.file) { mutableStateOf<PreviewResult>(PreviewResult.Loading) }
    LaunchedEffect(image.file) {
        preview = withContext(Dispatchers.IO) {
            ImageDecoding.decodeOriented(image.file, ImageDecoding.PREVIEW_MAX_DIMENSION)
                ?.let { PreviewResult.Ready(it.asImageBitmap()) }
                ?: PreviewResult.Failed
        }
    }
    val description = stringResource(R.string.camera_review_image_description)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val p = preview) {
                PreviewResult.Loading -> CircularProgressIndicator(color = Color.White)
                is PreviewResult.Ready -> Image(
                    bitmap = p.bitmap,
                    contentDescription = description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                PreviewResult.Failed -> Text(stringResource(R.string.camera_preview_failed), color = Color.White)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.camera_confirm))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.camera_retake), color = Color.White)
                }
                OutlinedButton(onClick = onChooseAnother, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.camera_choose_another), color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------ states
@Composable
private fun LoadingContent() {
    CenteredColumn {
        CircularProgressIndicator(color = Color.White)
        Text(stringResource(R.string.camera_loading_image), color = Color.White)
    }
}

@Composable
private fun ErrorContent(
    problem: ImageProblem,
    onTryAgain: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onClose: () -> Unit,
) {
    CenteredColumn {
        Text(stringResource(problem.messageRes()), color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onTryAgain) { Text(stringResource(R.string.camera_try_again)) }
        OutlinedButton(onClick = onChooseFromGallery) {
            Text(stringResource(R.string.camera_choose_another), color = Color.White)
        }
        TextButton(onClick = onClose) { Text(stringResource(R.string.camera_close), color = Color.White) }
    }
}

/** Neutral hand-off screen. No classification result exists or is shown in this phase. */
@Composable
private fun ConfirmedContent(onScanAnother: () -> Unit, onClose: () -> Unit) {
    CenteredColumn {
        Text(stringResource(R.string.camera_image_confirmed), color = Color.White, textAlign = TextAlign.Center)
        Button(onClick = onScanAnother) { Text(stringResource(R.string.camera_scan_another)) }
        TextButton(onClick = onClose) { Text(stringResource(R.string.camera_close), color = Color.White) }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) { content() }
}

@StringRes
private fun ImageProblem.messageRes(): Int = when (this) {
    ImageProblem.CAPTURE_FAILED -> R.string.camera_error_capture_failed
    ImageProblem.CAMERA_UNAVAILABLE -> R.string.camera_error_camera_unavailable
    ImageProblem.IMPORT_FAILED -> R.string.camera_error_import_failed
    ImageProblem.NOT_AN_IMAGE -> R.string.camera_error_not_an_image
    ImageProblem.UNSUPPORTED_FORMAT -> R.string.camera_error_unsupported_format
    ImageProblem.INVALID_DIMENSIONS -> R.string.camera_error_invalid_dimensions
    ImageProblem.TOO_LARGE -> R.string.camera_error_too_large
}
