package com.cascade.alarm.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cascade.alarm.ui.engine.EngineScreen

object Routes {
    const val ENGINE = "engine"
}

@Composable
fun CascadeNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ENGINE) {
        composable(Routes.ENGINE) { EngineScreen() }
    }
}
