package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONObject
import java.io.FileOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class StoredMapSummary(
    val file: File, val islandName: String, val sourceIslandKey: String, val roadCount: Int,
    val placeCount: Int, val downloadedAt: Long, val length: Long, val modified: Long,
    val unlocatedPlaceCount: Int = 0
)

class MapStore(context: Context) {
    private val directory = File(context.filesDir, "island-maps").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "island-map-index.json")
    @Volatile private var cachedSummaries: List<StoredMapSummary>? = null
    fun save(map: MapData): File {
        val file = File(directory, "${map.saveName()}.json")
        writeAtomic(file, map.toJson().toString())
        val current = cachedSummaries
        if (current == null) indexFile.delete() else {
            cachedSummaries = (current.filterNot { it.file.name == file.name } + summary(file, map)).sortedBy { it.file.name }
            writeIndex(cachedSummaries!!)
        }
        return file
    }

    /** Replaces old estimated OSM roads with a node-aware download while preserving saved places and custom roads. */
    @Synchronized fun updateWithNetwork(area: IslandArea, network: MapData): Pair<File, MapData> {
        val previous = summaries()
        val atoll = AtollEntry(area.atollCode, area.atollName, emptyList())
        val island = IslandEntry(area.shortName, 0.0, 0.0)
        val networkKey = normalizeIslandIdentity(network.sourceIslandKey)
        val existing = previous.firstOrNull { summary ->
            (networkKey.isNotBlank() && normalizeIslandIdentity(summary.sourceIslandKey) == networkKey) ||
                storedMapMatchesIsland(summary, atoll, island)
        }
        val oldMap = existing?.let { runCatching { load(it.file) }.getOrNull() }
        val merged = mergeDownloadedNetwork(oldMap, network)
        val target = existing?.file ?: File(directory, "${merged.saveName()}.json")
        writeAtomic(target, merged.toJson().toString())
        cachedSummaries = (previous.filterNot { it.file.name == target.name } + summary(target, merged)).sortedBy { it.file.name }
        writeIndex(cachedSummaries!!)
        return target to merged
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
        var unlocatedPlacesAdded = 0
        var mapsProcessed = 0
        val previousSummaries = summaries()
        val existing = previousSummaries.associateBy { normalizeIslandIdentity(it.sourceIslandKey) }
        val replacements = linkedMapOf<String, StoredMapSummary>()
        for (incoming in batch.maps) {
            val existingRecord = existing[normalizeIslandIdentity(incoming.sourceIslandKey)]
            val existingFile = existingRecord?.file
            val oldMap = existingFile?.let { runCatching { load(it) }.getOrNull() }
            val oldRoads = oldMap?.roads.orEmpty()
            val oldPlaces = oldMap?.places.orEmpty()
            val oldUnlocatedPlaces = oldMap?.unlocatedPlaces.orEmpty()
            // Keep downloaded OSM geometry first: an identical backup line must not replace its real node IDs.
            val roads = (oldRoads + incoming.roads).distinctBy { roadGeometryKey(it.points) }
            val places = mergePlacesPreservingSources(oldPlaces, incoming.places)
            val unlocatedPlaces = mergeUnlocatedPreservingSources(oldUnlocatedPlaces, incoming.unlocatedPlaces)
            roadsAdded += roads.size - oldRoads.size
            placesAdded += places.size - oldPlaces.size
            unlocatedPlacesAdded += unlocatedPlaces.size - oldUnlocatedPlaces.size
            val merged = MapData(incoming.islandName, maxOf(oldMap?.downloadedAt ?: 0L, incoming.downloadedAt), roads, places,
                incoming.sourceIslandKey, roads.isNotEmpty() && roads.all { it.nodeIdsVerified }, unlocatedPlaces)
            val target = existingFile ?: File(directory, "${merged.saveName()}.json")
            writeAtomic(target, merged.toJson().toString())
            replacements[incoming.sourceIslandKey] = summary(target, merged)
            mapsProcessed++
        }
        val replacementKeys = replacements.keys.map(::normalizeIslandIdentity).toSet()
        cachedSummaries = (previousSummaries.filterNot { normalizeIslandIdentity(it.sourceIslandKey) in replacementKeys } + replacements.values).sortedBy { it.file.name }
        writeIndex(cachedSummaries!!)
        return MapImportSummary(mapsProcessed, roadsAdded, placesAdded, batch.unsupportedPlacesSkipped, unlocatedPlacesAdded)
    }

    private fun summary(file: File, map: MapData) = StoredMapSummary(file, map.islandName, map.sourceIslandKey,
        map.roads.size, map.places.size, map.downloadedAt, file.length(), file.lastModified(), map.unlocatedPlaces.size)

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
                o.optLong("downloaded"), o.optLong("length"), o.optLong("modified"), o.optInt("unlocatedPlaces"))
        }
        values.takeIf { matches(files, it) }
    }.getOrNull()

    private fun writeIndex(values: List<StoredMapSummary>) {
        runCatching {
            val json = JSONObject().put("maps", org.json.JSONArray().apply { values.forEach { s ->
                put(JSONObject().put("file", s.file.name).put("name", s.islandName).put("key", s.sourceIslandKey)
                    .put("roads", s.roadCount).put("places", s.placeCount).put("downloaded", s.downloadedAt)
                    .put("length", s.file.length()).put("modified", s.file.lastModified()).put("unlocatedPlaces", s.unlocatedPlaceCount))
            } })
            writeAtomic(indexFile, json.toString())
        }
    }

    /** Write a complete replacement before swapping it into the visible map/index filename. */
    private fun writeAtomic(target: File, content: String) {
        val parent = target.parentFile ?: error("Map storage directory is unavailable.")
        if (!parent.exists() && !parent.mkdirs()) error("Map storage directory could not be created.")
        val temp = File(parent, "${target.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

data class MapImportSummary(val mapsProcessed: Int, val roadsAdded: Int, val placesAdded: Int,
    val unsupportedPlacesSkipped: Int, val unlocatedPlacesAdded: Int = 0)

/** Only repeated records with the same source and stable ID are collapsed; cross-source places remain distinct. */
internal fun mergePlacesPreservingSources(existing: List<Place>, incoming: List<Place>): List<Place> {
    val merged = ArrayList<Place>(existing.size + incoming.size)
    merged.addAll(existing)
    for (place in incoming) {
        val duplicateIndex = merged.indexOfFirst { it.id == place.id && it.source.equals(place.source, ignoreCase = true) }
        if (duplicateIndex < 0) merged.add(place)
        else {
            // Re-import/update can enrich an older place row with its address/provider fields
            // without removing the row or changing any other saved place record.
            val old = merged[duplicateIndex]
            if (old.sourceData.isBlank() && place.sourceData.isNotBlank()) {
                merged[duplicateIndex] = old.copy(sourceData = place.sourceData)
            }
        }
    }
    return merged
}

/** Keep unlocated provider records available for review instead of dropping them during import. */
internal fun mergeUnlocatedPreservingSources(existing: List<UnlocatedPlace>, incoming: List<UnlocatedPlace>): List<UnlocatedPlace> {
    val merged = ArrayList<UnlocatedPlace>(existing.size + incoming.size)
    merged.addAll(existing)
    for (place in incoming) {
        val duplicate = merged.any { it.id == place.id && it.source.equals(place.source, ignoreCase = true) }
        if (!duplicate) merged.add(place)
    }
    return merged
}

internal fun mergeDownloadedNetwork(existing: MapData?, network: MapData): MapData {
    val networkGeometry = network.roads.map { roadGeometryKey(it.points) }.toSet()
    // Keep saved roads the live OSM response did not contain; their connections remain marked as estimates.
    val retainedRoads = existing?.roads.orEmpty().filter { roadGeometryKey(it.points) !in networkGeometry }
    val roads = (network.roads + retainedRoads).distinctBy { roadGeometryKey(it.points) }
    val places = mergePlacesPreservingSources(existing?.places.orEmpty(), network.places)
    val unlocatedPlaces = mergeUnlocatedPreservingSources(existing?.unlocatedPlaces.orEmpty(), network.unlocatedPlaces)
    return network.copy(roads = roads, places = places, unlocatedPlaces = unlocatedPlaces,
        connectivityVerified = roads.isNotEmpty() && roads.all { it.nodeIdsVerified })
}

internal fun roadGeometryKey(points: List<GeoPoint>): String {
    fun encode(list: List<GeoPoint>) = list.joinToString(";") { "${(it.lat * 1e7).toLong()},${(it.lon * 1e7).toLong()}" }
    return minOf(encode(points), encode(points.reversed()))
}
