package com.hussainshareef.haluviexplorer

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

data class BackupImportBatch(
    val maps: List<MapData>,
    val unsupportedPlacesSkipped: Int
)

/** Reads the JSON map/backup format exported by Haluvi Navigation (v2). */
object HaluviBackupImporter {
    fun parse(text: String): BackupImportBatch {
        val root = JSONObject(text)
        require(root.optString("app") == "Haluvi Navigation") { "This is not a Haluvi Navigation backup or shared map." }
        val roadsByIsland = linkedMapOf<String, MutableList<Road>>()
        val displayNames = linkedMapOf<String, String>()
        val roadObject = root.optJSONObject("osmRoads") ?: JSONObject()
        val islandKeys = roadObject.keys()
        while (islandKeys.hasNext()) {
            val key = islandKeys.next()
            val out = roadsByIsland.getOrPut(key) { mutableListOf() }
            val records = roadObject.optJSONArray(key) ?: continue
            for (i in 0 until records.length()) {
                val item = records.optJSONObject(i) ?: continue
                val points = parseCoordinatePairs(item.optJSONArray("points")) ?: continue
                val name = item.optString("name").ifBlank { "Unnamed road" }
                val kind = item.optString("kind").ifBlank { "unknown" }
                out += Road("nav:$key:$i", name, kind, points.map(::nodeId), points)
            }
            displayNames[key] = key.substringAfter(':', key).ifBlank { key }
        }

        // Haluvi Navigation saves user road names, recorded roads and straight-road overlays separately.
        val roadNames = root.optJSONArray("roadNames") ?: JSONArray()
        for (i in 0 until roadNames.length()) {
            val item = roadNames.optJSONObject(i) ?: continue
            val key = item.optString("island")
            if (key.isBlank()) continue
            val points = parseCoordinatePairs(item.optJSONArray("points")) ?: continue
            val name = item.optString("name").ifBlank { "Unnamed user road" }
            displayNames.putIfAbsent(key, key.substringAfter(':', key).ifBlank { key })
            val roads = roadsByIsland.getOrPut(key) { mutableListOf() }
            val match = roads.indexOfFirst { sameLine(it.points, points) }
            if (match >= 0 && !item.optBoolean("recorded") && !item.optBoolean("straight")) {
                roads[match] = roads[match].copy(name = name)
            } else if (match < 0) {
                val recorded = item.optBoolean("recorded")
                val kind = when (item.optString("width")) {
                    "narrow" -> "user-recorded footway"
                    "wide" -> "user-recorded wide road"
                    else -> if (recorded) "user-recorded road" else "user-defined road"
                }
                roads += Road("user:$key:$i", "$name (user supplied)", kind, points.map(::nodeId), points)
            }
        }

        var unsupportedSkipped = 0
        val placesByIsland = linkedMapOf<String, MutableList<Place>>()
        val placeRecords = root.optJSONArray("locations") ?: JSONArray()
        for (i in 0 until placeRecords.length()) {
            val item = placeRecords.optJSONObject(i) ?: continue
            val source = item.optString("source", "manual").ifBlank { "manual" }
            val islandName = item.optString("islandName").trim()
            val key = item.optString("islandKey").ifBlank {
                islandName.takeIf { it.isNotBlank() }?.let { "imported:$it" } ?: "imported-unassigned"
            }
            val lat = item.optDouble("lat", Double.NaN)
            val lon = item.optDouble("lon", Double.NaN)
            if (!validCoordinate(lat, lon)) { unsupportedSkipped++; continue }
            val name = item.optString("name").trim()
            if (name.isBlank()) { unsupportedSkipped++; continue }
            val category = item.optString("categoryId").ifBlank { "other" }
            val id = item.optString("id").ifBlank { "backup:$key:$i" }
            placesByIsland.getOrPut(key) { mutableListOf() } += Place(id, name, category, GeoPoint(lat, lon), false, source)
            if (islandName.isNotBlank()) displayNames[key] = islandName
        }

        val keys = (roadsByIsland.keys + placesByIsland.keys).distinct()
        val maps = keys.map { key ->
            MapData(
                islandName = displayNames[key].orEmpty().ifBlank { key.substringAfter(':', key) },
                downloadedAt = System.currentTimeMillis(),
                roads = splitRoadsAtSharedNodes(roadsByIsland[key].orEmpty().filter { it.points.size >= 2 && it.lengthMeters > 0.5 }),
                places = placesByIsland[key].orEmpty(),
                sourceIslandKey = key,
                connectivityVerified = false
            )
        }.filter { it.roads.isNotEmpty() || it.places.isNotEmpty() }
        require(maps.isNotEmpty()) { "No reusable road or place data was found in this backup." }
        return BackupImportBatch(maps, unsupportedSkipped)
    }

    private fun parseCoordinatePairs(array: JSONArray?): List<GeoPoint>? {
        if (array == null || array.length() < 2) return null
        val points = ArrayList<GeoPoint>(array.length())
        for (i in 0 until array.length()) {
            val pair = array.optJSONArray(i) ?: return null
            if (pair.length() < 2) return null
            val lat = pair.optDouble(0, Double.NaN)
            val lon = pair.optDouble(1, Double.NaN)
            if (!validCoordinate(lat, lon)) return null
            points += GeoPoint(lat, lon)
        }
        return points
    }

    private fun validCoordinate(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    // The backup omits OSM node IDs. Sharing an exact stored vertex gives a conservative synthetic junction;
    // line crossings without a shared vertex remain disconnected.
    private fun nodeId(point: GeoPoint): Long {
        val lat = (point.lat * 10_000_000).roundToLong().toInt()
        val lon = (point.lon * 10_000_000).roundToLong().toInt()
        return (lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)
    }

    private fun sameLine(a: List<GeoPoint>, b: List<GeoPoint>): Boolean {
        if (a.size != b.size) return false
        fun key(points: List<GeoPoint>) = points.joinToString(";") { "${(it.lat * 1e7).roundToLong()},${(it.lon * 1e7).roundToLong()}" }
        val forward = key(a); val reverse = key(a.reversed())
        val other = key(b)
        return other == forward || other == reverse
    }
}
