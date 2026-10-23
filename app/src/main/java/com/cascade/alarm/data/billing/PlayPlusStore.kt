package com.cascade.alarm.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.cascade.alarm.engine.EngineLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cascade Plus through Google Play Billing (DESIGN.md §12): one INAPP product, bought once.
 *
 * Ownership lives in [Entitlements], which only Play's actual answer changes. When Play cannot be
 * reached (offline, no Play Store, a slow restore) the cached answer stands, so a reinstall at
 * midnight keeps its themes once Play has synced, and a hiccup never takes them away.
 */
@Singleton
class PlayPlusStore @Inject constructor(
    @ApplicationContext context: Context,
    private val entitlements: Entitlements,
) : PlusStore {
    private val mutablePrice = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> = mutablePrice.asStateFlow()
    private var product: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases.orEmpty(), complete = false) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun refresh() = whenConnected {
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PlusStore.PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            ).build(),
        ) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                EngineLog.w("plus price unavailable: ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            product = details.productDetailsList.firstOrNull()
            mutablePrice.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            // Only a real answer from Play changes what the user owns.
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, complete = true)
        }
    }

    override fun buy(activity: Activity) {
        val details = product ?: return refresh()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) EngineLog.w("plus purchase not started: ${result.debugMessage}")
    }

    /**
     * [complete]: [purchases] is everything the user owns (a query), not just what changed (a
     * purchase update); only then can a missing Plus mean it was refunded.
     */
    private fun handle(purchases: List<Purchase>, complete: Boolean) {
        val plus = purchases.filter { PlusStore.PRODUCT_ID in it.products }
        PlusOwnership.after(plus.map { it.purchaseState == Purchase.PurchaseState.PURCHASED }, complete)?.let(entitlements::setPlusOwned)
        // Play refunds a purchase that is not acknowledged within three days.
        plus.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) EngineLog.w("plus acknowledgement failed: ${result.debugMessage}")
            }
        }
    }

    /** Work waiting for the connection; one connection attempt at a time. */
    private val waiting = mutableListOf<() -> Unit>()

    private fun whenConnected(block: () -> Unit) {
        if (client.isReady) return block()
        val alreadyConnecting = waiting.isNotEmpty()
        waiting += block
        if (alreadyConnecting) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ready = waiting.toList().also { waiting.clear() }
                if (result.responseCode == BillingClient.BillingResponseCode.OK) ready.forEach { it() } else EngineLog.w("billing unavailable: ${result.debugMessage}")
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }
}

/** How a Play answer changes ownership; separate from Play so it can be tested. */
object PlusOwnership {
    /**
     * [purchased]: for each Cascade Plus purchase in the answer, whether it is completed (a
     * pending one is not yet owned). Returns the new ownership, or null to keep the cached one:
     * a purchase update that does not include Plus says nothing about whether it is still owned.
     */
    fun after(purchased: List<Boolean>, complete: Boolean): Boolean? {
        val owned = purchased.any { it }
        return if (owned || complete) owned else null
    }
}
