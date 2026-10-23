package com.cascade.alarm.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** A runtime permission as challenge setup needs it (SPEC §6.8: asked at setup, never at alarm time). */
class PermissionRequest internal constructor(
    val granted: Boolean,
    /** Shows the system dialog; once the user has said no, Android stops showing it, so this opens app settings instead. */
    val request: () -> Unit,
)

/**
 * [permission] is null when nothing needs asking on this Android version (then it counts as
 * granted). Re-checks on resume, so a change made in system settings shows up straight away.
 */
@Composable
fun rememberPermissionRequest(permission: String?, isGranted: () -> Boolean): PermissionRequest {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(permission == null || isGranted()) }
    var refused by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) refused = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = permission == null || isGranted() }
    return PermissionRequest(granted) {
        when {
            permission == null || granted -> Unit
            refused -> context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> launcher.launch(permission)
        }
    }
}
