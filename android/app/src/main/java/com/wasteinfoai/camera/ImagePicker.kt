package com.wasteinfoai.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Stable
class ImagePickerLauncher internal constructor(private val onLaunch: () -> Unit) {
    fun launch() = onLaunch()
}

/**
 * Gallery selection through the system Photo Picker (ActivityResultContracts.PickVisualMedia). It
 * needs no storage or media permission: the user hands over only the image they choose. On devices
 * without the platform picker the contract falls back automatically to the system document picker.
 *
 * [onImagePicked] receives the content URI as a string, or null if the user cancelled.
 */
@Composable
fun rememberImagePicker(onImagePicked: (String?) -> Unit): ImagePickerLauncher {
    val latest by rememberUpdatedState(onImagePicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        latest(uri?.toString())
    }
    return remember(launcher) {
        ImagePickerLauncher {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}
