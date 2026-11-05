package com.satchel.traveljournal

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val PORTAL_HOST = "https://devapi.io-jp.com"
class PortalFailure(val kind: String) : Exception(kind)
data class PortalSession(val userId: String, val token: String, val deviceCode: String)
data class PortalRoute(val url: String, val purchaseAvailable: Boolean)

class PortalBackend(private val context: Context) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val shortClient = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
    private val refreshLock = Mutex()
    @Volatile var session: PortalSession? = null; private set
    @Volatile var route: PortalRoute? = null; private set
    var onCredentialsChanged: (() -> Unit)? = null
    private fun deviceId(): String {
        val prefs = context.getSharedPreferences("portal_identity", Context.MODE_PRIVATE)
        prefs.getString("deviceCode", null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val value = UUID.randomUUID().toString()
        if (!prefs.edit().putString("deviceCode", value).commit()) throw PortalFailure("identity_storage_failed")
        return value
    }
    private suspend fun ipAddress(): String = withContext(Dispatchers.IO) {
        for (url in listOf("https://ipv4.icanhazip.com", "https://api.ipify.org/")) {
            try {
                shortClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val body = response.body ?: return@use
                    if (!response.isSuccessful || body.contentLength() > 128) return@use
                    val text = body.byteStream().use { it.readNBytesCompat(129) }.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)?.trim() ?: return@use
                    if (text.length <= 15 && text.matches(Regex("[0-9.]+")) && InetAddress.getByName(text) is Inet4Address) return@withContext text
                }
            } catch (_: Exception) { }
        }
        ""
    }
    private suspend fun credentials(): PortalSession {
        val device = deviceId()
        val body = JSONObject().put("deviceCode", device).put("uniqueId", ipAddress()).put("source", context.packageName)
        val fields = nested(raw("/app-api/member/auth/app_tourist_login", body = body))
        val token = first(fields, "accessToken", "access_token", "token") ?: throw PortalFailure("login_failed")
        val user = first(fields, "userId", "user_id") ?: throw PortalFailure("login_failed")
        return PortalSession(user, token, device)
    }
    suspend fun open(language: String): PortalRoute {
        val current = credentials(); session = current
        val params = mapOf("platform" to "android", "name" to "Satchel", "version" to BuildConfig.VERSION_NAME, "lang" to language, "userId" to current.userId, "time" to TimeZone.getDefault().id, "input" to "手机键盘")
        val result = nested(raw("/app-api/flow/release-package/getV2", params = params))
        val switch = result.opt("NB01")
        val allowed = when (switch) { null, JSONObject.NULL -> true; is Boolean -> !switch; else -> false }
        val address = result.optString("NB02")
        val uri = Uri.parse(address)
        if (!validWebUrl(uri)) throw PortalFailure("unsafe_url")
        val output = uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.forEach { name -> if (name != "deviceCode" && name != "appId") uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) } }
            appendQueryParameter("deviceCode", current.deviceCode); appendQueryParameter("appId", context.packageName)
        }.build().toString()
        return PortalRoute(output, allowed).also { route = it }
    }
    suspend fun status(): Any = request("/app-api/member/user/get")
    suspend fun refresh(failedToken: String) = refreshLock.withLock {
        val old = session ?: throw PortalFailure("session_unavailable")
        if (old.token != failedToken) return@withLock
        val updated = credentials()
        if (updated.userId != old.userId || updated.deviceCode != old.deviceCode || session != old) throw PortalFailure("session_changed")
        session = updated; onCredentialsChanged?.invoke()
    }
    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null): Any {
        val current = session ?: throw PortalFailure("session_unavailable")
        return try { raw(path, params, body, current.token) } catch (error: PortalFailure) {
            if (error.kind != "unauthorized") throw error
            refresh(current.token)
            val next = session ?: throw PortalFailure("session_unavailable")
            if (next.userId != current.userId) throw PortalFailure("session_changed")
            raw(path, params, body, next.token)
        }
    }
    private suspend fun raw(path: String, params: Map<String, String> = emptyMap(), body: JSONObject? = null, token: String? = null): Any = withContext(Dispatchers.IO) {
        val builder = Uri.parse(PORTAL_HOST + path).buildUpon(); params.toSortedMap().forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        val request = Request.Builder().url(builder.build().toString()).header("Accept", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        if (body == null) request.get() else request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(request.build()).execute().use { response ->
            if (response.code == 401) throw PortalFailure("unauthorized")
            val payload = response.body ?: throw PortalFailure("invalid_response")
            if (!response.isSuccessful || payload.contentLength() > 2_000_000) throw PortalFailure("invalid_response")
            val bytes = payload.byteStream().use { it.readNBytesCompat(2_000_001) }
            if (bytes.size > 2_000_000) throw PortalFailure("invalid_response")
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (!json.has("code") || json.optInt("code", -1) != 0) throw PortalFailure("backend_rejected")
            json.opt("data") ?: JSONObject.NULL
        }
    }
    fun closePortal() { route = null }
    fun close() { closePortal(); session = null; onCredentialsChanged = null }
    private fun nested(value: Any): JSONObject {
        val obj = value as? JSONObject ?: throw PortalFailure("invalid_response")
        return obj.optJSONObject("data") ?: obj.optJSONObject("result") ?: obj
    }
    private fun first(obj: JSONObject, vararg names: String) = names.firstNotNullOfOrNull { name -> if (obj.isNull(name)) null else obj.optString(name).takeIf { it.isNotEmpty() } }
}
fun validWebUrl(uri: Uri): Boolean = uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrEmpty() && uri.userInfo == null
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (out.size() < limit) { val n = read(buffer, 0, minOf(buffer.size, limit - out.size())); if (n < 0) break; out.write(buffer, 0, n) }
    return out.toByteArray()
}
