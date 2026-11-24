package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONObject
import java.io.File

class MapStore(context: Context) {
    private val directory = File(context.filesDir, "island-maps").apply { mkdirs() }
    fun save(map: MapData) { File(directory, "${map.saveName()}.json").writeText(map.toJson().toString()) }
    fun load(file: File): MapData = MapData.fromJson(JSONObject(file.readText()))
    fun all(): List<File> = directory.listFiles { file -> file.isFile && file.extension == "json" }?.sortedBy { it.nameWithoutExtension } ?: emptyList()
    fun delete(file: File): Boolean = file.delete()

    fun importNavigationBackup(text: String): MapImportSummary {
        val batch = HaluviBackupImporter.parse(text)
        var roadsAdded = 0
        var placesAdded = 0
        var mapsProcessed = 0
        for (incoming in batch.maps) {
            val existingFile = all().firstOrNull { file ->
                runCatching { load(file).sourceIslandKey == incoming.sourceIslandKey }.getOrDefault(false)
            }
            val existing = existingFile?.let { runCatching { load(it) }.getOrNull() }
            val oldRoads = existing?.roads.orEmpty()
            val oldPlaces = existing?.places.orEmpty()
            val roads = (oldRoads + incoming.roads).distinctBy { "${it.name.trim().lowercase()}|${geometryKey(it.points)}" }
            val places = (oldPlaces + incoming.places).fold(mutableListOf<Place>()) { kept, place ->
                if (kept.none { it.id == place.id || (it.name.equals(place.name, true) && distanceMeters(it.point, place.point) <= 3.0) }) kept.add(place)
                kept
            }
            roadsAdded += roads.size - oldRoads.size
            placesAdded += places.size - oldPlaces.size
            val merged = MapData(
                incoming.islandName,
                maxOf(existing?.downloadedAt ?: 0L, incoming.downloadedAt),
                roads,
                places,
                incoming.sourceIslandKey,
                (existing?.connectivityVerified ?: true) && incoming.connectivityVerified
            )
            // Preserve the current file path when merging into an imported map.
            if (existingFile != null) existingFile.writeText(merged.toJson().toString()) else save(merged)
            mapsProcessed++
        }
        return MapImportSummary(mapsProcessed, roadsAdded, placesAdded, batch.googlePlacesSkipped, batch.unsupportedPlacesSkipped)
    }

    private fun geometryKey(points: List<GeoPoint>): String {
        fun encode(list: List<GeoPoint>) = list.joinToString(";") { "${(it.lat * 1e7).toLong()},${(it.lon * 1e7).toLong()}" }
        val forward = encode(points)
        val reverse = encode(points.reversed())
        return minOf(forward, reverse)
    }
}

data class MapImportSummary(
    val mapsProcessed: Int,
    val roadsAdded: Int,
    val placesAdded: Int,
    val googlePlacesSkipped: Int,
    val unsupportedPlacesSkipped: Int
)

