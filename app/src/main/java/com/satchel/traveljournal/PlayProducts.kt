package com.satchel.traveljournal

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

const val MONTHLY = "satchel_monthly"
const val YEARLY = "satchel_yearly"
private val productIds = listOf(MONTHLY, YEARLY)
data class PlayOffer(val id: String, val name: String, val price: String, val period: String)

class PlayProducts(context: Context) {
    var status by mutableStateOf("Connecting to Google Play…"); private set
    var offers by mutableStateOf<List<PlayOffer>>(emptyList()); private set
    var isMember by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    private val main = Handler(Looper.getMainLooper())
    private val key = try { KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(BuildConfig.PLAY_PUBLIC_KEY, Base64.DEFAULT))) } catch (_: Exception) { null }
    val configured: Boolean get() = key != null
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> refreshPurchases()
                BillingClient.BillingResponseCode.USER_CANCELED -> main.post { status = "Purchase cancelled." }
                else -> main.post { status = "Purchase could not be completed. Please try again." }
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection().build()
    fun connect() {
        if (key == null) { status = "Google Play verification is not configured yet."; return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) { main.post { status = "Google Play is unavailable."; ready = false }; return }
                refreshPurchases(); queryProducts()
            }
            override fun onBillingServiceDisconnected() { main.post { ready = false; status = "Google Play is disconnected." } }
        })
    }
    private fun valid(purchase: Purchase): Boolean = try {
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(key); verifier.update(purchase.originalJson.toByteArray(Charsets.UTF_8))
        verifier.verify(Base64.decode(purchase.signature, Base64.DEFAULT))
    } catch (_: Exception) { false }
    private fun process(purchases: List<Purchase>) {
        purchases.filter { p -> p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in productIds } && valid(p) && !p.isAcknowledged }.forEach { p ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) main.post { status = "Purchase confirmation will be retried when Google Play reconnects." }
            }
        }
    }
    fun refreshPurchases() {
        if (key == null || !client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { main.post { ready = false; status = "Could not check your membership. Please try again." }; return@queryPurchasesAsync }
            val active = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && it.products.any { id -> id in productIds } && valid(it) }
            val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING && it.products.any { id -> id in productIds } }
            main.post { isMember = active; ready = true; status = if(active) "Membership active." else if(pending) "Purchase pending." else "Free plan."; process(purchases) }
        }
    }
    private fun productQuery() = QueryProductDetailsParams.newBuilder().setProductList(productIds.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() }).build()
    private fun baseOffer(product: ProductDetails) = product.subscriptionOfferDetails?.firstOrNull { offer ->
        val phase = offer.pricingPhases.pricingPhaseList.singleOrNull()
        val expectedPeriod = if (product.productId == MONTHLY) "P1M" else if (product.productId == YEARLY) "P1Y" else ""
        offer.offerId == null && phase != null && phase.billingPeriod == expectedPeriod && phase.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING
    }
    fun queryProducts() {
        if (key == null) return
        client.queryProductDetailsAsync(productQuery()) { result, details ->
            val values = if (result.responseCode == BillingClient.BillingResponseCode.OK) details.productDetailsList.mapNotNull { p ->
                val offer = baseOffer(p); val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
                if (phase == null) null else PlayOffer(p.productId, p.name, phase.formattedPrice, phase.billingPeriod)
            } else emptyList()
            main.post { offers = values; if(values.isEmpty() && !isMember) status = "Google Play products are not available yet." }
        }
    }
    fun buy(activity: Activity, id: String) {
        if (key == null || !ready || isMember || id !in productIds) return
        client.queryProductDetailsAsync(productQuery()) { result, details ->
            val product = details.productDetailsList.firstOrNull { it.productId == id }
            val offer = product?.let { baseOffer(it) }
            if (result.responseCode != BillingClient.BillingResponseCode.OK || product == null || offer == null) { main.post { status = "This plan is unavailable. Please try again." }; return@queryProductDetailsAsync }
            val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offer.offerToken).build()
            main.post { val launch = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()); if (launch.responseCode != BillingClient.BillingResponseCode.OK) status = "Could not open Google Play. Please try again." }
        }
    }
    fun close() { client.endConnection() }
}
