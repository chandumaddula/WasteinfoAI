package com.wasteinfoai.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner

/**
 * The hosting Activity as a [LifecycleOwner] (ComponentActivity implements it). Used instead of
 * LocalLifecycleOwner, whose package differs between Compose/lifecycle versions.
 * The camera is bound only while the camera composable is in the composition.
 */
@Composable
fun rememberHostLifecycleOwner(): LifecycleOwner? {
    val context = LocalContext.current
    return remember(context) { context.findActivity() as? LifecycleOwner }
}
