package com.cascade.alarm.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.ui.components.AmbientAnimation
import com.cascade.alarm.ui.components.AmbientCanvas
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.animationsOff
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferences: UiPreferences) : ViewModel() {
    val animation: StateFlow<AnimationChoice> = preferences.animation
    val themeId: StateFlow<String> = preferences.themeId
    fun setAnimation(choice: AnimationChoice) = preferences.setAnimation(choice)
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val animation by viewModel.animation.collectAsStateWithLifecycle()
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    SettingsContent(animation, themeId, viewModel::setAnimation, onBack)
}

/** App settings. Starts small; SPEC §7's defaults, test alarm, theme and export join in M7. */
@Composable
fun SettingsContent(animation: AnimationChoice, themeId: String, onAnimationChange: (AnimationChoice) -> Unit, onBack: () -> Unit) {
    val c = cascadeColors
    val theme = CascadeThemes.byId(themeId)
    val family = CascadeThemes.familyOf(themeId)
    val familyAnimation = AmbientAnimation.forFamily(family.id)
    val showing = animation.resolve(themeId)
    // A plain ground: the preview is the one animation here, not a second copy behind the list.
    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Settings", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(CascadeSpace.gutter, 0.dp, CascadeSpace.gutter, 48.dp),
        ) {
            item {
                Text("Background animation", style = CascadeType.rowLabel, color = c.textPrimary)
                Text(
                    "Plays behind the home screen and a few others, never while an alarm rings. It stands still if Android's animations are off.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = CascadeSpace.xs, bottom = CascadeSpace.m),
                )
            }
            item { Preview(showing) }
            item {
                Choice(
                    title = "Match the theme",
                    detail = "${theme.name} is in the ${family.label} family: " +
                        (familyAnimation?.label?.lowercase()?.let { "$it." } ?: "no animation, to keep it easy to read."),
                    selected = animation == AnimationChoice.MatchTheme,
                    onSelect = { onAnimationChange(AnimationChoice.MatchTheme) },
                    tag = "animation-theme",
                )
            }
            items(AmbientAnimation.entries, key = { it.key }) { option ->
                Choice(
                    title = option.label,
                    detail = option.description,
                    selected = animation == AnimationChoice.Fixed(option),
                    onSelect = { onAnimationChange(AnimationChoice.Fixed(option)) },
                    tag = "animation-${option.key}",
                )
            }
            item {
                Choice(
                    title = "None",
                    detail = "A still background",
                    selected = animation == AnimationChoice.None,
                    onSelect = { onAnimationChange(AnimationChoice.None) },
                    tag = "animation-none",
                )
            }
        }
    }
}

/** The chosen animation in a small window, so a choice can be judged without leaving the screen. */
@Composable
private fun Preview(animation: AmbientAnimation?) {
    val c = cascadeColors
    Surface(
        shape = CascadeShapes.medium,
        color = c.groundDeep,
        modifier = Modifier.fillMaxWidth().height(140.dp).padding(bottom = CascadeSpace.m).testTag("animation-preview"),
    ) {
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            if (animation == null) {
                Text("No animation", style = CascadeType.body, color = c.textQuiet)
            } else {
                AmbientCanvas(animation, Modifier.fillMaxSize(), animate = !animationsOff())
            }
        }
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, tag: String) {
    val c = cascadeColors
    Surface(
        shape = CascadeShapes.medium,
        color = if (selected) c.surfaceHigh else Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .heightIn(min = 56.dp)
                .padding(horizontal = CascadeSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The row takes the click; the button shows the state by shape as well as colour.
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = c.textPrimary, unselectedColor = c.textSecondary),
            )
            Column(Modifier.weight(1f).padding(start = CascadeSpace.m, top = CascadeSpace.s, bottom = CascadeSpace.s)) {
                Text(title, style = CascadeType.rowLabel, color = c.textPrimary)
                Text(detail, style = CascadeType.body, color = c.textSecondary)
            }
        }
    }
}
