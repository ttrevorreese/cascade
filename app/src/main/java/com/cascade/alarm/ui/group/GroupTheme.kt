package com.cascade.alarm.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cascade.alarm.data.AlarmRepository
import com.cascade.alarm.data.billing.Entitlements
import com.cascade.alarm.ui.settings.UiPreferences
import com.cascade.alarm.ui.theme.CascadeTheme
import com.cascade.alarm.ui.theme.LocalCascadeTheme
import com.cascade.alarm.ui.theme.SystemBarsFor
import com.cascade.alarm.ui.theme.ThemeResolution
import com.cascade.alarm.ui.theme.ThemeSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The theme of the group named by the destination's `groupId` argument, kept up to date. */
@HiltViewModel
class GroupThemeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: AlarmRepository,
    preferences: UiPreferences,
    entitlements: Entitlements,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])

    /** Null until the group has loaded; the surrounding theme shows meanwhile. */
    val theme: StateFlow<ThemeSpec?> =
        combine(repository.observeGroup(groupId), preferences.themeId, entitlements.plusOwned) { group, appTheme, plus ->
            ThemeResolution.resolve(group?.themeId, appTheme) { it.isFree || plus }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * A group's screens in the group's own theme (DESIGN.md §11.2: group detail, the alarm editor,
 * the escalation editor, and its sound). The navigation crossfade covers the change of theme.
 */
@Composable
fun GroupThemed(viewModel: GroupThemeViewModel = hiltViewModel(), content: @Composable () -> Unit) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val outer = LocalCascadeTheme.current
    val shown = theme ?: outer
    SystemBarsFor(shown.colors.isLight, restoreTo = outer.colors.isLight)
    CascadeTheme(shown, content)
}
