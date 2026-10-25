package com.cascade.alarm.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds' store: "buying" unlocks Cascade Plus at once, for free, and Play is never asked,
 * so a test phone keeps what it unlocked (Play would answer "not owned" and take it back).
 * `SET_PLUS --ez owned false` locks it again.
 */
@Singleton
class TestPlusStore @Inject constructor(private val entitlements: Entitlements) : PlusStore {
    private val mutablePrice = MutableStateFlow<String?>("$0 (test build)")
    override val price: StateFlow<String?> = mutablePrice.asStateFlow()

    override fun buy(activity: Activity) = entitlements.setPlusOwned(true)

    override fun refresh() = Unit
}
