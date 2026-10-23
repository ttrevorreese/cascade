package com.cascade.alarm.ui.settings

import android.content.Context
import com.cascade.alarm.engine.EngineLog
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.theme.CascadeThemes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Look-and-feel choices that never affect whether or how an alarm rings. Kept in device-protected
 * storage because the firing screen themes itself from [themeId] before the first unlock.
 */
@Singleton
class UiPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().let { device ->
        // Before M7 these lived in credential storage, which cannot be read before the first unlock.
        runCatching { device.moveSharedPreferencesFrom(context, NAME) }
            .onFailure { EngineLog.w("ui preferences not moved yet: ${it.message}") }
        device.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    private val mutableAnimation = MutableStateFlow(readAnimation())
    private val mutableThemeId = MutableStateFlow(prefs.getString(THEME, null) ?: CascadeThemes.default.id)
    private val mutableGroupThemesOnHome = MutableStateFlow(prefs.getBoolean(GROUP_THEMES_ON_HOME, true))

    /** The background animation; matching the theme by default (owner's request, 2026-10-23). */
    val animation: StateFlow<AnimationChoice> = mutableAnimation.asStateFlow()

    /** The app theme as chosen; resolve it with ThemeResolution, which checks ownership. */
    val themeId: StateFlow<String> = mutableThemeId.asStateFlow()

    /**
     * Whether Home's group cards show each group's own theme (DESIGN.md §11.2), or all stay in the
     * app theme. On by default; added at the owner's request, 2026-10-23.
     */
    val groupThemesOnHome: StateFlow<Boolean> = mutableGroupThemesOnHome.asStateFlow()

    fun setGroupThemesOnHome(on: Boolean) {
        prefs.edit().putBoolean(GROUP_THEMES_ON_HOME, on).apply()
        mutableGroupThemesOnHome.value = on
    }

    fun setAnimation(choice: AnimationChoice) {
        prefs.edit().putString(ANIMATION, choice.key).apply()
        mutableAnimation.value = choice
    }

    fun setTheme(themeId: String) {
        val id = CascadeThemes.byId(themeId).id
        prefs.edit().putString(THEME, id).apply()
        mutableThemeId.value = id
    }

    /** Before choices existed the dots were a plain switch; off carries over as None. */
    private fun readAnimation(): AnimationChoice = when {
        prefs.contains(ANIMATION) -> AnimationChoice.fromKey(prefs.getString(ANIMATION, null))
        !prefs.getBoolean(OLD_SWITCH, true) -> AnimationChoice.None
        else -> AnimationChoice.MatchTheme
    }

    private companion object {
        const val NAME = "ui"
        const val ANIMATION = "background_animation"
        const val OLD_SWITCH = "background_motion"
        const val THEME = "theme"
        const val GROUP_THEMES_ON_HOME = "group_themes_on_home"
    }
}
