package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONObject
import java.io.File

data class StoredMapSummary(
    val file: File, val islandName: String, val sourceIslandKey: String, val roadCount: Int,
    val placeCount: Int, val downloadedAt: Long, val length: Long, val modified: Long
)

class MapStore(context: Context) {
    private val directory = File(context.filesDir, "island-maps").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "island-map-index.json")
    @Volatile private var cachedSummaries: List<StoredMapSummary>? = null
    fun save(map: MapData): File {
        val file = File(directory, "${map.saveName()}.json")
        file.writeText(map.toJson().toString())
        val current = cachedSummaries
        if (current == null) indexFile.delete() else {
            cachedSummaries = (current.filterNot { it.file.name == file.name } + summary(file, map)).sortedBy { it.file.name }
            writeIndex(cachedSummaries!!)
        }
        return file
    }
    fun load(file: File): MapData = MapData.fromJson(JSONObject(file.readText()))
    fun all(): List<File> = directory.listFiles { file -> file.isFile && file.extension == "json" }?.sortedBy { it.nameWithoutExtension } ?: emptyList()
    @Synchronized fun summaries(): List<StoredMapSummary> {
        val files = all()
        cachedSummaries?.takeIf { matches(files, it) }?.let { return it }
        readIndex(files)?.let { cachedSummaries = it; return it }
        val built = files.mapNotNull { file -> runCatching { summary(file, load(file)) }.getOrNull() }
        cachedSummaries = built
        writeIndex(built)
        return built
    }
    @Synchronized fun delete(file: File): Boolean {
        val deleted = file.delete()
        if (deleted) cachedSummaries?.let { old ->
            cachedSummaries = old.filterNot { it.file.name == file.name }
            writeIndex(cachedSummaries!!)
        } ?: indexFile.delete()
        return deleted
    }

    fun importNavigationBackup(text: String): MapImportSummary {
        val batch = HaluviBackupImporter.parse(text)
        var roadsAdded = 0
        var placesAdded = 0
        var mapsProcessed = 0
        val previousSummaries = summaries()
        val existing = previousSummaries.associateBy { it.sourceIslandKey }
        val replacements = linkedMapOf<String, StoredMapSummary>()
        for (incoming in batch.maps) {
            val existingRecord = existing[incoming.sourceIslandKey]
            val existingFile = existingRecord?.file
            val oldMap = existingFile?.let { runCatching { load(it) }.getOrNull() }
            val oldRoads = oldMap?.roads.orEmpty()
            val oldPlaces = oldMap?.places.orEmpty()
            val roads = (oldRoads + incoming.roads).distinctBy { "${it.name.trim().lowercase()}|${geometryKey(it.points)}" }
            val places = (oldPlaces + incoming.places).fold(mutableListOf<Place>()) { kept, place ->
                if (kept.none { it.id == place.id || (it.name.equals(place.name, true) && distanceMeters(it.point, place.point) <= 3.0) }) kept.add(place)
                kept
            }
            roadsAdded += roads.size - oldRoads.size
            placesAdded += places.size - oldPlaces.size
            val merged = MapData(incoming.islandName, maxOf(oldMap?.downloadedAt ?: 0L, incoming.downloadedAt), roads, places,
                incoming.sourceIslandKey, (oldMap?.connectivityVerified ?: true) && incoming.connectivityVerified)
            val target = existingFile ?: File(directory, "${merged.saveName()}.json")
            target.writeText(merged.toJson().toString())
            replacements[incoming.sourceIslandKey] = summary(target, merged)
            mapsProcessed++
        }
        cachedSummaries = (previousSummaries.filterNot { it.sourceIslandKey in replacements.keys } + replacements.values).sortedBy { it.file.name }
        writeIndex(cachedSummaries!!)
        return MapImportSummary(mapsProcessed, roadsAdded, placesAdded, batch.unsupportedPlacesSkipped)
    }

    private fun geometryKey(points: List<GeoPoint>): String {
        fun encode(list: List<GeoPoint>) = list.joinToString(";") { "${(it.lat * 1e7).toLong()},${(it.lon * 1e7).toLong()}" }
        return minOf(encode(points), encode(points.reversed()))
    }

    private fun summary(file: File, map: MapData) = StoredMapSummary(file, map.islandName, map.sourceIslandKey,
        map.roads.size, map.places.size, map.downloadedAt, file.length(), file.lastModified())

    private fun matches(files: List<File>, values: List<StoredMapSummary>) = files.size == values.size &&
        files.all { file -> values.any { it.file.name == file.name && it.length == file.length() && it.modified == file.lastModified() } }

    private fun readIndex(files: List<File>): List<StoredMapSummary>? = runCatching {
        if (!indexFile.isFile) return null
        val array = JSONObject(indexFile.readText()).optJSONArray("maps") ?: return null
        val byName = files.associateBy { it.name }
        val values = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val file = byName[o.optString("file")] ?: return@mapNotNull null
            StoredMapSummary(file, o.optString("name"), o.optString("key"), o.optInt("roads"), o.optInt("places"),
                o.optLong("downloaded"), o.optLong("length"), o.optLong("modified"))
        }
        values.takeIf { matches(files, it) }
    }.getOrNull()

    private fun writeIndex(values: List<StoredMapSummary>) {
        runCatching {
            val json = JSONObject().put("maps", org.json.JSONArray().apply { values.forEach { s ->
                put(JSONObject().put("file", s.file.name).put("name", s.islandName).put("key", s.sourceIslandKey)
                    .put("roads", s.roadCount).put("places", s.placeCount).put("downloaded", s.downloadedAt)
                    .put("length", s.file.length()).put("modified", s.file.lastModified()))
            } })
            indexFile.writeText(json.toString())
        }
    }
}

data class MapImportSummary(val mapsProcessed: Int, val roadsAdded: Int, val placesAdded: Int,
    val unsupportedPlacesSkipped: Int)
