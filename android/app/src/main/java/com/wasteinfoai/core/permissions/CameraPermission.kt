package com.wasteinfoai.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wasteinfoai.core.util.findActivity
import com.wasteinfoai.core.util.rememberHostLifecycleOwner

enum class CameraPermissionStatus {
    GRANTED,
    /** Not granted; the system dialog can still be shown (first time, or after one denial). */
    CAN_REQUEST,
    /** Denied with "don't ask again": only the system settings page can grant it now. */
    PERMANENTLY_DENIED,
}

@Stable
class CameraPermissionState internal constructor(
    val status: CameraPermissionStatus,
    /** True once the system dialog has been shown at least once (survives rotation). */
    val hasRequested: Boolean,
    private val onRequest: () -> Unit,
) {
    fun request() = onRequest()
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Tracks the CAMERA runtime permission. Re-checks on every ON_RESUME so a grant made in system
 * settings is picked up when the user returns. Permanent denial is inferred the standard way:
 * requested before, not granted, and the system no longer offers a rationale.
 */
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        granted = isGranted
        requested = true
    }

    val owner = rememberHostLifecycleOwner()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasCameraPermission()
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val activity = context.findActivity()
    val showRationale = activity != null &&
        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    val status = when {
        granted -> CameraPermissionStatus.GRANTED
        requested && !showRationale -> CameraPermissionStatus.PERMANENTLY_DENIED
        else -> CameraPermissionStatus.CAN_REQUEST
    }
    return remember(status, requested) {
        CameraPermissionState(status, requested) { launcher.launch(Manifest.permission.CAMERA) }
    }
}
