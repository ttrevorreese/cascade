package com.cascade.alarm.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cascade.alarm.ui.alarm.AlarmEditorScreen
import com.cascade.alarm.ui.appearance.AppearanceScreen
import com.cascade.alarm.ui.appearance.GroupThemeScreen
import com.cascade.alarm.ui.components.AmbientBackdrop
import com.cascade.alarm.ui.escalation.EscalationScreen
import com.cascade.alarm.ui.group.GroupScreen
import com.cascade.alarm.ui.group.GroupThemed
import com.cascade.alarm.ui.home.HomeScreen
import com.cascade.alarm.ui.reliability.ReliabilityScreen
import com.cascade.alarm.ui.settings.SettingsScreen
import com.cascade.alarm.ui.voice.VoiceScreen
import com.cascade.alarm.ui.sound.SoundScreen

object Routes {
    const val HOME = "home"
    const val GROUP = "group/{groupId}"
    const val ALARM = "alarm/{groupId}?alarmId={alarmId}"
    const val RELIABILITY = "reliability"
    const val ESCALATION = "escalation/{groupId}"
    const val SOUND = "sound/{groupId}"
    const val GROUP_THEME = "group-theme/{groupId}"
    const val SETTINGS = "settings"
    const val APPEARANCE = "appearance"
    const val VOICE = "voice"

    fun group(groupId: String) = "group/${Uri.encode(groupId)}"
    fun escalation(groupId: String) = "escalation/${Uri.encode(groupId)}"
    fun sound(groupId: String) = "sound/${Uri.encode(groupId)}"
    fun groupTheme(groupId: String) = "group-theme/${Uri.encode(groupId)}"
    fun alarm(groupId: String, alarmId: String?) =
        "alarm/${Uri.encode(groupId)}" + (alarmId?.let { "?alarmId=${Uri.encode(it)}" } ?: "")
}

private val GROUP_ARG = listOf(navArgument("groupId") { type = NavType.StringType })

/**
 * Themes (DESIGN.md §11.2): a group's own screens are [GroupThemed]; Home, Settings, Appearance
 * and Reliability use the app theme. Moving between them crossfades over 200ms, so a change of
 * theme reads as a transition rather than a glitch (§11.3).
 *
 * Home, Reliability, Sound and the alarm editor sit on [AmbientBackdrop] (the background
 * animation, when there is one). The group screen and the escalation editor do not: their spine
 * and rail are the only ornament (DESIGN.md §10). Settings shows the animation in its own preview.
 */
@Composable
fun CascadeNavHost() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(CROSSFADE_MILLIS)) },
        exitTransition = { fadeOut(tween(CROSSFADE_MILLIS)) },
        popEnterTransition = { fadeIn(tween(CROSSFADE_MILLIS)) },
        popExitTransition = { fadeOut(tween(CROSSFADE_MILLIS)) },
    ) {
        composable(Routes.HOME) {
            AmbientBackdrop {
                HomeScreen(
                    onOpenGroup = { nav.navigate(Routes.group(it)) },
                    onOpenReliability = { nav.navigate(Routes.RELIABILITY) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
        }
        composable(Routes.GROUP, arguments = GROUP_ARG) { entry ->
            val groupId = checkNotNull(entry.arguments?.getString("groupId"))
            GroupThemed {
                GroupScreen(
                    onBack = { nav.popBackStack() },
                    onOpenAlarm = { alarmId -> nav.navigate(Routes.alarm(groupId, alarmId)) },
                    onOpenEscalation = { nav.navigate(Routes.escalation(groupId)) },
                    onOpenSound = { nav.navigate(Routes.sound(groupId)) },
                    onOpenTheme = { nav.navigate(Routes.groupTheme(groupId)) },
                )
            }
        }
        composable(
            Routes.ALARM,
            arguments = GROUP_ARG + navArgument("alarmId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ) {
            GroupThemed { AmbientBackdrop { AlarmEditorScreen(onDone = { nav.popBackStack() }) } }
        }
        composable(Routes.ESCALATION, arguments = GROUP_ARG) {
            GroupThemed { EscalationScreen(onDone = { nav.popBackStack() }) }
        }
        composable(Routes.SOUND, arguments = GROUP_ARG) {
            GroupThemed { AmbientBackdrop { SoundScreen(onDone = { nav.popBackStack() }) } }
        }
        // Picking a group's theme happens in the app theme, so every preview is judged on the same page.
        composable(Routes.GROUP_THEME, arguments = GROUP_ARG) { GroupThemeScreen(onDone = { nav.popBackStack() }) }
        composable(Routes.RELIABILITY) { AmbientBackdrop { ReliabilityScreen(onBack = { nav.popBackStack() }) } }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAppearance = { nav.navigate(Routes.APPEARANCE) },
                onOpenReliability = { nav.navigate(Routes.RELIABILITY) },
                onOpenVoice = { nav.navigate(Routes.VOICE) },
            )
        }
        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.VOICE) { AmbientBackdrop { VoiceScreen(onBack = { nav.popBackStack() }, is24Hour = is24HourClock()) } }
    }
}

private const val CROSSFADE_MILLIS = 200
