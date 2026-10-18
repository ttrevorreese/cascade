package com.cascade.alarm.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cascade.alarm.ui.alarm.AlarmEditorScreen
import com.cascade.alarm.ui.diagnostics.DiagnosticsScreen
import com.cascade.alarm.ui.group.GroupScreen
import com.cascade.alarm.ui.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val GROUP = "group/{groupId}"
    const val ALARM = "alarm/{groupId}?alarmId={alarmId}"
    const val DIAGNOSTICS = "diagnostics"

    fun group(groupId: String) = "group/${Uri.encode(groupId)}"
    fun alarm(groupId: String, alarmId: String?) =
        "alarm/${Uri.encode(groupId)}" + (alarmId?.let { "?alarmId=${Uri.encode(it)}" } ?: "")
}

@Composable
fun CascadeNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenGroup = { nav.navigate(Routes.group(it)) },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.GROUP, arguments = listOf(navArgument("groupId") { type = NavType.StringType })) { entry ->
            val groupId = checkNotNull(entry.arguments?.getString("groupId"))
            GroupScreen(
                onBack = { nav.popBackStack() },
                onOpenAlarm = { alarmId -> nav.navigate(Routes.alarm(groupId, alarmId)) },
            )
        }
        composable(
            Routes.ALARM,
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("alarmId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            AlarmEditorScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
    }
}
