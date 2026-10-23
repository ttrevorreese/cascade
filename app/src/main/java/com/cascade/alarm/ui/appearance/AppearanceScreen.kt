package com.cascade.alarm.ui.appearance

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.data.billing.PlusStore
import com.cascade.alarm.ui.components.AnimationChoice
import com.cascade.alarm.ui.components.CascadeSwitch
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.components.SecondaryButton
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.ThemeSpec
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val preferences: UiPreferences,
    private val entitlements: Entitlements,
    private val store: PlusStore,
) : ViewModel() {
    val themeId: StateFlow<String> = preferences.themeId
    val animation: StateFlow<AnimationChoice> = preferences.animation
    val plusOwned: StateFlow<Boolean> = entitlements.plusOwned
    val price: StateFlow<String?> = store.price
    val groupThemesOnHome: StateFlow<Boolean> = preferences.groupThemesOnHome

    init {
        // Ownership can change elsewhere (a purchase on another device, a refund): ask on each visit.
        store.refresh()
    }

    fun setTheme(theme: ThemeSpec) = preferences.setTheme(theme.id)
    fun setAnimation(choice: AnimationChoice) = preferences.setAnimation(choice)
    fun setGroupThemesOnHome(on: Boolean) = preferences.setGroupThemesOnHome(on)
    fun buy(activity: Activity) = store.buy(activity)
    fun restore() = store.refresh()
}

@Composable
fun AppearanceScreen(onBack: () -> Unit, viewModel: AppearanceViewModel = hiltViewModel()) {
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val animation by viewModel.animation.collectAsStateWithLifecycle()
    val plusOwned by viewModel.plusOwned.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val groupThemesOnHome by viewModel.groupThemesOnHome.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    AppearanceContent(
        themeId = themeId,
        animation = animation,
        plusOwned = plusOwned,
        price = price,
        onTheme = viewModel::setTheme,
        onAnimation = viewModel::setAnimation,
        onBuy = { activity?.let(viewModel::buy) },
        onRestore = viewModel::restore,
        onBack = onBack,
        groupThemesOnHome = groupThemesOnHome,
        onGroupThemesOnHome = viewModel::setGroupThemesOnHome,
    )
}

/**
 * The app theme and the background animation (DESIGN.md §12). The unlock button sits at the
 * bottom, below everything, and nowhere else in the app.
 */
@Composable
fun AppearanceContent(
    themeId: String,
    animation: AnimationChoice,
    plusOwned: Boolean,
    price: String?,
    onTheme: (ThemeSpec) -> Unit,
    onAnimation: (AnimationChoice) -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
    groupThemesOnHome: Boolean = true,
    onGroupThemesOnHome: (Boolean) -> Unit = {},
) {
    val c = cascadeColors
    val owns = { theme: ThemeSpec -> theme.isFree || plusOwned }
    var family by rememberSaveable { mutableStateOf(CascadeThemes.familyOf(themeId).id) }
    // The sheet, and the locked theme that opened it (null when opened from the unlock button).
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Appearance", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(0.dp, 0.dp, 0.dp, 48.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = CascadeSpace.gutter, vertical = CascadeSpace.s)) {
                    Text("Theme", style = CascadeType.rowLabel, color = c.textPrimary)
                    Text(
                        "${CascadeThemes.byId(themeId).name}. Each group can have its own, on the group's screen.",
                        style = CascadeType.body,
                        color = c.textSecondary,
                    )
                }
            }
            item {
                FamilyChips(
                    selected = CascadeThemes.families.first { it.id == family },
                    onSelect = { family = it.id },
                    modifier = Modifier.padding(vertical = CascadeSpace.s),
                )
            }
            item {
                FamilyGrid(CascadeThemes.families.first { it.id == family }, selectedId = themeId, owns = owns) { theme ->
                    if (owns(theme)) {
                        onTheme(theme)
                    } else {
                        sheetFor = theme.id
                        sheetOpen = true
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = CascadeSpace.gutter).padding(top = CascadeSpace.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = CascadeSpace.m)) {
                        Text("Group themes on Home", style = CascadeType.rowLabel, color = c.textPrimary)
                        Text(
                            if (groupThemesOnHome) "Each group's card shows its own theme" else "Every card stays in ${CascadeThemes.byId(themeId).name}",
                            style = CascadeType.body,
                            color = c.textSecondary,
                        )
                    }
                    CascadeSwitch(groupThemesOnHome, onGroupThemesOnHome, Modifier.testTag("group-themes-on-home"))
                }
            }
            item { Spacer(Modifier.height(CascadeSpace.xxl)) }
            animationPicker(animation, themeId, onAnimation)
            item {
                Column(Modifier.padding(horizontal = CascadeSpace.gutter).padding(top = CascadeSpace.xxl)) {
                    if (plusOwned) {
                        Text("Cascade Plus is unlocked: every theme is yours.", style = CascadeType.body, color = c.textSecondary)
                    } else {
                        SecondaryButton(
                            "Unlock all themes",
                            {
                                sheetFor = null
                                sheetOpen = true
                            },
                            Modifier.fillMaxWidth().testTag("unlock-themes"),
                        )
                    }
                }
            }
        }
    }
    if (sheetOpen) {
        PurchaseSheet(
            theme = sheetFor?.let(CascadeThemes::find),
            price = price,
            onBuy = onBuy,
            onRestore = onRestore,
            onDismiss = { sheetOpen = false },
        )
    }
}
