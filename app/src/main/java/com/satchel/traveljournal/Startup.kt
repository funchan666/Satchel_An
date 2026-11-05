package com.satchel.traveljournal

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.*
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Composable fun SatchelApp() {
    val context = LocalContext.current
    val activity = context as Activity
    val backend = remember { PortalBackend(context.applicationContext) }
    val billing = remember { PlayProducts(context) }
    val ads = remember(activity) { PortalAds(activity) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var route by remember { mutableStateOf<PortalRoute?>(null) }
    var routeResolved by remember { mutableStateOf(false) }
    var adResolved by remember { mutableStateOf(false) }
    var webReady by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(true) }
    val language = remember { context.getSharedPreferences("satchel", 0).getString("language", "en") ?: "en" }
    DisposableEffect(billing, lifecycle) {
        billing.connect()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) billing.refreshPurchases() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); billing.close(); backend.close() }
    }
    LaunchedEffect(backend) {
        try { route = backend.open(language) } catch (_: Exception) { route = null }
        routeResolved = true
    }
    LaunchedEffect(ads) {
        ads.show("open") { adResolved = true }
        var elapsed = 0
        while (!adResolved) {
            delay(1000)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            if (ads.consentInProgress) { elapsed = 0; continue }
            if (ads.presenting) continue
            elapsed++
            if (elapsed >= 15) { ads.cancelOpen(); adResolved = true }
        }
    }
    LaunchedEffect(routeResolved, adResolved, route, webReady) {
        if (starting && routeResolved && adResolved && (route == null || webReady)) { starting = false }
    }
    Box(Modifier.fillMaxSize()) {
        route?.let { selected ->
            PortalView(selected, backend, ads, billing, lifecycle, { webReady = true }, { backend.closePortal(); route = null; webReady = false })
            if (!starting && ads.privacyRequired) Box(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) { Action("Privacy options", { it }, false) { ads.privacyOptions() } }
        }
        if (!starting && route == null) LocalApp(billing, ads, backend)
        if (starting) Box(Modifier.fillMaxSize().background(Color(0xFFFDF8E7)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Artwork("launch_icon", Modifier.size(184.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(3) { Artwork("launch_stitch", Modifier.size(width=18.dp,height=8.dp)) } }
            }
        }
    }
}

@Composable private fun PortalView(route: PortalRoute, backend: PortalBackend, ads: PortalAds, billing: PlayProducts, lifecycle: Lifecycle, loaded: () -> Unit, failed: () -> Unit) {
    val context = LocalContext.current
    val socket = remember(route.url) { PortalSocket(context.applicationContext, backend, ads, billing) }
    var view by remember(route.url) { mutableStateOf<WebView?>(null) }
    var fullScreenView by remember(route.url) { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember(route.url) { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun hideFullScreen() { val callback = fullScreenCallback; fullScreenCallback = null; fullScreenView = null; callback?.onCustomViewHidden() }
    var initialFinished by remember(route.url) { mutableStateOf(false) }
    var probeRunning by remember(route.url) { mutableStateOf(false) }
    var canGoBack by remember(route.url) { mutableStateOf(false) }
    fun finish() { if (!initialFinished) { initialFinished = true; loaded() } }
    fun fail() { if (!initialFinished) { initialFinished = true; failed() } }
    DisposableEffect(socket) { socket.start(); onDispose { hideFullScreen(); socket.stop(); view?.stopLoading(); view?.destroy(); view = null } }
    BackHandler(fullScreenView != null || canGoBack) { if (fullScreenView != null) hideFullScreen() else view?.goBack() }
    LaunchedEffect(route.url, initialFinished) {
        if (!initialFinished) {
            var elapsed = 0
            while (!initialFinished && elapsed < 30) { delay(1000); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) elapsed++ }
            if (!initialFinished) fail()
        }
    }
    LaunchedEffect(route.url, probeRunning, initialFinished) {
        while (probeRunning && !initialFinished) {
            view?.evaluateJavascript("(() => { if (!document.body) return false; if (document.body.innerText.trim().length) return true; return Array.from(document.images).some(i => { const r=i.getBoundingClientRect(); return i.complete && i.naturalWidth>0 && r.width>0 && r.height>0; }); })()") { if (it == "true") finish() }
            delay(500)
        }
    }
    Box(Modifier.fillMaxSize()) {
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> WebView(ctx).apply {
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(customView: View?, callback: CustomViewCallback?) {
                if (customView == null || callback == null || fullScreenView != null) { callback?.onCustomViewHidden(); return }
                fullScreenView = customView; fullScreenCallback = callback
            }
            override fun onHideCustomView() { hideFullScreen() }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (validWebUrl(request.url)) return false
                if (request.isForMainFrame) fail()
                return true
            }
            override fun onPageCommitVisible(view: WebView, url: String) { if (!initialFinished) probeRunning = true }
            override fun onPageFinished(view: WebView, url: String) { canGoBack = view.canGoBack(); finish() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) fail() }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) fail() }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean { if (initialFinished) failed() else fail(); return true }
        }
        view = this
        loadUrl(route.url)
    } })
    fullScreenView?.let { content -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        FrameLayout(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK); (content.parent as? ViewGroup)?.removeView(content); addView(content, FrameLayout.LayoutParams(-1, -1)) }
    }, onRelease = { frame -> frame.removeAllViews() }) }
    }
}
