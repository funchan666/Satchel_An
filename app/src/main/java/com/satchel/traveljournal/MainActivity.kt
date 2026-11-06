package com.satchel.traveljournal

import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import org.json.JSONObject
import java.time.LocalDate

val ink = Color(0xFF1F3345); val muted = Color(0xFF596B75); val ocean = Color(0xFF0847A3); val cream = Color(0xFFFFFCF5); val paper = Color(0xFFF7F9F5)
class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SatchelApp() } } }

@Composable fun LocalApp(billing: PlayProducts, ads: PortalAds, backend: PortalBackend) {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context as? Activity
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = true
        onDispose { if (previous != null) controller?.isAppearanceLightStatusBars = previous }
    }
    val store = remember { TravelStore(context, billing) }
    val prefs = remember { context.getSharedPreferences("satchel", 0) }
    var language by remember { mutableStateOf(prefs.getString("language", "en") ?: "en") }
    val translations = remember { JSONObject(context.assets.open("translations.json").bufferedReader().use { it.readText() }) }
    val languages = listOf("en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt")
    val tr: (String) -> String = { key -> translations.optJSONArray(key)?.optString(languages.indexOf(language).coerceAtLeast(0), key) ?: key }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableStateOf("home") }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var itemId by rememberSaveable { mutableStateOf("") }
    var entryId by rememberSaveable { mutableStateOf("") }
    var packingPhotoId by rememberSaveable { mutableStateOf("") }
    var entryReturnPage by rememberSaveable { mutableStateOf("trip") }
    var legalReturnPage by rememberSaveable { mutableStateOf("home") }
    var detailSection by rememberSaveable { mutableIntStateOf(0) }
    val tabStates = rememberSaveableStateHolder()
    val trip = store.trips.firstOrNull { it.id == selectedId }
    fun goBack() { page = when (page) { "trip", "new", "settings", "guide" -> "home"; "item", "tripEdit", "lessons", "packingPhotos" -> "trip"; "entry" -> entryReturnPage; "privacy", "terms" -> legalReturnPage; "template", "membership" -> "home"; else -> "home" } }
    BackHandler(page != "home") { goBack() }
    Box(Modifier.fillMaxSize().background(paper).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Artwork("page_linen", Modifier.fillMaxSize(), ContentScale.Crop, alpha = .18f)
        Column(Modifier.fillMaxSize()) {
            store.error?.let { error -> Row(Modifier.fillMaxWidth().background(cream).padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(tr(error), Modifier.weight(1f), color = ink); Action("OK", tr, false) { store.error = null } } }
            Box(Modifier.weight(1f)) {
                when (page) {
                    "home" -> tabStates.SaveableStateProvider("tab-$tab") { when (tab) {
                        0 -> TripsScreen(store, tr, { page = "new" }, { selectedId = it; detailSection = 0; page = "trip" }, { tab = it })
                        1 -> JournalScreen(store, tr, { page = "new" }, { selectedId = it; detailSection = 1; page = "trip" }, { selectedId = it; entryId = ""; entryReturnPage = "home"; page = "entry" })
                        2 -> GuideScreen(tr, prefs, billing) { page = "membership" }
                        else -> SettingsScreen(store, tr, language, { language = it; prefs.edit().putString("language", it).apply() }, { legalReturnPage = "home"; page = it }, ads, backend, billing)
                    } }
                    "new" -> TripEditor(store, tr, null, { page = "home" })
                    "trip" -> if (trip != null) TripScreen(store, tr, trip, detailSection, { detailSection = it }, { page = "home" }, { page = "tripEdit" }, { itemId = it; page = "item" }, { entryId = it; entryReturnPage = "trip"; page = "entry" }, { page = it }, { packingPhotoId = it; page = "packingPhotos" }) else { page = "home" }
                    "tripEdit" -> if (trip != null) TripEditor(store, tr, trip, { page = if (store.trips.any { it.id == selectedId }) "trip" else "home" })
                    "item" -> if (trip != null) ItemEditor(store, tr, trip, trip.items.firstOrNull { it.id == itemId }, { page = "trip" })
                    "entry" -> if (trip != null) EntryEditor(store, tr, trip, trip.entries.firstOrNull { it.id == entryId }, { page = entryReturnPage })
                    "lessons" -> if (trip != null) LessonsEditor(store, tr, trip) { page = "trip" }
                    "packingPhotos" -> if (trip != null) PackingPhotosScreen(store, tr, trip, packingPhotoId) { page = "trip" }
                    "template" -> TemplateScreen(store, tr) { page = "home" }
                    "privacy", "terms" -> LegalScreen(page, tr) { page = legalReturnPage }
                    "membership" -> MembershipScreen(tr, billing, { legalReturnPage = "membership"; page = it }) { page = "home" }
                }
            }
            if (page == "home" && WindowInsets.ime.getBottom(LocalDensity.current) == 0) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).background(cream.copy(alpha=.9f), RoundedCornerShape(24.dp)), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("nav_trips", "sea_journal", "nav_guide", "nav_me").forEachIndexed { index, art ->
                    val label = listOf("Trips", "Journal", "Travel guide", "Me")[index]
                    Box(Modifier.weight(1f).height(58.dp).clickable { tab = index }.semantics { contentDescription = tr(label); selected = tab == index }, contentAlignment = Alignment.Center) { Artwork(art, Modifier.size(45.dp), alpha = if (tab == index) 1f else .52f) }
                }
            }
        }
    }
}
@Composable fun Artwork(name: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Fit, alpha: Float = 1f) {
    val ctx = LocalContext.current; val res = remember(name) { ctx.resources.getIdentifier(name, "drawable", ctx.packageName) }
    if (res != 0) Image(painterResource(res), null, modifier, contentScale = scale, alpha = alpha)
}
@Composable fun Photo(store: TravelStore, name: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fallback: Boolean = true) {
    val file = store.photoFile(name)
    val bitmap = remember(name) { file?.takeIf { it.exists() }?.let { image ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let { decoded ->
            val orientation = try { ExifInterface(image.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (transform.isIdentity) decoded.asImageBitmap() else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).asImageBitmap()
        }
    } }
    if (bitmap != null) Image(bitmap, null, modifier, contentScale = scale) else if (fallback) Artwork("cover_paper_v2", modifier, ContentScale.Crop)
}
@Composable fun Head(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = ink, fontFamily = FontFamily.Serif, fontSize = 22.sp) }
@Composable fun Caption(text: String) { Text(text, color = muted, fontSize = 14.sp) }
@Composable fun Action(label: String, tr: (String)->String, primary: Boolean = true, artwork: String? = null, onClick: () -> Unit) {
    Box(Modifier.defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Artwork(if (primary) "control_ocean_v3" else "control_paper_v3", Modifier.matchParentSize(), ContentScale.Crop)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) { if (artwork != null) Artwork(artwork, Modifier.size(28.dp)); Text(tr(label), color = if (primary) Color.White else ink, fontWeight = FontWeight.SemiBold) }
    }
}
@Composable fun SurfaceCard(modifier: Modifier = Modifier, art: String = "button_paper", content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp))) { Artwork(art, Modifier.matchParentSize(), ContentScale.Crop); Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
@Composable fun Field(label: String, value: String, onChange: (String)->Unit, multiline: Boolean = false, placeholder: String = label, minHeight: Dp = if (multiline) 130.dp else 48.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight).background(cream, RoundedCornerShape(12.dp)).padding(12.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = ink, fontSize = 16.sp), singleLine = !multiline,
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, color = muted, fontSize = 16.sp); inner() } })
    }
}
@Composable fun Hero(art: String, height: Int = 250) { Artwork(art, Modifier.fillMaxWidth().height(height.dp), ContentScale.Crop) }
@Composable fun ScrollPage(state: ScrollState = rememberScrollState(), content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(state), horizontalAlignment = Alignment.CenterHorizontally) { Column(Modifier.fillMaxWidth().widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) } }
@Composable fun Back(tr: (String)->String, action: ()->Unit) { Box(Modifier.size(width = 72.dp, height = 48.dp).clickable(onClick=action).semantics { contentDescription = tr("Back") }) { Artwork("back_tag", Modifier.fillMaxSize()) } }
@Composable fun ContentColumn(content: @Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content) }
