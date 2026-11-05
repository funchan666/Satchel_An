package com.satchel.traveljournal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** This relay follows the iOS envelope; purchase fulfillment remains gated until the provider's Android contract is known. */
class PortalSocket(context: Context, private val backend: PortalBackend, private val ads: PortalAds, private val billing: PlayProducts) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).pingInterval(30, TimeUnit.SECONDS).build()
    private val ledger = AtomicFile(File(context.filesDir, "portal-operations.json"))
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
            if (ledger.baseFile.exists() || File(context.filesDir, "portal-operations.json.bak").exists()) operations = JSONArray(ledger.readFully().toString(Charsets.UTF_8))
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
        connect()
        scope.launch { while (isActive) { delay(30_000); resend() } }
    }
    fun stop() { stopped = true; connectionGeneration++; backend.onCredentialsChanged = null; socket?.close(1000, ""); socket = null; scope.cancel(); client.dispatcher.executorService.shutdown() }
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
        val params = data.optJSONObject("params") ?: JSONObject()
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
                billing.refreshPurchases()
                try { send(id, action, "pending", data = backend.status(), error = "restore_pending_reconciliation") }
                catch (_: Exception) { send(id, action, "pending", error = "restore_pending_reconciliation") }
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
            val failure = if (plan !in listOf("monthly", "yearly")) "invalid_plan" else if (backend.route?.purchaseAvailable != true) "purchase_disabled" else "android_verification_unavailable"
            update(op, "failed", failure); return
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
        send(op.getString("requestId"), op.getString("action"), status, op.optString("plan").takeIf { it.isNotEmpty() }, if (op.has("completed")) op.optBoolean("completed") else null, error = op.optString("error").takeIf { it.isNotEmpty() })
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
