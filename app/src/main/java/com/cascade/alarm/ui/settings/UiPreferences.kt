package com.cascade.alarm.ui.settings

import android.content.Context
import com.cascade.alarm.ui.components.AmbientAnimation
import com.cascade.alarm.ui.theme.CascadeThemes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What plays behind the screens: the theme family's own, one picked by name, or nothing. */
sealed interface AnimationChoice {
    data object MatchTheme : AnimationChoice
    data class Fixed(val animation: AmbientAnimation) : AnimationChoice
    data object None : AnimationChoice

    /** The animation to show under [themeId]; null means a still background. */
    fun resolve(themeId: String): AmbientAnimation? = when (this) {
        MatchTheme -> AmbientAnimation.forFamily(CascadeThemes.familyOf(themeId).id)
        is Fixed -> animation
        None -> null
    }

    val key: String
        get() = when (this) {
            MatchTheme -> MATCH_THEME
            is Fixed -> animation.key
            None -> NONE
        }

    companion object {
        private const val MATCH_THEME = "theme"
        private const val NONE = "none"

        fun fromKey(key: String?): AnimationChoice = when (key) {
            null, MATCH_THEME -> MatchTheme
            NONE -> None
            else -> AmbientAnimation.byKey(key)?.let(::Fixed) ?: MatchTheme
        }
    }
}

/**
 * Look-and-feel choices that never affect an alarm. Kept out of the alarm database on purpose:
 * nothing on the ring path reads them.
 */
@Singleton
class UiPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val mutableAnimation = MutableStateFlow(readAnimation())
    private val mutableThemeId = MutableStateFlow(prefs.getString(THEME, null) ?: CascadeThemes.default.id)

    /** The background animation; matching the theme by default (owner's request, 2026-10-23). */
    val animation: StateFlow<AnimationChoice> = mutableAnimation.asStateFlow()

    /** The app's theme. Only the debug SET_THEME hook changes it until M7's Appearance screen. */
    val themeId: StateFlow<String> = mutableThemeId.asStateFlow()

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
        const val ANIMATION = "background_animation"
        const val OLD_SWITCH = "background_motion"
        const val THEME = "theme"
    }
}
