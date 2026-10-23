package com.cascade.alarm.ui.ring

import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.ThemeResolution
import com.cascade.alarm.ui.theme.ThemeSpec

/**
 * The firing screen's theme: the ringing alarm's group theme, read at fire time (DESIGN.md §11.1),
 * else the app theme, else Forest. Both stores live in device-protected storage, so this works
 * before the first unlock; any failure at all means Forest, never a delay or a crash.
 */
fun ringTheme(groupThemeId: String?, preferences: UiPreferences, entitlements: Entitlements): ThemeSpec =
    try {
        ThemeResolution.resolve(groupThemeId, preferences.themeId.value, entitlements::owns)
    } catch (e: Exception) {
        EngineLog.e("ring theme unavailable; using the default", e)
        CascadeThemes.default
    }
