package com.cascade.alarm.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val ENGINE = "engine"
}

@Composable
fun CascadeNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.ENGINE) {
        composable(Routes.ENGINE) {
            Scaffold { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Cascade")
                }
            }
        }
    }
}
