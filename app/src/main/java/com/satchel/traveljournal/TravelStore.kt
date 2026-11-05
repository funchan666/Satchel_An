package com.satchel.traveljournal

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

private fun id() = UUID.randomUUID().toString()
data class Item(val id: String = id(), val name: String, val category: String, val quantity: Int = 1, val location: String = "", val notes: String = "", val photo: String? = null, val packed: Boolean = false, val returned: Boolean = false, val needsReturn: Boolean = true, val suggestedNameKey: String? = null, val suggestedCategoryKey: String? = null)
data class Entry(val id: String = id(), val date: String, val text: String = "", val photos: List<String> = emptyList())
data class Trip(val id: String = id(), val destination: String, val start: String, val end: String, val kind: String = "leisure", val cover: String? = null, val favorite: Boolean = false, val items: List<Item> = emptyList(), val entries: List<Entry> = emptyList(), val packingPhotos: List<String> = emptyList(), val packingPhotoNotes: Map<String, String> = emptyMap(), val lessons: String = "")
private fun String?.jsonValue(): Any = this ?: JSONObject.NULL
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
private fun JSONObject.item() = Item(getString("id"), getString("name"), getString("category"), optInt("quantity", 1), optString("location"), optString("notes"), stringOrNull("photo"), optBoolean("packed"), optBoolean("returned"), optBoolean("needsReturn", true), stringOrNull("suggestedNameKey"), stringOrNull("suggestedCategoryKey"))
private fun Item.json() = JSONObject().put("id", id).put("name", name).put("category", category).put("quantity", quantity).put("location", location).put("notes", notes).put("photo", photo.jsonValue()).put("packed", packed).put("returned", returned).put("needsReturn", needsReturn).put("suggestedNameKey", suggestedNameKey.jsonValue()).put("suggestedCategoryKey", suggestedCategoryKey.jsonValue())
fun Item.displayName(tr: (String) -> String) = suggestedNameKey?.let(tr) ?: name
fun Item.displayCategory(tr: (String) -> String) = suggestedCategoryKey?.let(tr) ?: category
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
private fun JSONObject.entry() = Entry(getString("id"), getString("date"), optString("text"), optJSONArray("photos")?.strings() ?: emptyList())
private fun Entry.json() = JSONObject().put("id", id).put("date", date).put("text", text).put("photos", JSONArray(photos))
private fun JSONObject.trip(): Trip {
    val notes = optJSONObject("packingPhotoNotes") ?: JSONObject()
    return Trip(getString("id"), getString("destination"), getString("start"), getString("end"), optString("kind", "leisure"), stringOrNull("cover"), optBoolean("favorite"), optJSONArray("items")?.objects()?.map { it.item() } ?: emptyList(), optJSONArray("entries")?.objects()?.map { it.entry() } ?: emptyList(), optJSONArray("packingPhotos")?.strings() ?: emptyList(), notes.keys().asSequence().associateWith { notes.getString(it) }, optString("lessons"))
}
private fun Trip.json() = JSONObject().put("id", id).put("destination", destination).put("start", start).put("end", end).put("kind", kind).put("cover", cover.jsonValue()).put("favorite", favorite).put("items", JSONArray(items.map { it.json() })).put("entries", JSONArray(entries.map { it.json() })).put("packingPhotos", JSONArray(packingPhotos)).put("packingPhotoNotes", JSONObject(packingPhotoNotes)).put("lessons", lessons)

