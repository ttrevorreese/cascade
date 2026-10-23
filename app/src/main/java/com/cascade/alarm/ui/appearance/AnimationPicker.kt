package com.cascade.alarm.ui.appearance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cascade.alarm.ui.components.AmbientAnimation
import com.cascade.alarm.ui.components.AmbientCanvas
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.components.animationsOff
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.cascadeColors

/** The background animation choices with a live preview (DESIGN.md §6), as rows of a list. */
fun LazyListScope.animationPicker(choice: AnimationChoice, themeId: String, onChange: (AnimationChoice) -> Unit) {
    item {
        val c = cascadeColors
        Column(Modifier.padding(horizontal = CascadeSpace.gutter)) {
            Text("Background animation", style = CascadeType.rowLabel, color = c.textPrimary)
            Text(
                "Plays behind the home screen and a few others, never while an alarm rings. It stands still if Android's animations are off.",
                style = CascadeType.body,
                color = c.textSecondary,
                modifier = Modifier.padding(top = CascadeSpace.xs, bottom = CascadeSpace.m),
            )
            AnimationPreview(choice.resolve(themeId))
        }
    }
    item {
        val theme = CascadeThemes.byId(themeId)
        val family = CascadeThemes.familyOf(themeId)
        Choice(
            title = "Match the theme",
            detail = "${theme.name} is in the ${family.label} family: " +
                (AmbientAnimation.forFamily(family.id)?.label?.lowercase()?.let { "$it." } ?: "no animation, to keep it easy to read."),
            selected = choice == AnimationChoice.MatchTheme,
            onSelect = { onChange(AnimationChoice.MatchTheme) },
            tag = "animation-theme",
        )
    }
    items(AmbientAnimation.entries, key = { it.key }) { option ->
        Choice(
            title = option.label,
            detail = option.description,
            selected = choice == AnimationChoice.Fixed(option),
            onSelect = { onChange(AnimationChoice.Fixed(option)) },
            tag = "animation-${option.key}",
        )
    }
    item {
        Choice(
            title = "None",
            detail = "A still background",
            selected = choice == AnimationChoice.None,
            onSelect = { onChange(AnimationChoice.None) },
            tag = "animation-none",
        )
    }
}

/** The chosen animation in a small window, so a choice can be judged without leaving the screen. */
@Composable
private fun AnimationPreview(animation: AmbientAnimation?) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = CascadeSpace.gutter).testTag(tag),
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
