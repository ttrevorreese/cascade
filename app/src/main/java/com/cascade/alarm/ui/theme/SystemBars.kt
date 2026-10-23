package com.cascade.alarm.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Status and navigation bar icons follow the theme on screen, not the system dark-mode setting
 * (DESIGN.md §2.1). Inside a group with a theme of the other polarity, pass [restoreTo] (the
 * surrounding theme's) so leaving the group puts the bars back.
 */
@Composable
fun SystemBarsFor(isLight: Boolean, restoreTo: Boolean? = null) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    DisposableEffect(isLight) {
        activity.applyBars(isLight)
        onDispose { restoreTo?.let(activity::applyBars) }
    }
}

private fun ComponentActivity.applyBars(isLight: Boolean) {
    val bars = if (isLight) SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT) else SystemBarStyle.dark(Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
}
