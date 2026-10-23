package com.cascade.alarm.data.billing

import android.content.Context
import com.cascade.alarm.ui.theme.ThemeSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user owns: today only Cascade Plus, the forty-six pack themes (DESIGN.md §12).
 *
 * The answer is cached in device-protected storage so the firing screen can theme itself before
 * the first unlock, and so a reinstall at midnight with no network still finds a restored
 * purchase once Play has synced it. Billing writes it; nothing about an alarm reads it except
 * the theme, and an unowned theme only ever falls back to a free one.
 */
@Singleton
class Entitlements @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val mutablePlus = MutableStateFlow(prefs.getBoolean(PLUS, false))

    val plusOwned: StateFlow<Boolean> = mutablePlus.asStateFlow()

    fun owns(theme: ThemeSpec): Boolean = theme.isFree || plusOwned.value

    /** Called by billing with Play's answer, and by the debug hook. */
    fun setPlusOwned(owned: Boolean) {
        prefs.edit().putBoolean(PLUS, owned).apply()
        mutablePlus.value = owned
    }

    private companion object {
        const val NAME = "entitlements"
        const val PLUS = "plus_owned"
    }
}