class TravelStore(private val context: Context, private val billing: PlayProducts) {
    private val dir = File(context.filesDir, "Satchel").apply { mkdirs() }
    private val file = AtomicFile(File(dir, "journal.json"))
    var trips by mutableStateOf<List<Trip>>(emptyList()); private set
    var template by mutableStateOf<List<Item>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)
    private var writable = true
    init {
        if (file.baseFile.exists() || File(dir, "journal.json.bak").exists()) try {
            val data = JSONObject(file.readFully().toString(Charsets.UTF_8))
            trips = data.getJSONArray("trips").objects().map { it.trip() }
            template = data.optJSONArray("commonList")?.objects()?.map { it.item() } ?: emptyList()
        } catch (_: Exception) { writable = false; error = "Could not open your travel journal. Your existing data has not been replaced." }
    }
    private fun photos(ts: List<Trip>, common: List<Item> = template): Set<String> = (ts.flatMap { t -> listOfNotNull(t.cover) + t.packingPhotos + t.items.mapNotNull { it.photo } + t.entries.flatMap { it.photos } } + common.mapNotNull { it.photo }).toSet()
    fun tripPhotoCount(): Int = photos(trips, emptyList()).size
    private fun persist(next: List<Trip>, common: List<Item>): Boolean {
        if (!writable) { error = "Your journal could not be opened; saving is disabled to protect existing data."; return false }
        val oldTripPhotos = photos(trips, emptyList()); val newTripPhotos = photos(next, emptyList())
        val addsContent = next.size > trips.size || (newTripPhotos - oldTripPhotos).isNotEmpty()
        if (addsContent && billing.configured && !billing.ready) { error = "Checking membership. Please try again in a moment."; return false }
        if (!billing.isMember && next.size > trips.size && next.size > 3) { error = "The free plan includes up to 3 trips. Membership unlocks unlimited trips."; return false }
        val before = photos(trips); val after = photos(next, common)
        if (!billing.isMember && (newTripPhotos - oldTripPhotos).isNotEmpty() && newTripPhotos.size > 30) { error = "The free plan includes up to 30 photos. Membership unlocks unlimited photos."; return false }
        var stream: java.io.FileOutputStream? = null
        return try {
            val root = JSONObject().put("trips", JSONArray(next.map { it.json() })).put("commonList", JSONArray(common.map { it.json() }))
            stream = file.startWrite(); stream.write(root.toString().toByteArray()); file.finishWrite(stream); stream = null
            trips = next; template = common
            (before - after).forEach { photoFile(it)?.delete() }
            true
        } catch (_: Exception) { stream?.let { file.failWrite(it) }; error = "Could not save your changes. Please try again."; false }
    }
    fun update(trip: Trip): Boolean = persist(if (trips.any { it.id == trip.id }) trips.map { if (it.id == trip.id) trip else it } else trips + trip, template)
    fun delete(trip: Trip): Boolean = persist(trips.filterNot { it.id == trip.id }, template)
    fun saveTemplate(items: List<Item>): Boolean = persist(trips, items)
    fun photoFile(name: String?): File? = name?.takeIf { it.matches(Regex("[a-fA-F0-9-]+\\.jpg")) }?.let { File(dir, it) }
    fun savePhoto(uri: Uri): String? {
        if (!writable) { error = "Your journal is unavailable; the photo was not saved."; return null }
        val name = "${id()}.jpg"; val target = photoFile(name)!!
        return try {
            context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); target.outputStream().use { input.copyTo(it) } }
            name
        } catch (_: Exception) { target.delete(); error = "Could not save the photo. Please try again."; null }
    }
    fun discardPhoto(name: String?) { if (name != null && name !in photos(trips)) photoFile(name)?.delete() }
    fun export(uri: Uri): Boolean {
        if (!writable) { error = "Your journal could not be exported. Please try again."; return false }
        return try {
            val encoded = JSONObject()
            photos(trips).sorted().forEach { name -> encoded.put(name, Base64.encodeToString(requireNotNull(photoFile(name)).readBytes(), Base64.NO_WRAP)) }
            val data = JSONObject().put("formatVersion", 1).put("trips", JSONArray(trips.map { it.json() })).put("commonList", JSONArray(template.map { it.json() })).put("photos", encoded)
            requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(data.toString().toByteArray()) }; true
        } catch (_: Exception) { error = "Could not export your journal. Please try again."; false }
    }
}
fun suggested(kind: String, start: LocalDate, end: LocalDate): List<Item> {
    val days = (java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1).coerceIn(1L, 7L).toInt()
    fun suggestion(name: String, category: String, quantity: Int = 1) = Item(name=name, category=category, quantity=quantity, suggestedNameKey=name, suggestedCategoryKey=category)
    return listOf(suggestion("Tops", "Clothes", days), suggestion("Underwear", "Clothes", days), suggestion("Toothbrush", "Toiletries"), suggestion("Toiletries", "Toiletries"), suggestion("Phone charger", "Electronics"), suggestion("ID and travel documents", "Essentials")) + if (kind == "business") listOf(suggestion("Laptop", "Electronics"), suggestion("Work materials", "Essentials")) else emptyList()
}
