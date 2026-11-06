package com.satchel.traveljournal

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable fun PhotoActions(store: TravelStore, tr: (String)->String, label: String = "Add photo", onPhoto: (String)->Unit) {
    val context = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val library = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if(uri != null) store.savePhoto(uri)?.let(onPhoto) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pending
        try {
            if (ok && path != null) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".photos", File(path))
                store.savePhoto(uri)?.let(onPhoto)
            }
        } catch (_: Exception) { store.error = "Could not load the photo. Please try again." }
        finally { path?.let { File(it).delete() }; pending = null }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Action(label,tr,false,"action_photo") { library.launch("image/*") }
        Action("Camera",tr,false,"action_camera") {
            try {
                val folder = File(context.cacheDir,"camera").apply { mkdirs() }
                val file = File.createTempFile("capture-", ".jpg", folder)
                pending = file.absolutePath
                camera.launch(FileProvider.getUriForFile(context, context.packageName + ".photos", file))
            } catch (_: Exception) { pending?.let { File(it).delete() }; pending = null; store.error = "Camera access is unavailable. Check your device settings." }
        }
    }
}

@Composable fun TripScreen(store: TravelStore, tr: (String)->String, trip: Trip, section: Int, selectSection: (Int)->Unit, back: ()->Unit, editTrip: ()->Unit, editItem: (String)->Unit, editEntry: (String)->Unit, navigate: (String)->Unit, openPackingPhoto: (String)->Unit) {
    var returning by rememberSaveable { mutableStateOf(false) }; var onlyRemaining by rememberSaveable { mutableStateOf(false) }; var saved by rememberSaveable { mutableStateOf(false) }
    ScrollPage { ContentColumn {
        Back(tr,back); Photo(store,trip.cover,Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)))
        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Head(trip.destination); Caption("${dateLabel(trip.start)} — ${dateLabel(trip.end)}") }; Box(Modifier.size(52.dp).clickable { store.update(trip.copy(favorite=!trip.favorite)) }.semantics { contentDescription = tr(if (trip.favorite) "Remove from favorites" else "Save trip to favorites") }) { Artwork(if(trip.favorite) "favorite_on" else "favorite_off",Modifier.fillMaxSize()) } }
        Action("Edit trip",tr,false) { editTrip() }
        PhotoActions(store,tr,"Change cover") { name ->
            val current = store.trips.firstOrNull { it.id == trip.id }
            if (current == null || !store.update(current.copy(cover=name))) store.discardPhoto(name)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Packing", "Journal").forEachIndexed { i, label -> Action(label,tr,section==i) { selectSection(i) } } }
        if(section == 0) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Action("Packing",tr,!returning) { returning=false }; Action("Returning",tr,returning) { returning=true } }
            Caption("${trip.items.count { (!returning || it.needsReturn) && (if(returning) it.returned else it.packed) }} / ${trip.items.count { !returning || it.needsReturn }}")
            Action(if (onlyRemaining) "Show all" else "Show remaining",tr,onlyRemaining,"action_filter") { onlyRemaining=!onlyRemaining }
            Action("Add item",tr,artwork="action_add") { editItem("") }
            val list = trip.items.filter { !onlyRemaining || ((!returning || it.needsReturn) && !(if(returning) it.returned else it.packed)) }
            if (onlyRemaining && list.isEmpty()) Caption(tr("Nothing left to check."))
            list.groupBy { it.displayCategory(tr) }.toSortedMap().forEach { (category,items)->
                Head(category); items.forEach { item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cream).padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clickable(enabled = !returning || item.needsReturn) { val updated=if(returning) item.copy(returned=!item.returned) else item.copy(packed=!item.packed); store.update(trip.copy(items=trip.items.map { if(it.id==item.id) updated else it })) }.semantics { contentDescription = item.displayName(tr); stateDescription = tr(if (if(returning) item.returned else item.packed) "Selected" else "Not selected") }) { Artwork(if(if(returning) item.returned else item.packed) "sea_check_on" else "sea_check_off",Modifier.fillMaxSize()) }
                        Column(Modifier.weight(1f).clickable { editItem(item.id) }.padding(8.dp)) { Text(item.displayName(tr), color=ink, fontWeight=FontWeight.SemiBold); Caption("${item.quantity} ${if(item.location.isNotBlank()) "· ${item.location}" else ""}"); if (returning && !item.needsReturn) Caption(tr("Not returning")) }
                    }
                }
            }
            Action("Save as common list",tr,false) { if(store.saveTemplate(trip.items.map { it.copy(id=UUID.randomUUID().toString(),packed=false,returned=false,photo=null) })) saved=true }
            if(saved) Caption(tr("Common list saved"))
            Head(tr("Packing photos"))
            if (trip.packingPhotos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                trip.packingPhotos.forEach { name -> Photo(store,name,Modifier.size(width=130.dp,height=105.dp).clip(RoundedCornerShape(10.dp)).clickable { openPackingPhoto(name) }) }
            }
            PhotoActions(store,tr) { name ->
                val current = store.trips.firstOrNull { it.id == trip.id }
                if (current == null || !store.update(current.copy(packingPhotos=current.packingPhotos+name))) store.discardPhoto(name)
            }
            Action("Packing photos",tr,false,"action_photo") { openPackingPhoto("") }
            SurfaceCard(Modifier.fillMaxWidth().clickable { navigate("lessons") }) { Head(tr("Notes for next time")); if(trip.lessons.isNotBlank()) Caption(trip.lessons) }
        } else {
            Action("Add a memory",tr,artwork="action_add") { editEntry("") }
            if(trip.entries.isEmpty()) { Artwork("sea_journal",Modifier.fillMaxWidth().height(180.dp)); Head(tr("Your memories start here.")) }
            trip.entries.sortedByDescending { it.date }.groupBy { it.date.take(10) }.forEach { (day, entries) ->
                Head(dateLabel(day, long=true))
                entries.forEach { entry -> SurfaceCard(Modifier.fillMaxWidth().clickable { editEntry(entry.id) }) { Caption(timeLabel(entry.date)); entry.photos.forEach { Photo(store,it,Modifier.fillMaxWidth().heightIn(max=600.dp).clip(RoundedCornerShape(12.dp)),ContentScale.Fit, fallback=false) }; if (entry.text.isNotEmpty()) Text(entry.text,color=ink) } }
            }
        }
    } }
}
private fun date(value: String): LocalDate? = try { LocalDate.parse(value,DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { null }
@Composable fun TripEditor(store: TravelStore, tr: (String)->String, old: Trip?, done: ()->Unit) {
    var destination by rememberSaveable(old?.id) { mutableStateOf(old?.destination ?: "") }; var start by rememberSaveable(old?.id) { mutableStateOf(old?.start ?: LocalDate.now().toString()) }; var end by rememberSaveable(old?.id) { mutableStateOf(old?.end ?: LocalDate.now().toString()) }
    var kind by rememberSaveable { mutableStateOf(old?.kind ?: "leisure") }; var source by rememberSaveable { mutableStateOf("suggested") }; var cover by rememberSaveable { mutableStateOf(old?.cover) }; var invalid by rememberSaveable { mutableStateOf(false) }; var confirm by rememberSaveable { mutableStateOf(false) }
    fun cancel() { if(cover!=old?.cover) store.discardPhoto(cover); done() }
    BackHandler { cancel() }
    ScrollPage { ContentColumn {
        Back(tr) { cancel() }; Head(tr(if(old==null) "Create trip" else "Edit trip")); Head(tr("Cover photo")); Photo(store,cover,Modifier.fillMaxWidth().height(165.dp).clip(RoundedCornerShape(16.dp)))
        PhotoActions(store,tr,if(cover==null) "Add photo" else "Change cover") { name -> if(cover!=old?.cover) store.discardPhoto(cover); cover=name }
        if(cover!=null) Action("Remove cover",tr,false) { if(cover!=old?.cover) store.discardPhoto(cover); cover=null }
        Field(tr("Destination"),destination,{destination=it}); Field(tr("Start date")+" · YYYY-MM-DD",start,{start=it}); Field(tr("End date")+" · YYYY-MM-DD",end,{end=it})
        if(old==null) { Head(tr("Trip type")); Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf("weekend","leisure","business").forEach { k-> Action(k.replaceFirstChar { it.uppercase() },tr,kind==k) { kind=k } } }; Head(tr("Copy a previous list")); Action("Suggested list",tr,source=="suggested") { source="suggested" }; if(store.template.isNotEmpty()) Action("Common list",tr,source=="template") { source="template" }; store.trips.forEach { t -> Action(t.destination,{it},source==t.id) { source=t.id } } }
        if(invalid) Text(tr("Please check the destination and dates."),color=Color(0xFFA6472E))
        Action(if(old==null) "Create trip" else "Save",tr,artwork="action_save") {
            val from=date(start); val to=date(end)
            if(destination.isBlank() || from==null || to==null || to.isBefore(from)) invalid=true else {
                val sourceTrip=store.trips.firstOrNull { it.id==source }
                val list=when { old!=null -> old.items; source=="template" -> store.template; sourceTrip!=null -> sourceTrip.items; else -> suggested(kind,from,to) }.map { if(old==null) it.copy(id=UUID.randomUUID().toString(),packed=false,returned=false) else it }
                val next=(old ?: Trip(destination=destination.trim(),start=start,end=end)).copy(destination=destination.trim(),start=start,end=end,kind=kind,cover=cover,items=list,lessons=old?.lessons ?: sourceTrip?.lessons.orEmpty())
                if(store.update(next)) done()
            }
        }
        Action("Cancel",tr,false) { cancel() }
        if(old!=null) { if(confirm) { Caption(tr("Delete this trip and its records?")); Action("Delete",tr,false,"action_delete") { if(store.delete(old)) { store.discardPhoto(cover); done() } }; Action("Cancel",tr,false) { confirm=false } } else Action("Delete",tr,false,"action_delete") { confirm=true } }
    } }
}
@Composable fun ItemEditor(store: TravelStore, tr: (String)->String, trip: Trip, old: Item?, done: ()->Unit) {
    var name by rememberSaveable { mutableStateOf(old?.displayName(tr) ?: "") }; var category by rememberSaveable { mutableStateOf(old?.displayCategory(tr) ?: "") }; var quantity by rememberSaveable { mutableIntStateOf(old?.quantity ?: 1) }; var location by rememberSaveable { mutableStateOf(old?.location ?: "") }; var notes by rememberSaveable { mutableStateOf(old?.notes ?: "") }; var needsReturn by rememberSaveable { mutableStateOf(old?.needsReturn ?: true) }; var photo by rememberSaveable { mutableStateOf(old?.photo) }; var invalid by rememberSaveable { mutableStateOf(false) }; var confirm by rememberSaveable { mutableStateOf(false) }
    fun cancel() { if(photo!=old?.photo) store.discardPhoto(photo); done() }
    BackHandler { cancel() }
    ScrollPage { ContentColumn {
        Back(tr) { cancel() }; Hero("packing_editorial",110)
        Field(tr("Item name"),name,{name=it}); Field(tr("Category"),category,{category=it})
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text(tr("Quantity"),color=ink); Action("Less",tr,false) { quantity=(quantity-1).coerceAtLeast(1) }; Text("$quantity",color=ink); Action("More",tr,false) { quantity=(quantity+1).coerceAtMost(999) } }
        Field(tr("Where is it?"),location,{location=it}); Field(tr("Notes"),notes,{notes=it},true)
        Action("Bring back",tr,needsReturn) { needsReturn=!needsReturn }; PhotoActions(store,tr) { p -> if(photo!=old?.photo) store.discardPhoto(photo); photo=p }; if(photo!=null) Photo(store,photo,Modifier.fillMaxWidth().height(180.dp))
        if(invalid) Text(tr("Please enter a name and category."),color=Color(0xFFA6472E))
        Action("Save",tr,artwork="action_save") { if(name.isBlank() || category.isBlank()) invalid=true else { val item=(old ?: Item(name=name,category=category)).copy(name=name.trim(),category=category.trim(),quantity=quantity,location=location,notes=notes,photo=photo,needsReturn=needsReturn,suggestedNameKey=old?.suggestedNameKey?.takeIf { name.trim() == old?.displayName(tr) },suggestedCategoryKey=old?.suggestedCategoryKey?.takeIf { category.trim() == old?.displayCategory(tr) }); val list=if(old==null) trip.items+item else trip.items.map { if(it.id==item.id) item else it }; if(store.update(trip.copy(items=list))) done() } }
        Action("Cancel",tr,false) { cancel() }
        if(old!=null) { if(confirm) { Action("Delete",tr,false,"action_delete") { if(store.update(trip.copy(items=trip.items.filterNot { it.id==old.id }))) { store.discardPhoto(photo); done() } }; Action("Cancel",tr,false) { confirm=false } } else Action("Delete",tr,false,"action_delete") { confirm=true } }
    } }
}
@Composable fun EntryEditor(store: TravelStore, tr: (String)->String, trip: Trip, old: Entry?, done: ()->Unit) {
    val format=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT); var dateText by rememberSaveable { mutableStateOf(old?.date ?: LocalDateTime.now().format(format)) }; var text by rememberSaveable { mutableStateOf(old?.text ?: "") }; var photos by rememberSaveable { mutableStateOf(old?.photos ?: emptyList()) }; var invalid by rememberSaveable { mutableStateOf(false) }; var confirm by rememberSaveable { mutableStateOf(false) }
    fun cancel() { (photos-(old?.photos ?: emptyList()).toSet()).forEach { store.discardPhoto(it) }; done() }
    BackHandler { cancel() }
    ScrollPage { ContentColumn {
        Back(tr) { cancel() }; Hero("journal_editorial",110); Field(tr("Date")+" · YYYY-MM-DD HH:mm",dateText,{dateText=it}); Field(tr("Write a little something..."),text,{text=it},true)
        PhotoActions(store,tr) { photos=photos+it }; photos.forEach { p -> Row(verticalAlignment=Alignment.CenterVertically) { Photo(store,p,Modifier.size(90.dp)); Action("Delete",tr,false,"action_delete") { photos=photos-p; store.discardPhoto(p) } } }
        if(invalid) Text(tr("Please check the date and time."),color=Color(0xFFA6472E))
        Action("Save",tr,artwork="action_save") { val valid=try { LocalDateTime.parse(dateText,format); true } catch (_: Exception) { false }; if(!valid || (text.isBlank() && photos.isEmpty())) invalid=true else { val entry=(old ?: Entry(date=dateText)).copy(date=dateText,text=text,photos=photos); val entries=if(old==null) trip.entries+entry else trip.entries.map { if(it.id==entry.id) entry else it }; if(store.update(trip.copy(entries=entries))) done() } }
        Action("Cancel",tr,false) { cancel() }
        if(old!=null) { if(confirm) { Action("Delete",tr,false,"action_delete") { if(store.update(trip.copy(entries=trip.entries.filterNot { it.id==old.id }))) { cancel() } }; Action("Cancel",tr,false) { confirm=false } } else Action("Delete",tr,false,"action_delete") { confirm=true } }
    } }
}
@Composable fun LessonsEditor(store: TravelStore, tr: (String)->String, trip: Trip, done: ()->Unit) { var text by rememberSaveable { mutableStateOf(trip.lessons) }; ScrollPage { ContentColumn { Back(tr,done); Hero("journal_coast",150); Field(tr("Notes for next time"),text,{text=it},true); Action("Save",tr,artwork="action_save") { if(store.update(trip.copy(lessons=text))) done() }; Action("Cancel",tr,false, onClick=done) } } }
@Composable fun PackingPhotosScreen(store: TravelStore, tr: (String)->String, trip: Trip, initialPhoto: String, done: ()->Unit) {
    var editing by rememberSaveable { mutableStateOf(initialPhoto.takeIf { it in trip.packingPhotos }) }; var note by rememberSaveable { mutableStateOf(trip.packingPhotoNotes[initialPhoto].orEmpty()) }; var confirm by rememberSaveable { mutableStateOf(false) }
    fun closeEditor() { if (initialPhoto.isNotEmpty()) done() else editing = null }
    BackHandler(editing != null) { closeEditor() }
    ScrollPage { ContentColumn {
        Back(tr) { if (editing != null) closeEditor() else done() }; Head(tr("Packing photos"))
        if (editing == null) {
            PhotoActions(store,tr) { name -> if(!store.update(trip.copy(packingPhotos=trip.packingPhotos+name))) store.discardPhoto(name) }
            trip.packingPhotos.forEach { p -> Column(Modifier.clickable { editing=p; note=trip.packingPhotoNotes[p].orEmpty(); confirm=false }) { Photo(store,p,Modifier.fillMaxWidth().height(200.dp)); Caption(trip.packingPhotoNotes[p].orEmpty()) } }
        } else {
            val p = editing!!
            Photo(store,p,Modifier.fillMaxWidth().heightIn(max=600.dp),ContentScale.Fit,fallback=false)
            Field(tr("Notes"),note,{note=it},true)
            Action("Save",tr) { if(store.update(trip.copy(packingPhotoNotes=trip.packingPhotoNotes+(p to note)))) closeEditor() }
            Action("Cancel",tr,false) { closeEditor() }
            if(confirm) {
                Caption(tr("Delete this photo?"))
                Action("Delete",tr,false,"action_delete") { if(store.update(trip.copy(packingPhotos=trip.packingPhotos-p,packingPhotoNotes=trip.packingPhotoNotes-p))) closeEditor() }
                Action("Cancel",tr,false) { confirm=false }
            } else Action("Delete",tr,false,"action_delete") { confirm=true }
        }
    } }
}
@Composable fun TemplateScreen(store: TravelStore, tr: (String)->String, done: ()->Unit) {
    var items by rememberSaveable { mutableStateOf(store.template.map { it.copy(name = it.displayName(tr), category = it.displayCategory(tr)) }) }; var name by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("") }
    ScrollPage { ContentColumn { Back(tr,done); Hero("packing_editorial", 170); Head(tr("Common list")); items.forEach { item -> SurfaceCard(Modifier.fillMaxWidth()) { Field(tr("Item name"),item.name,{ value -> items=items.map { if(it.id==item.id) it.copy(name=value,suggestedNameKey=null) else it } }); Field(tr("Category"),item.category,{ value -> items=items.map { if(it.id==item.id) it.copy(category=value,suggestedCategoryKey=null) else it } }); Action("Delete",tr,false,"action_delete") { items=items.filterNot { it.id==item.id } } } }; Field(tr("Item name"),name,{name=it}); Field(tr("Category"),category,{category=it}); Action("Add item",tr,false,"action_add") { if(name.isNotBlank() && category.isNotBlank()) { items=items+Item(name=name.trim(),category=category.trim()); name=""; category="" } }; Action("Save",tr,artwork="action_save") { if(store.saveTemplate(items.filter { it.name.isNotBlank() && it.category.isNotBlank() })) done() } } }
}
