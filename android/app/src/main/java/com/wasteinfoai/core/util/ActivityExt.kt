package com.wasteinfoai.core.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Unwraps ContextWrappers (e.g. Compose's themed contexts) to the hosting Activity, if any. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Opens this app's system settings page (used after a permanent permission denial). */
fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No settings UI available; nothing sensible to do.
    }
}
