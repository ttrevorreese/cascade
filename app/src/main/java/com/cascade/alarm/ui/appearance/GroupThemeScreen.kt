package com.cascade.alarm.ui.appearance

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.BuildConfig
import com.cascade.alarm.data.billing.PlusStore
import com.cascade.alarm.data.db.AlarmGroupEntity
import com.cascade.alarm.ui.components.CascadeTopBar
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeShapes
import com.cascade.alarm.ui.theme.CascadeSpace
import com.cascade.alarm.ui.theme.CascadeThemes
import com.cascade.alarm.ui.theme.CascadeType
import com.cascade.alarm.ui.theme.ThemeResolution
import com.cascade.alarm.ui.theme.ThemeSpec
import com.cascade.alarm.ui.theme.cascadeColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupThemePickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: AlarmRepository,
    preferences: UiPreferences,
    entitlements: Entitlements,
    private val store: PlusStore,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    val group: StateFlow<AlarmGroupEntity?> = repository.observeGroup(groupId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val appThemeId: StateFlow<String> = preferences.themeId
    val plusOwned: StateFlow<Boolean> = entitlements.plusOwned
    val price: StateFlow<String?> = store.price

    fun setTheme(themeId: String?) = viewModelScope.launch { repository.setGroupTheme(groupId, themeId) }
    fun buy(activity: Activity) = store.buy(activity)
    fun restore() = store.refresh()
    val forSale: Boolean = BuildConfig.PLUS_FOR_SALE
}

@Composable
fun GroupThemeScreen(onDone: () -> Unit, viewModel: GroupThemePickerViewModel = hiltViewModel()) {
    val group by viewModel.group.collectAsStateWithLifecycle()
    val appThemeId by viewModel.appThemeId.collectAsStateWithLifecycle()
    val plusOwned by viewModel.plusOwned.collectAsStateWithLifecycle()
    val price by viewModel.price.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val loaded = group ?: return
    GroupThemeContent(
        groupName = loaded.name,
        groupThemeId = loaded.themeId,
        appThemeId = appThemeId,
        plusOwned = plusOwned,
        forSale = viewModel.forSale,
        price = price,
        onTheme = viewModel::setTheme,
        onBuy = { activity?.let(viewModel::buy) },
        onRestore = viewModel::restore,
        onBack = onDone,
    )
}

/**
 * A group's own theme (DESIGN.md §11): the capability is free, only pack themes are paid. A pack
 * theme the phone does not own is kept but not shown, and this is where that is said.
 */
@Composable
fun GroupThemeContent(
    groupName: String,
    groupThemeId: String?,
    appThemeId: String,
    plusOwned: Boolean,
    price: String?,
    onTheme: (String?) -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onBack: () -> Unit,
    forSale: Boolean = true,
) {
    val c = cascadeColors
    val owns = { theme: ThemeSpec -> theme.isFree || plusOwned }
    val appTheme = ThemeResolution.app(appThemeId, owns)
    val families = CascadeThemes.shownFamilies(forSale || plusOwned)
    var family by rememberSaveable { mutableStateOf(CascadeThemes.familyOf(groupThemeId ?: appTheme.id).id) }
    val shown = families.firstOrNull { it.id == family } ?: families.first()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(containerColor = c.ground, topBar = { CascadeTopBar("Group theme", onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(0.dp, 0.dp, 0.dp, 48.dp),
        ) {
            item {
                Text(
                    "$groupName: its screens, its alarm cards and its alarms when they ring.",
                    style = CascadeType.body,
                    color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = CascadeSpace.gutter, vertical = CascadeSpace.s),
                )
            }
            if (ThemeResolution.groupThemeLocked(groupThemeId, owns)) {
                item {
                    val name = groupThemeId?.let(CascadeThemes::find)?.name
                    Surface(
                        shape = CascadeShapes.medium,
                        color = c.surface,
                        border = BorderStroke(1.5.dp, c.status.warn),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = CascadeSpace.gutter, vertical = CascadeSpace.s).testTag("theme-locked"),
                    ) {
                        Text(
                            "$name is part of Cascade Plus, which this phone does not have. This group uses ${appTheme.name} until it is unlocked.",
                            style = CascadeType.body,
                            color = c.textPrimary,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            item {
                FollowApp(
                    appThemeName = appTheme.name,
                    selected = groupThemeId == null,
                    onSelect = { onTheme(null) },
                )
            }
            item {
                FamilyChips(
                    selected = shown,
                    onSelect = { family = it.id },
                    modifier = Modifier.padding(vertical = CascadeSpace.s),
                    families = families,
                )
            }
            item {
                FamilyGrid(shown, selectedId = groupThemeId, owns = owns) { theme ->
                    if (owns(theme)) onTheme(theme.id) else sheetFor = theme.id
                }
            }
        }
    }
    sheetFor?.let { id ->
        PurchaseSheet(CascadeThemes.find(id), price, onBuy, onRestore, onDismiss = { sheetFor = null })
    }
}

@Composable
private fun FollowApp(appThemeName: String, selected: Boolean, onSelect: () -> Unit) {
    val c = cascadeColors
    Surface(
        shape = CascadeShapes.medium,
        color = if (selected) c.surfaceHigh else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CascadeSpace.gutter).testTag("follow-app-theme"),
    ) {
        Row(
            Modifier.selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).heightIn(min = 56.dp).padding(horizontal = CascadeSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = c.textPrimary, unselectedColor = c.textSecondary),
            )
            Column(Modifier.weight(1f).padding(start = CascadeSpace.m, top = CascadeSpace.s, bottom = CascadeSpace.s)) {
                Text("Follow the app theme", style = CascadeType.rowLabel, color = c.textPrimary)
                Text("$appThemeName, and whatever it changes to", style = CascadeType.body, color = c.textSecondary)
            }
        }
    }
}
