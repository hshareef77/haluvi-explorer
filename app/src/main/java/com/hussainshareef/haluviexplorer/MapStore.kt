package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONObject
import java.io.File

data class StoredMapSummary(val file: File, val map: MapData) {
    val islandName get() = map.islandName
    val roadCount get() = map.roads.size
    val placeCount get() = map.places.size
}

class MapStore(context: Context) {
    private val directory = File(context.filesDir, "island-maps").apply { mkdirs() }
    fun save(map: MapData): File = File(directory, "${map.saveName()}.json").also { it.writeText(map.toJson().toString()) }
    fun load(file: File): MapData = MapData.fromJson(JSONObject(file.readText()))
    fun all(): List<File> = directory.listFiles { file -> file.isFile && file.extension == "json" }?.sortedBy { it.nameWithoutExtension } ?: emptyList()
    fun summaries(): List<StoredMapSummary> = all().mapNotNull { file -> runCatching { StoredMapSummary(file, load(file)) }.getOrNull() }
    fun delete(file: File): Boolean = file.delete()

    fun importNavigationBackup(text: String): MapImportSummary {
        val batch = HaluviBackupImporter.parse(text)
        var roadsAdded = 0
        var placesAdded = 0
        var mapsProcessed = 0
        val existing = summaries().associateBy { it.map.sourceIslandKey }
        for (incoming in batch.maps) {
            val existingRecord = existing[incoming.sourceIslandKey]
            val existingFile = existingRecord?.file
            val oldMap = existingRecord?.map
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
            if (existingFile != null) existingFile.writeText(merged.toJson().toString()) else save(merged)
            mapsProcessed++
        }
        return MapImportSummary(mapsProcessed, roadsAdded, placesAdded, batch.googlePlacesSkipped, batch.unsupportedPlacesSkipped)
    }

    private fun geometryKey(points: List<GeoPoint>): String {
        fun encode(list: List<GeoPoint>) = list.joinToString(";") { "${(it.lat * 1e7).toLong()},${(it.lon * 1e7).toLong()}" }
        return minOf(encode(points), encode(points.reversed()))
    }
}

data class MapImportSummary(val mapsProcessed: Int, val roadsAdded: Int, val placesAdded: Int,
    val googlePlacesSkipped: Int, val unsupportedPlacesSkipped: Int)
