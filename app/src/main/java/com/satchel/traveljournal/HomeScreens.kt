package com.satchel.traveljournal

import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate

@Composable fun TripsScreen(store: TravelStore, tr: (String)->String, create: ()->Unit, open: (String)->Unit, switch: (Int)->Unit) {
    var query by remember { mutableStateOf("") }; var favorites by remember { mutableStateOf(false) }
    val sorted = store.trips.sortedByDescending { it.start }
    val matches = sorted.filter { (!favorites || it.favorite) && (it.destination.contains(query, true) || it.entries.any { e -> e.text.contains(query, true) }) }
    ScrollPage { Hero("coast_notebook"); ContentColumn {
        Field(tr("Search journeys and memories"), query, { query = it })
        if (sorted.isEmpty()) SurfaceCard(Modifier.fillMaxWidth(), "journey_paper") { Head(tr("Your next journey starts here.")); Caption(tr("Keep your packing list and memories in one place.")); Action("New Trip", tr, artwork="action_add", onClick=create) }
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
@Composable fun TripCard(store: TravelStore, trip: Trip, tr: (String)->String, journal: Boolean = false, open: ()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cream).clickable(onClick=open)) {
        Box { Photo(store, trip.cover, Modifier.fillMaxWidth().height(180.dp)); if (trip.favorite) Artwork("favorite_on", Modifier.padding(8.dp).size(48.dp)) }
        Column(Modifier.padding(17.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { Head(trip.destination); Caption("${trip.start} — ${trip.end}"); Caption(if(journal) "${trip.entries.size} · ${tr("Memories")}" else "${trip.items.count { it.packed }} / ${trip.items.size} · ${tr("Packing")}") }
    }
}
@Composable fun JournalScreen(store: TravelStore, tr: (String)->String, create: ()->Unit, open: (String)->Unit, add: (String)->Unit) {
    var query by remember { mutableStateOf("") }; val trips = store.trips.sortedByDescending { it.start }
    ScrollPage { Hero("journal_coast", 220); ContentColumn {
        if (trips.isNotEmpty()) { Field(tr("Search journeys and memories"), query, { query=it }); Text(tr("Choose a journey to add a memory"), color=muted); trips.forEach { Action(it.destination, { s->s }, false) { add(it.id) } } }
        val matches = trips.filter { it.entries.isNotEmpty() && (it.destination.contains(query,true) || it.entries.any { e->e.text.contains(query,true) }) }
        if (matches.isEmpty()) {
            Head(tr(if (query.isEmpty()) "Your memories start here." else "No matching memories"))
            if (trips.isEmpty()) Action("New Trip", tr, artwork="action_add", onClick=create)
            if (query.isEmpty()) { Head(tr("A little inspiration")); listOf("journal_idea_place" to "A place worth remembering", "journal_idea_moment" to "A moment that made you smile", "journal_idea_photo" to "A photo and the story behind it").forEach { (art, title)-> Row(verticalAlignment=Alignment.CenterVertically) { Artwork(art,Modifier.size(64.dp)); Text(tr(title),Modifier.padding(start=12.dp), color=ink) } } }
        } else matches.forEach { TripCard(store,it,tr,true) { open(it.id) } }
    } }
}
private val guide = listOf(
    Triple("What should I pack?", "Start with essentials, then pack interchangeable layers. Add your charger, toiletries and any medication; check your route and luggage rules before leaving.", listOf("pack","luggage","行李","打包","荷物","짐","maleta","bagage","packen","bagagem")),
    Triple("What if it rains?", "Pack a light waterproof layer, a small umbrella and a dry bag for electronics. Choose shoes that dry quickly and keep a flexible indoor plan. Check the local forecast before you go.", listOf("rain","weather","下雨","天气","天氣","雨","날씨","비가","비옷","비 오","lluvia","pluie","regen","chuva")),
    Triple("How do I keep a travel journal?", "Capture one small moment each day: where you were, what surprised you and how it felt. Add a photo or a ticket, then leave a note for your next visit.", listOf("journal","memory","diary","手账","手帳","日记","日記","回忆","回憶","思い出","기록","diario","souvenir","tagebuch","diário")),
    Triple("What should I check before flying?", "Check your passport and entry requirements with official sources, then confirm baggage rules and flight times with your airline. Keep documents, medication and a charger in your carry-on.", listOf("flight","fly","passport","airport","飞机","飛機","護照","护照","航班","飛行機","여권","비행","vuelo","vol","flug","voo")),
    Triple("How can I plan a travel budget?", "Start with transport and accommodation, then set a daily amount for food and activities. Keep a separate buffer for changes, and note the currency and payment fees.", listOf("budget","money","cost","预算","預算","花费","花費","予算","예산","presupuesto","custo","orçamento")),
    Triple("What should I pack for the beach?", "Bring sun protection, a hat, water and a quick-dry layer. Add a small waterproof pouch and check local conditions before swimming.", listOf("beach","sea","海边","海邊","海滩","海灘","海水浴","해변","playa","plage","strand","praia")))
@Composable fun GuideScreen(tr: (String)->String, prefs: SharedPreferences, billing: PlayProducts) {
    var draft by remember { mutableStateOf("") }; var all by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<List<Pair<Boolean,String>>>(emptyList()) }; var notice by remember { mutableStateOf("") }
    fun ask(q: String) {
        val text = q.trim(); if (text.isEmpty()) return
        if (billing.configured && !billing.ready) { notice = tr("Checking membership. Please try again in a moment."); return }
        if (!billing.isMember && prefs.getString("guide_day", "") == LocalDate.now().toString()) { notice = tr("You have used today's free question. Come back tomorrow or become a member."); return }
        if (!billing.isMember) prefs.edit().putString("guide_day", LocalDate.now().toString()).apply()
        val found = guide.firstOrNull { tr(it.first).equals(text, true) } ?: listOf(1,3,4,5,2,0).map { guide[it] }.firstOrNull { topic -> topic.third.any { text.contains(it,true) } }
        val fallback = "I can help with packing, rain, flights, budgets, beaches and travel journals. Try one of these topics."
        message = message + (true to text) + (false to tr(found?.second ?: fallback)); draft=""; notice=""
    }
    ScrollPage { Hero("guide_hero"); ContentColumn {
        if(message.isEmpty()) { Head(tr("Ask about packing, planning or journaling.")); guide.take(if(all) 6 else 4).forEach { Action(it.first,tr,false) { ask(tr(it.first)) } }; Action(if(all) "Show fewer questions" else "More travel questions",tr,false) { all=!all } }
        message.forEach { (user,text)-> SurfaceCard(Modifier.fillMaxWidth(),if(user) "button_selected" else "button_paper") { Text(text,color=ink) } }
        if(notice.isNotEmpty()) Caption(notice)
        Field(tr("Ask a travel question"),draft,{draft=it},true); Action("Send",tr,artwork="action_send") { ask(draft) }
    } }
}
@Composable fun SettingsScreen(store: TravelStore, tr: (String)->String, language: String, setLanguage: (String)->Unit, open: (String)->Unit, ads: PortalAds, backend: PortalBackend) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri != null) store.export(uri) }
    ScrollPage { Hero("collection_coast"); ContentColumn {
        SurfaceCard(Modifier.fillMaxWidth(), "collection_paper_v2") { Caption(tr("User ID") + ": " + (backend.session?.userId ?: tr("Not available yet"))); Text(tr("Your collection"), color=muted); Head("${store.trips.size}  ${tr("Trips")}     ${store.trips.sumOf { it.entries.size }}  ${tr("Memories")}") }
        Action("Satchel Membership",tr,false,"membership_art") { open("membership") }
        Action("Common list",tr,false,"sea_trips") { open("template") }
        Action("Export your journal",tr,false,"sea_journal") { export.launch("Satchel.json") }
        Action("Restore purchases",tr,false) { open("membership") }
        if (ads.privacyRequired) Action("Privacy options",tr,false) { ads.privacyOptions() }
        Action("Privacy policy",tr,false) { open("privacy") }; Action("Terms of use",tr,false) { open("terms") }
        Head(tr("Language")); listOf("en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語", "ko" to "한국어", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "pt" to "Português").forEach { (code,label) -> Action(label,{it},language==code) { setLanguage(code) } }
    } }
}
@Composable fun LegalScreen(page: String, tr: (String)->String, back: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Back(tr,back)
        AndroidView(factory={ ctx -> WebView(ctx).apply {
            settings.javaScriptEnabled=false; settings.allowFileAccess=false; settings.allowContentAccess=false
            loadDataWithBaseURL(null,ctx.assets.open(if(page=="privacy") "Privacy.html" else "Terms.html").bufferedReader().use { it.readText() },"text/html","UTF-8",null)
        } },modifier=Modifier.weight(1f))
    }
}
@Composable fun MembershipScreen(tr: (String)->String, billing: PlayProducts, back: ()->Unit) {
    val context = LocalContext.current
    ScrollPage { ContentColumn {
        Back(tr,back); Artwork("membership_art",Modifier.fillMaxWidth().height(200.dp)); Head(tr("Satchel Membership")); Caption(tr("More room for every journey.")); Caption(billing.status)
        billing.offers.forEach { offer ->
            val period = when (offer.period) { "P1M" -> tr("Monthly"); "P1Y" -> tr("Yearly"); else -> offer.period }
            SurfaceCard(Modifier.fillMaxWidth()) { Head(period); Text(offer.price, color=ink); if (!billing.isMember && billing.ready) Action("Subscribe",tr) { (context as? Activity)?.let { billing.buy(it,offer.id) } } }
        }
        Caption("Subscriptions renew automatically until cancelled in Google Play.")
        Action("Restore purchases",tr,false) { billing.refreshPurchases() }
        Action("Manage subscriptions",tr,false) { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) } catch (_: Exception) { } }
    } }
}
