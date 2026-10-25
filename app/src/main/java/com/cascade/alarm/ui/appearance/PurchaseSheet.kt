package com.cascade.alarm.ui.appearance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.cascade.alarm.ui.components.PrimaryButton
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.ThemeSpec
import com.cascade.alarm.ui.theme.cascadeColors

/**
 * What Cascade Plus is, shown when a locked theme is tapped or the unlock button is pressed
 * (DESIGN.md §12). Leads with what it is for, not the number; never applies the theme on trial,
 * never nags. [price] is null when Play has not answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseSheet(theme: ThemeSpec?, price: String?, onBuy: () -> Unit, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val c = cascadeColors
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CascadeSpace.gutter).padding(bottom = CascadeSpace.xxl).testTag("purchase-sheet"),
            verticalArrangement = Arrangement.spacedBy(CascadeSpace.m),
        ) {
            Text("Cascade Plus", style = CascadeType.screenTitle, color = c.textPrimary)
            Text(
                (theme?.let { "${it.name} is part of Cascade Plus. " } ?: "") +
                    "A different look for every group: all forty-six pack themes and the background animations, paid for once. Not a subscription.",
                style = CascadeType.body,
                color = c.textSecondary,
            )
            Text(
                "Every alarm feature works the same without it. Only the looks are extra.",
                style = CascadeType.body,
                color = c.textSecondary,
            )
            PrimaryButton(
                if (price != null) "Unlock all themes for $price" else "Not available yet",
                onBuy,
                Modifier.fillMaxWidth().testTag("buy-plus"),
                enabled = price != null,
            )
            TextButton(onClick = onRestore, modifier = Modifier.testTag("restore-plus")) { Text("Restore a purchase") }
        }
    }
}
