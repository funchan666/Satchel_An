package com.satchel.traveljournal

import android.content.SharedPreferences
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.app.Activity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.Normalizer
import java.util.Locale
import java.io.File
import kotlinx.coroutines.delay

@Composable fun dateLabel(value: String, long: Boolean = false): String {
    val context = LocalContext.current
    val language = context.getSharedPreferences("satchel", 0).getString("language", "en") ?: "en"
    return try { LocalDate.parse(value.take(10)).format(DateTimeFormatter.ofLocalizedDate(if (long) FormatStyle.LONG else FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag(language))) } catch (_: Exception) { value }
}
@Composable fun timeLabel(value: String): String {
    val context = LocalContext.current
    val language = context.getSharedPreferences("satchel", 0).getString("language", "en") ?: "en"
    return try { LocalTime.parse(value.substringAfter(' ')).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag(language))) } catch (_: Exception) { value }
}

@Composable fun TripsScreen(store: TravelStore, tr: (String)->String, create: ()->Unit, open: (String)->Unit, switch: (Int)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var favorites by rememberSaveable { mutableStateOf(false) }
    val sorted = store.trips.sortedByDescending { it.start }
    val term = query.trim()
    val matches = sorted.filter { (!favorites || it.favorite) && (it.destination.contains(term, true) || it.entries.any { e -> e.text.contains(term, true) }) }
    ScrollPage { Hero("coast_notebook"); ContentColumn {
        Field(tr("Search journeys and memories"), query, { query = it }, placeholder = tr("Search destinations or memories"))
        if (sorted.isEmpty()) SurfaceCard(Modifier.fillMaxWidth(), "journey_paper") {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Head(tr("Your next journey starts here.")); Caption(tr("Keep your packing list and memories in one place.")); Action("New Trip", tr, artwork="action_add", onClick=create)
                }
                Spacer(Modifier.fillMaxWidth(.42f))
            }
        }
        else {
            SurfaceCard(Modifier.fillMaxWidth(), "collection_paper_v2") { Text("${sorted.size}  ${tr("Trips")}     ${sorted.sumOf { it.entries.size }}  ${tr("Memories")}", color = ink); Action("New Trip", tr, artwork="action_add", onClick=create) }
            Action("Favorites", tr, favorites, "action_filter") { favorites = !favorites }
            if (matches.isEmpty()) Head(tr(if (favorites && query.isBlank()) "No favorite journeys yet." else "No matching journeys"))
            val today = LocalDate.now().toString()
            val upcoming = matches.filter { it.end >= today }.sortedBy { it.start }; val past = matches.filter { it.end < today }
            if (upcoming.isNotEmpty()) { Head(tr("Upcoming")); upcoming.forEach { TripCard(store, it, tr) { open(it.id) } } }
            if (past.isNotEmpty()) { Head(tr("Past journeys")); past.forEach { TripCard(store, it, tr) { open(it.id) } } }
        }
        Text(tr("Explore Satchel"), color=muted, fontWeight=FontWeight.SemiBold)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { listOf(1 to "Journal", 2 to "Travel guide").forEach { (i,title) -> SurfaceCard(Modifier.weight(1f).clickable { switch(i) }, if (i == 1) "button_paper" else "button_selected") { Artwork(if (i == 1) "journal_mark_v2" else "guide_mark_v2", Modifier.size(42.dp)); Text(tr(title), color=ink, fontWeight=FontWeight.SemiBold); Caption(tr(if(i==1) "Save travel memories" else "Ideas for your journey")) } } }
    } }
}
@Composable fun TripCard(store: TravelStore, trip: Trip, tr: (String)->String, journal: Boolean = false, searchTerm: String = "", open: ()->Unit) {
    val entry = if (journal) trip.entries.sortedByDescending { it.date }.firstOrNull { searchTerm.isEmpty() || trip.destination.contains(searchTerm, true) || it.text.contains(searchTerm, true) } else null
    val photo = if (journal) entry?.photos?.firstOrNull() ?: trip.cover else trip.cover
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cream).clickable(onClick=open)) {
        if (!journal || photo != null) Box { Photo(store, photo, Modifier.fillMaxWidth().height(180.dp)); if (!journal && trip.favorite) Artwork("favorite_on", Modifier.padding(8.dp).size(48.dp)) }
        Column(Modifier.padding(17.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Head(trip.destination)
            if (journal) { Caption("${trip.entries.size} · ${tr("Memories")}"); entry?.let { Caption(dateLabel(it.date)); if (it.text.isNotEmpty()) Text(it.text, color=ink, maxLines=3) } }
            else { Caption("${dateLabel(trip.start)} — ${dateLabel(trip.end)}"); Caption("${trip.items.count { it.packed }} / ${trip.items.size} · ${tr("Packing")}") }
        }
    }
}
@Composable fun JournalScreen(store: TravelStore, tr: (String)->String, create: ()->Unit, open: (String)->Unit, add: (String)->Unit) {
    var query by rememberSaveable { mutableStateOf("") }; val trips = store.trips.sortedByDescending { it.start }
    ScrollPage { Hero("journal_coast", 220); ContentColumn {
        if (trips.isNotEmpty()) {
            Field(tr("Search journeys and memories"), query, { query=it }, placeholder = tr("Search destinations or memories"))
            if (trips.size == 1) Action("Add a memory",tr,artwork="action_add") { add(trips.first().id) }
            else {
                Text(tr("Choose a journey to add a memory"), color=muted)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) { trips.forEach { Action(it.destination, { s->s }, false) { add(it.id) } } }
            }
        }
        val term = query.trim()
        val matches = trips.filter { it.entries.isNotEmpty() && (it.destination.contains(term,true) || it.entries.any { e->e.text.contains(term,true) }) }
        if (matches.isEmpty()) {
            Head(tr(if (query.isEmpty()) "Your memories start here." else "No matching memories"))
            if (trips.isEmpty()) Action("New Trip", tr, artwork="action_add", onClick=create)
            if (query.isEmpty()) { Head(tr("A little inspiration")); listOf("journal_idea_place" to "A place worth remembering", "journal_idea_moment" to "A moment that made you smile", "journal_idea_photo" to "A photo and the story behind it").forEach { (art, title)-> Row(verticalAlignment=Alignment.CenterVertically) { Artwork(art,Modifier.size(64.dp)); Text(tr(title),Modifier.padding(start=12.dp), color=ink) } } }
        } else matches.forEach { TripCard(store,it,tr,true,term) { open(it.id) } }
    } }
}
private val guide = listOf(
    Triple("What should I pack?", "Start with essentials, then pack interchangeable layers. Add your charger, toiletries and any medication; check your route and luggage rules before leaving.", listOf("pack","luggage","suitcase","行李","打包","收拾","荷物","짐","maleta","bagage","packen","bagagem")),
    Triple("What if it rains?", "Pack a light waterproof layer, a small umbrella and a dry bag for electronics. Choose shoes that dry quickly and keep a flexible indoor plan. Check the local forecast before you go.", listOf("rain","weather","下雨","天气","天氣","雨","날씨","비가","비옷","비 오","lluvia","pluie","regen","chuva")),
    Triple("How do I keep a travel journal?", "Capture one small moment each day: where you were, what surprised you and how it felt. Add a photo or a ticket, then leave a note for your next visit.", listOf("journal","memory","diary","手账","手帳","日记","日記","回忆","回憶","思い出","기록","diario","souvenir","tagebuch","diário")),
    Triple("What should I check before flying?", "Check your passport and entry requirements with official sources, then confirm baggage rules and flight times with your airline. Keep documents, medication and a charger in your carry-on.", listOf("flight","fly","passport","airport","飞机","飛機","護照","护照","航班","飛行機","여권","비행","vuelo","vol","flug","voo")),
    Triple("How can I plan a travel budget?", "Start with transport and accommodation, then set a daily amount for food and activities. Keep a separate buffer for changes, and note the currency and payment fees.", listOf("budget","money","cost","预算","預算","花费","花費","予算","예산","presupuesto","custo","orçamento")),
    Triple("What should I pack for the beach?", "Bring sun protection, a hat, water and a quick-dry layer. Add a small waterproof pouch and check local conditions before swimming.", listOf("beach","sea","seaside","海边","海邊","海滩","海灘","海水浴","해변","playa","plage","strand","praia")))
