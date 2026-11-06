package com.satchel.traveljournal

import android.content.Context
import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** The relay follows the iOS envelope and only reports Play purchases after backend verification. */
class PortalSocket(private val activity: Activity, private val backend: PortalBackend, private val ads: PortalAds, private val billing: PlayProducts) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).pingInterval(30, TimeUnit.SECONDS).build()
    private val ledger = AtomicFile(File(activity.filesDir, "portal-operations.json"))
    private var operations = JSONArray()
    private var ledgerAvailable = true
    private var socket: WebSocket? = null
    private var stopped = true
    private var connecting = false
    private var connectionGeneration = 0
    private var reconnectDelay = 1L
    private var refreshed = false
    private var currentUser = ""
    private val processing = mutableSetOf<String>()
    init {
        try {
            if (ledger.baseFile.exists() || File(activity.filesDir, "portal-operations.json.bak").exists()) operations = JSONArray(ledger.readFully().toString(Charsets.UTF_8))
        } catch (_: Exception) { ledgerAvailable = false }
    }
    fun start() {
        val session = backend.session ?: return
        if (!stopped) return
        currentUser = session.userId; stopped = false
        // A process death during an ad has an unknown outcome: do not replay it or award credit.
        for (i in 0 until operations.length()) {
            val op = operations.optJSONObject(i) ?: continue
            if (op.optString("userId") == currentUser && op.optString("status") == "pending" && op.optString("action").startsWith("ad.")) {
                op.put("status", "failed").put("error", "ad_interrupted").put("needsSend", true)
            }
        }
        if (!persist()) ledgerAvailable = false
        backend.onCredentialsChanged = { connectionGeneration++; socket?.cancel(); socket = null; connecting = false; connect() }
        billing.portalPurchases = { purchases -> handlePurchases(purchases) }
        connect()
        billing.refreshPurchases()
        scope.launch { while (isActive) { delay(30_000); resend(); billing.refreshPurchases(); recoverPayments() } }
        recoverPayments()
    }
    fun stop() { stopped = true; connectionGeneration++; backend.onCredentialsChanged = null; billing.portalPurchases = null; socket?.close(1000, ""); socket = null; scope.cancel(); client.dispatcher.executorService.shutdown() }
    private fun persist(): Boolean {
        if (!ledgerAvailable) return false
        var stream: java.io.FileOutputStream? = null
        return try { stream = ledger.startWrite(); stream.write(operations.toString().toByteArray()); ledger.finishWrite(stream); stream = null; true }
        catch (_: Exception) { stream?.let { ledger.failWrite(it) }; false }
    }
    private fun connect() {
        if (stopped || connecting || socket != null) return
        val auth = backend.session ?: return
        if (auth.userId != currentUser) return
        val url = android.net.Uri.parse("wss://devapi.io-jp.com/admin-api/ws/version").buildUpon().appendQueryParameter("userId", auth.userId).appendQueryParameter("clientType", "app").appendQueryParameter("accessToken", auth.token).build().toString()
        connecting = true
        val attempt = ++connectionGeneration
        client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { handler.post { if (stopped || attempt != connectionGeneration || backend.session?.token != auth.token) { webSocket.cancel(); return@post }; connecting = false; socket = webSocket; reconnectDelay = 1; refreshed = false; resend() } }
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.toByteArray().size <= 65_536) handler.post { if (attempt == connectionGeneration && socket === webSocket && !stopped) receive(text) } }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { if (bytes.size <= 65_536) onMessage(webSocket, bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { handler.post { if (attempt == connectionGeneration) disconnect(webSocket) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { handler.post {
                if (stopped || attempt != connectionGeneration) return@post
                if (socket != null && socket !== webSocket) return@post
                socket = null; connecting = false
                if (response?.code == 401 && !refreshed) {
                    refreshed = true
                    scope.launch { try { backend.refresh(auth.token); connect() } catch (_: Exception) { scheduleReconnect() } }
                } else if (response?.code != 401) scheduleReconnect()
            } }
        })
    }
    private fun disconnect(webSocket: WebSocket) { if (socket === webSocket) { socket = null; connecting = false; scheduleReconnect() } }
    private fun scheduleReconnect() { if (!stopped) { val wait = reconnectDelay; reconnectDelay = (reconnectDelay * 2).coerceAtMost(30); handler.postDelayed({ connect() }, wait * 1000) } }
    private fun matches(value: Any?): Boolean = value != null && value !== JSONObject.NULL && value.toString() == currentUser
    private fun receive(text: String) {
        val outer = try { JSONObject(text) } catch (_: Exception) { return }
        if (!matches(outer.opt("userId"))) return
        val type = outer.optString("type")
        var data = outer.optJSONObject("data") ?: return
        if (type == "ACK") { ack(data); return }
        if (type !in setOf("PAYMENT", "PAYMENT_REQUEST", "AD_REQUEST", "REQUEST")) return
        if (data.optString("type") == "ACK") { if (matches(data.opt("userId"))) data.optJSONObject("data")?.let { ack(it) }; return }
        if (data.optString("type") in setOf("PAYMENT", "PAYMENT_REQUEST", "AD_REQUEST", "REQUEST")) {
            if (!matches(data.opt("userId"))) return
            data = data.optJSONObject("data") ?: return
        }
        val id = data.optString("requestId"); val action = data.optString("action")
        if (id.length !in 1..128 || data.optInt("version", -1) != 1 || data.optString("platform") == "app") return
        val params = data.optJSONObject("params") ?: return
        if (action !in setOf("pay", "ad.rewarded", "ad.interstitial", "subscription.getStatus", "subscription.getProducts", "subscription.purchase", "subscription.restore")) return
        when (action) {
            "subscription.getStatus" -> scope.launch { try { send(id, action, "available", data = backend.status()) } catch (_: Exception) { send(id, action, "failed", error = "user_query_failed") } }
            "subscription.getProducts" -> billing.queryProducts { offers ->
                if (!stopped) {
                    val items = JSONArray(); offers.forEach { p -> items.put(JSONObject().put("planId", p.id).put("productId", p.id).put("rightsName", p.id).put("title", p.name).put("description", p.description).put("price", p.price).put("rawPrice", p.micros.toDouble() / 1_000_000).put("currencyCode", p.currency).put("available", true)) }
                    if (items.length() == 0) send(id, action, "failed", error = "products_not_found")
                    else send(id, action, "available", data = JSONObject().put("platform", "android").put("products", items))
                }
            }
            "subscription.restore" -> scope.launch {
                try {
                    val checked = withTimeoutOrNull(10_000) { suspendCancellableCoroutine<Boolean> { cont -> billing.refreshPurchases { ok -> if (cont.isActive) cont.resume(ok) } } } == true
                    if (!checked) throw PortalFailure("play_unavailable")
                    val status = backend.status()
                    val record = (0 until operations.length()).mapNotNull { operations.optJSONObject(it) }.firstOrNull { op ->
                        op.optString("userId") == currentUser && op.optString("status") == "purchased" && op.optString("providerMode") == "google_buy_v1" &&
                            billing.activePurchases.any { purchase -> purchase.purchaseState == Purchase.PurchaseState.PURCHASED && purchase.products.contains(op.optString("productId")) && purchase.accountIdentifiers?.obfuscatedAccountId == op.optString("accountId") && purchase.accountIdentifiers?.obfuscatedProfileId == op.optString("profileId") }
                    }
                    if (activeProviderExpiry(status) != null && record != null) send(id, action, "purchased", data = JSONObject().put("planId", record.optString("productId")).put("transactionId", record.optString("transactionId")).put("verified", true))
                    else send(id, action, "pending", data = status, error = "restore_pending_reconciliation")
                } catch (_: Exception) { send(id, action, "pending", error = "restore_pending_reconciliation") }
            }
            else -> handleOperation(id, action, params)
        }
    }
    private fun operation(id: String): JSONObject? = (0 until operations.length()).mapNotNull { operations.optJSONObject(it) }.firstOrNull { it.optString("userId") == currentUser && it.optString("requestId") == id }
    private fun handleOperation(id: String, action: String, params: JSONObject) {
        val plan = when (action) { "pay" -> if (params.length() == 0) "monthly" else params.optString("plan"); "subscription.purchase" -> when (params.optString("planId")) { MONTHLY -> "monthly"; YEARLY -> "yearly"; else -> "" }; else -> params.optString("plan") }
        val existing = operation(id)
        if (existing != null) { if (existing.optString("action") == action && existing.optString("plan") == plan && id !in processing) sendRecord(existing); return }
        if (!ledgerAvailable) { send(id, action, "failed", error = "storage_unavailable"); return }
        val op = JSONObject().put("userId", currentUser).put("requestId", id).put("action", action).put("plan", plan).put("status", "pending").put("needsSend", false).put("ackAmbiguous", false)
        operations.put(op)
        if (!persist()) { ledgerAvailable = false; send(id, action, "failed", error = "storage_unavailable"); return }
        if (action == "pay" || action == "subscription.purchase") {
            val failure = if (plan !in listOf("monthly", "yearly")) "invalid_plan" else if (backend.route?.purchaseAvailable != true) "purchase_disabled" else if (!billing.configured || !billing.ready) "product_query_failed" else if (billing.isMember) "membership_already_active" else null
            if (failure != null) update(op, "failed", failure) else beginPurchase(op)
            return
        }
        processing.add(id)
        ads.show(if (action == "ad.rewarded") "rewarded" else "interstitial") { result ->
            processing.remove(id)
            if (stopped) return@show
            val status = if (result == "rewarded" || result == "dismissed") result else "failed"
            if (action == "ad.rewarded") op.put("completed", result == "rewarded")
            update(op, status, if (status == "failed") result else null)
        }
    }
    private var activePayment: String? = null
    var purchaseActivity by mutableStateOf<String?>(null); private set
    private fun expiryMillis(value: Any?): Long? {
        val text = when (value) { is Number -> value.toString(); is String -> value; else -> return null }
        val number = text.toDoubleOrNull()
        if (number != null && number.isFinite() && number > 0) return (if (number < 100_000_000_000.0) number * 1000 else number).toLong()
        return try { Instant.parse(text).toEpochMilli() } catch (_: Exception) { null }
    }
    private fun providerExpiry(data: JSONObject): Long? {
        val value = if (!data.isNull("rightExpireTime")) data.opt("rightExpireTime") else data.opt("vipExpireTime")
        return expiryMillis(value)
    }
    private fun activeProviderExpiry(value: Any): Long? {
        val data = value as? JSONObject ?: return null
        val member = data.optInt("vipType", data.optInt("memberVipType", 0)) in 1..2
        return providerExpiry(data)?.takeIf { member && it > System.currentTimeMillis() }
    }
    private fun beginPurchase(op: JSONObject) {
        val id = op.getString("requestId")
        if (activePayment != null) { update(op, "failed", "purchase_in_progress"); return }
        activePayment = id; purchaseActivity = "Preparing your purchase…"; processing.add(id)
        scope.launch {
            try {
                val product = if (op.optString("plan") == "monthly") MONTHLY else YEARLY
                val original = backend.status()
                val fields = original as? JSONObject ?: throw PortalFailure("invalid_response")
                val member = fields.optInt("vipType", fields.optInt("memberVipType", 0)) in 1..2
                val priorExpiry = providerExpiry(fields)
                if (member && priorExpiry == null) throw PortalFailure("user_query_failed")
                val initialExpiry = if (member && priorExpiry != null && priorExpiry > System.currentTimeMillis()) priorExpiry else 0L
                val session = backend.session ?: throw PortalFailure("session_unavailable")
                if (stopped || session.userId != currentUser) { activePayment = null; purchaseActivity = null; return@launch }
                val account = MessageDigest.getInstance("SHA-256").digest((session.deviceCode + ":" + currentUser).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
                val profile = UUID.randomUUID().toString().replace("-", "")
                op.put("productId", product).put("accountId", account).put("profileId", profile)
                    .put("baselineExpiry", initialExpiry).put("providerMode", "google_buy_v1").put("stage", "preparing")
                if (!persist()) { ledgerAvailable = false; activePayment = null; purchaseActivity = null; return@launch }
                billing.launchPortal(activity, product, account, profile, { offer ->
                    op.put("priceMicros", offer.micros).put("currency", offer.currency).put("stage", "launching")
                    persist().also { if (!it) ledgerAvailable = false else purchaseActivity = "Waiting for Google Play…" }
                }) { code, launched ->
                    activePayment = null; purchaseActivity = null
                    if (stopped) return@launchPortal
                    if (code == BillingClient.BillingResponseCode.USER_CANCELED) update(op, "cancelled", null)
                    else if (!launched) update(op, "failed", "play_launch_failed")
                    billing.refreshPurchases()
                }
            } catch (_: Exception) {
                if (!stopped) update(op, "failed", "user_query_failed")
                activePayment = null; purchaseActivity = null
            } finally { processing.remove(id) }
        }
    }
    private fun handlePurchases(purchases: List<Purchase>) {
        if (stopped || !ledgerAvailable) return
        purchases.forEach { purchase ->
            val ids = purchase.accountIdentifiers ?: return@forEach
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || ids.obfuscatedAccountId.isNullOrBlank() || ids.obfuscatedProfileId.isNullOrBlank()) return@forEach
            val record = (0 until operations.length()).mapNotNull { operations.optJSONObject(it) }.firstOrNull { op ->
                op.optString("userId") == currentUser && op.optString("providerMode") == "google_buy_v1" && op.optString("action") in listOf("pay", "subscription.purchase") && op.optString("accountId") == ids.obfuscatedAccountId && op.optString("profileId") == ids.obfuscatedProfileId && purchase.products.contains(op.optString("productId"))
            } ?: return@forEach
            if (record.optString("status") == "purchased") return@forEach
            val known = record.optString("purchaseToken")
            if (known.isNotEmpty() && known != purchase.purchaseToken) return@forEach
            record.put("status", "pending").put("purchaseToken", purchase.purchaseToken).put("transactionId", purchase.orderId ?: "")
            if (!persist()) { ledgerAvailable = false; return@forEach }
            verifyPayment(record)
        }
    }
    private fun recoverPayments() {
        if (stopped || !ledgerAvailable) return
        for (i in 0 until operations.length()) {
            val op = operations.optJSONObject(i) ?: continue
            if (op.optString("userId") == currentUser && op.optString("providerMode") == "google_buy_v1" && op.optString("status") == "pending" && op.optString("purchaseToken").isNotEmpty()) verifyPayment(op)
        }
    }
    private fun verifyPayment(op: JSONObject) {
        val id = op.optString("requestId")
        if (!processing.add(id)) return
        scope.launch {
            if (activePayment == null) purchaseActivity = "Confirming your purchase…"
            try {
                if (!op.optBoolean("providerAccepted")) {
                    // The Google endpoint's replay behavior is undocumented. Never repeat an uncertain submission.
                    if (op.optBoolean("submitStarted")) return@launch
                    op.put("submitStarted", true)
                    if (!persist()) { ledgerAvailable = false; return@launch }
                    val result = backend.confirmGooglePurchase(op.getString("productId"), op.getString("purchaseToken"), op.getLong("priceMicros"), op.getString("currency"))
                    if (result !is String || result.isBlank() || result.trim().lowercase() in listOf("false", "failed", "error")) return@launch
                    op.put("providerAccepted", true)
                    if (!persist()) { ledgerAvailable = false; return@launch }
                }
                val expiry = activeProviderExpiry(backend.status()) ?: return@launch
                if (expiry <= op.optLong("baselineExpiry", 0L)) return@launch
                val acknowledged = withTimeoutOrNull(15_000) { suspendCancellableCoroutine<Boolean> { cont ->
                    billing.acknowledgePortal(op.getString("purchaseToken"), op.getString("accountId"), op.getString("profileId")) { ok -> if (cont.isActive) cont.resume(ok) }
                } } == true
                if (acknowledged && !stopped) update(op, "purchased", null)
            } catch (_: Exception) { /* Retain the original transaction; never guess a successful result. */ }
            finally { processing.remove(id); if (activePayment == null && purchaseActivity == "Confirming your purchase…") purchaseActivity = null }
        }
    }
    private fun update(op: JSONObject, status: String, error: String?) {
        op.put("status", status).put("needsSend", true)
        if (error == null) op.remove("error") else op.put("error", error)
        if (!persist()) { ledgerAvailable = false; return }
        sendRecord(op)
    }
    private fun resend() { if (!stopped) for (i in 0 until operations.length()) { val op = operations.optJSONObject(i) ?: continue; if (op.optString("userId") == currentUser && op.optBoolean("needsSend") && op.optString("requestId") !in processing) sendRecord(op) } }
    private fun sendRecord(op: JSONObject) {
        val status = op.optString("status"); if (status == "pending") return
        val previous = op.optString("ackCandidate")
        if (previous.isNotEmpty() && previous != status) op.put("ackAmbiguous", true) else if (previous.isEmpty()) op.put("ackCandidate", status)
        op.put("needsSend", true)
        if (!persist()) { ledgerAvailable = false; return }
        val purchaseData = if (op.optString("action") == "subscription.purchase" && status == "purchased") JSONObject().put("planId", op.optString("productId")).put("transactionId", op.optString("transactionId")).put("verified", true) else null
        send(op.getString("requestId"), op.getString("action"), status, op.optString("plan").takeIf { it.isNotEmpty() }, if (op.has("completed")) op.optBoolean("completed") else null, data = purchaseData, error = op.optString("error").takeIf { it.isNotEmpty() })
    }
    private fun ack(data: JSONObject) {
        if (data.optString("platform") != "h5" || data.optString("status") != "received" || data.optInt("version") != 1) return
        val op = operation(data.optString("requestId")) ?: return
        if (op.optString("action") != data.optString("action") || !op.optBoolean("needsSend")) return
        val received = data.optString("receivedStatus")
        if (received.isNotEmpty()) { if (received != op.optString("status")) return }
        else if (op.optBoolean("ackAmbiguous", true) || op.optString("ackCandidate") != op.optString("status")) return
        op.put("needsSend", false); if (!persist()) ledgerAvailable = false
    }
    private fun send(id: String, action: String, status: String, plan: String? = null, completed: Boolean? = null, data: Any? = null, error: String? = null) {
        val obj = JSONObject().put("type", "APP_RESULT").put("userId", currentUser).put("requestId", id).put("action", action).put("status", status).put("version", 1).put("platform", "app")
        if (plan != null) obj.put("params", JSONObject().put("plan", plan)); if (completed != null) obj.put("completed", completed); if (data != null) obj.put("data", data); if (error != null) obj.put("error", error)
        socket?.send(obj.toString())
    }
}
