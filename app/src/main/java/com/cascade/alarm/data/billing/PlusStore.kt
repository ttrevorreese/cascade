package com.cascade.alarm.data.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * Buying Cascade Plus, the one-time unlock of the forty-six pack themes (DESIGN.md §12): a Play
 * Billing INAPP product, never a subscription. What the user owns is published to [Entitlements].
 */
interface PlusStore {
    /** Play's localised price, or null while it is unknown or Play is unavailable. */
    val price: StateFlow<String?>

    /** Starts Play's purchase flow; the outcome reaches [Entitlements]. */
    fun buy(activity: Activity)

    /** Asks Play again what the user owns: restore purchases. */
    fun refresh()

    companion object {
        /** The in-app product id, as created in Play Console. */
        const val PRODUCT_ID = "cascade_plus"
    }
}
