package com.cascade.alarm.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.ui.components.CascadeSwitch
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferences: UiPreferences) : ViewModel() {
    val backgroundMotion: StateFlow<Boolean> = preferences.backgroundMotion
    fun setBackgroundMotion(on: Boolean) = preferences.setBackgroundMotion(on)
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val backgroundMotion by viewModel.backgroundMotion.collectAsStateWithLifecycle()
    SettingsContent(backgroundMotion, viewModel::setBackgroundMotion, onBack)
}

/** App settings. Starts small; SPEC §7's defaults, test alarm, theme and export join in M7. */
@Composable
fun SettingsContent(backgroundMotion: Boolean, onBackgroundMotionChange: (Boolean) -> Unit, onBack: () -> Unit) {
    val c = cascadeColors
    // Transparent so the floating dots behind it show (AmbientBackdrop, in CascadeNavHost).
    Scaffold(containerColor = Color.Transparent, topBar = { CascadeTopBar("Settings", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = CascadeSpace.m), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                        Text("Background animation", style = CascadeType.rowLabel, color = c.textPrimary)
                        Text(
                            if (backgroundMotion) "Slow floating dots behind some screens. They stay still if Android's animations are off."
                            else "No floating dots",
                            style = CascadeType.body,
                            color = c.textSecondary,
                        )
                    }
                    CascadeSwitch(backgroundMotion, onBackgroundMotionChange, Modifier.testTag("background-motion"))
                }
            }
        }
    }
}