@Composable fun GuideScreen(tr: (String)->String, prefs: SharedPreferences, billing: PlayProducts, openMembership: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }; var all by rememberSaveable { mutableStateOf(false) }; var message by rememberSaveable { mutableStateOf(arrayListOf<String>()) }; var notice by rememberSaveable { mutableStateOf("") }
    var pendingAnswer by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun normalize(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    fun usedFreeToday(): Boolean {
        val last = prefs.getLong("guide_last_question_at", 0L)
        return if (last > 0) Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        else prefs.getString("guide_day", "") == LocalDate.now().toString()
    }
    LaunchedEffect(billing.isMember) { notice = "" }
    LaunchedEffect(pendingAnswer) {
        val answer = pendingAnswer ?: return@LaunchedEffect
        delay(1_000)
        message = ArrayList(message + answer); pendingAnswer = null
    }
    LaunchedEffect(message.size, pendingAnswer, notice) { if (message.isNotEmpty() || notice.isNotEmpty()) { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) } }
    LaunchedEffect(keyboardVisible) { if (keyboardVisible) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    fun ask(q: String) {
        val text = q.trim(); if (text.isEmpty() || pendingAnswer != null) return
        if (billing.configured && !billing.ready) { notice = "Checking membership. Please try again in a moment."; return }
        if (!billing.isMember && usedFreeToday()) { notice = "You have used today's free question. Come back tomorrow or become a member."; return }
        if (!billing.isMember) prefs.edit().putLong("guide_last_question_at", System.currentTimeMillis()).putString("guide_day", LocalDate.now().toString()).apply()
        val normalized = normalize(text)
        val found = guide.firstOrNull { normalize(tr(it.first)) == normalized } ?: listOf(1,3,4,5,2,0).map { guide[it] }.firstOrNull { topic -> topic.third.any { normalize(it) in normalized } }
        val fallback = "I can help with packing, rain, flights, budgets, beaches and travel journals. Try one of these topics."
        message = ArrayList(message + text); pendingAnswer = tr(found?.second ?: fallback); draft=""; notice=""
    }
    ScrollPage(scroll) { Hero("guide_hero"); ContentColumn {
        if(message.isEmpty()) {
            Head(tr("Ask about packing, planning or journaling."))
            guide.take(if(all) 6 else 4).chunked(2).forEach { questions ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    questions.forEach { question ->
                        SurfaceCard(Modifier.weight(1f).defaultMinSize(minHeight = 76.dp).clickable { ask(tr(question.first)) }) {
                            Text(tr(question.first), color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            }
            Action(if(all) "Show fewer questions" else "More travel questions",tr,false) { all=!all }
        }
        message.forEachIndexed { index,text -> SurfaceCard(Modifier.fillMaxWidth(),if(index % 2 == 0) "button_selected" else "button_paper") { SelectionContainer { Text(text,color=ink) } } }
        if (pendingAnswer != null) Caption(tr("Preparing a tip…"))
        if(notice.isNotEmpty()) { Caption(tr(notice)); if ((!billing.configured || billing.ready) && !billing.isMember && usedFreeToday()) Action("Satchel Membership",tr,false,onClick=openMembership) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { Field(tr("Ask a travel question"),draft,{draft=it}, multiline = true, placeholder = tr("Your travel question…"), minHeight = 48.dp) }
            Action("Send",tr,artwork="action_send") { ask(draft) }
        }
    } }
}
@Composable private fun SettingsRow(label: String, tr: (String)->String, artwork: String? = null, detail: String? = null, onClick: ()->Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (artwork != null) Artwork(artwork, Modifier.padding(end = 10.dp).size(34.dp))
        Text(tr(label), Modifier.weight(1f), color = ink, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, Modifier.padding(start = 8.dp), color = muted, fontSize = 13.sp)
        Artwork("next_mark", Modifier.padding(start = 8.dp).size(24.dp))
    }
}
@Composable fun SettingsScreen(store: TravelStore, tr: (String)->String, language: String, setLanguage: (String)->Unit, open: (String)->Unit, ads: PortalAds, backend: PortalBackend, billing: PlayProducts) {
    val context = LocalContext.current
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    var restoreRequested by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) store.export(uri) }
    ScrollPage { Hero("collection_coast"); ContentColumn {
        SurfaceCard(Modifier.fillMaxWidth(), "identity_ticket") { Caption(tr("User ID")); SelectionContainer { Head(backend.session?.userId ?: tr("Not available yet")) } }
        SurfaceCard(Modifier.fillMaxWidth(), "collection_paper_v2") {
            Text(tr("Your collection"), color=muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Head("${store.trips.size}  ${tr("Trips")}", Modifier.weight(1f))
                Head("${store.trips.sumOf { it.entries.size }}  ${tr("Memories")}", Modifier.weight(1f))
            }
            Caption("${store.tripPhotoCount()}  ${tr("Photos")}")
        }
        SurfaceCard(Modifier.fillMaxWidth(), "coastal_paper") {
            SettingsRow("Satchel Membership",tr,"membership_art") { open("membership") }
            Caption(tr("More room for every journey."))
        }
        SurfaceCard(Modifier.fillMaxWidth()) {
        SettingsRow("Common list",tr,"sea_trips") { open("template") }
        SettingsRow("Export your journal",tr,"sea_journal") { export.launch("Satchel.json") }
        SettingsRow("Share your journal",tr,"sea_journal") {
            var file: File? = null
            try {
                val directory = File(context.cacheDir, "exports").apply { mkdirs() }
                val shareFile = File.createTempFile("Satchel-", ".json", directory); file = shareFile
                val uri = FileProvider.getUriForFile(context, context.packageName + ".photos", shareFile)
                if (store.export(uri)) {
                    val intent = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).apply {
                        clipData = ClipData.newRawUri("Satchel", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, tr("Share your journal")))
                } else shareFile.delete()
            } catch (_: Exception) { file?.delete(); store.error = "Your journal could not be exported. Please try again." }
        }
        }
        SurfaceCard(Modifier.fillMaxWidth()) {
        SettingsRow("Restore purchases",tr) { restoreRequested = true; billing.refreshPurchases() }
        if (restoreRequested) Caption(tr(billing.status))
        if (ads.privacyRequired) SettingsRow("Privacy options",tr) { ads.privacyOptions() }
        SettingsRow("Privacy policy",tr) { open("privacy") }; SettingsRow("Terms of use",tr) { open("terms") }
        }
        val languages = listOf("en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語", "ko" to "한국어", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português")
        SurfaceCard(Modifier.fillMaxWidth()) {
            SettingsRow("Language",tr, detail = languages.firstOrNull { it.first == language }?.second ?: "English") { showLanguages = !showLanguages }
            if (showLanguages) languages.forEach { (code,label) -> SettingsRow(label,{it}, detail = if (language == code) tr("Selected") else null) { setLanguage(code); showLanguages=false } }
        }
    } }
}
@Composable fun LegalScreen(page: String, tr: (String)->String, back: ()->Unit) {
    var local by rememberSaveable(page) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Back(tr,back)
        Action(if (local) "View online policy" else "Android details",tr,false) { local = !local }
        key(page, local) {
            AndroidView(factory={ ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled=false; settings.allowFileAccess=false; settings.allowContentAccess=false
                if (local) {
                    loadDataWithBaseURL(null,ctx.assets.open(if(page=="privacy") "Privacy.html" else "Terms.html").bufferedReader().use { it.readText() },"text/html","UTF-8",null)
                } else {
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) local = true }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) { if (request.isForMainFrame && response.statusCode >= 400) local = true }
                    }
                    loadUrl(if(page=="privacy") "https://www.satchel.click/privacy-policy.html" else "https://www.satchel.click/terms-of-use.html")
                }
            } },modifier=Modifier.weight(1f),onRelease={ it.stopLoading(); it.destroy() })
        }
    }
}
@Composable fun MembershipScreen(tr: (String)->String, billing: PlayProducts, open: (String)->Unit, back: ()->Unit) {
    val context = LocalContext.current
    ScrollPage { ContentColumn {
        Back(tr,back); Artwork("membership_art",Modifier.fillMaxWidth().height(200.dp)); Head(tr("Satchel Membership")); Caption(tr("More room for every journey.")); Caption(tr(billing.status))
        SurfaceCard(Modifier.fillMaxWidth()) { Caption(tr("Free: up to 3 trips and 30 photos. Membership: unlimited trips and photos.")); Caption(tr("Free: one travel question per day. Membership: unlimited questions.")); Caption(tr("Your saved trips remain available to view and export even if membership ends.")) }
        listOf(MONTHLY, YEARLY).forEach { id ->
            val monthly = id == MONTHLY
            val offer = billing.offers.firstOrNull { it.id == id }
            val period = tr(if (monthly) "month" else "year")
            SurfaceCard(Modifier.fillMaxWidth()) {
                Head(tr(if (monthly) "Monthly membership" else "Yearly membership"))
                if (offer != null) Text("${offer.price} / $period", color=ink)
                else { Text((if(monthly) "US$9.99" else "US$99.99") + " / " + period, color=ink); Caption(tr("Planned price in USD")) }
                if (offer != null && !billing.isMember && billing.ready) Action("Subscribe",tr) { (context as? Activity)?.let { billing.buy(it,id) } }
                else if (offer == null) Action("Refresh products",tr,false) { billing.queryProducts() }
            }
        }
        Caption(tr("Subscriptions renew automatically until cancelled in Google Play."))
        Action("Restore purchases",tr,false) { billing.refreshPurchases() }
        Action("Manage subscriptions",tr,false) { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) } catch (_: Exception) { } }
        Action("Privacy policy",tr,false) { open("privacy") }; Action("Terms of use",tr,false) { open("terms") }
    } }
}
