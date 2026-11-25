package com.hussainshareef.haluviexplorer

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class GeoPoint(val lat: Double, val lon: Double)

data class Road(
    val id: String,
    val name: String,
    val kind: String,
    val nodeIds: List<Long>,
    val points: List<GeoPoint>
) {
    val lengthMeters: Double by lazy { polylineLength(points) }
    val startNode: Long? get() = nodeIds.firstOrNull()
    val endNode: Long? get() = nodeIds.lastOrNull()
}

data class Place(
    val id: String,
    val name: String,
    val category: String,
    val point: GeoPoint,
    val entranceVerified: Boolean
)

data class MapData(
    val islandName: String,
    val downloadedAt: Long,
    val roads: List<Road>,
    val places: List<Place>,
    val sourceIslandKey: String = "",
    val connectivityVerified: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("islandName", islandName)
        put("sourceIslandKey", sourceIslandKey)
        put("connectivityVerified", connectivityVerified)
        put("downloadedAt", downloadedAt)
        put("roads", JSONArray().apply {
            roads.forEach { road -> put(JSONObject().apply {
                put("id", road.id); put("name", road.name); put("kind", road.kind)
                put("nodeIds", JSONArray().apply { road.nodeIds.forEach { put(it) } })
                put("points", JSONArray().apply { road.points.forEach { p -> put(JSONObject().apply { put("lat", p.lat); put("lon", p.lon) }) } })
            }) }
        })
        put("places", JSONArray().apply {
            places.forEach { place -> put(JSONObject().apply {
                put("id", place.id); put("name", place.name); put("category", place.category)
                put("lat", place.point.lat); put("lon", place.point.lon); put("entranceVerified", place.entranceVerified)
            }) }
        })
    }

    fun saveName(): String = (sourceIslandKey.ifBlank { islandName }).lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "island" }

    companion object {
        private val roadKinds = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "pedestrian", "footway", "path", "track", "steps", "cycleway", "road", "bridleway", "corridor", "platform", "raceway")
        private val placeKeys = listOf("amenity", "shop", "tourism", "leisure", "healthcare", "office", "education")

        fun fromJson(json: JSONObject): MapData {
            val roads = buildList {
                val array = json.optJSONArray("roads") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val ids = item.optJSONArray("nodeIds") ?: JSONArray()
                    val points = item.optJSONArray("points") ?: JSONArray()
                    add(Road(item.optString("id"), item.optString("name", "Unnamed road"), item.optString("kind"),
                        (0 until ids.length()).map { ids.optLong(it) },
                        (0 until points.length()).mapNotNull { j -> points.optJSONObject(j)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) } }))
                }
            }.filter { it.points.size >= 2 && it.lengthMeters > 0.5 }
            val places = buildList {
                val array = json.optJSONArray("places") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(Place(item.optString("id"), item.optString("name"), item.optString("category"),
                        GeoPoint(item.optDouble("lat"), item.optDouble("lon")), item.optBoolean("entranceVerified")))
                }
            }
            return MapData(json.optString("islandName", "Island"), json.optLong("downloadedAt"), roads, places,
                json.optString("sourceIslandKey"), json.optBoolean("connectivityVerified", true))
        }

        fun fromOverpass(islandName: String, response: String): MapData {
            val root = JSONObject(response)
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val roads = mutableListOf<Road>()
            val places = linkedMapOf<String, Place>()
            for (i in 0 until elements.length()) {
                val e = elements.optJSONObject(i) ?: continue
                val tags = e.optJSONObject("tags") ?: JSONObject()
                val type = e.optString("type")
                val id = e.optLong("id")
                if (type == "way" && tags.optString("highway") in roadKinds) {
                    val geometry = e.optJSONArray("geometry") ?: JSONArray()
                    val pointList = (0 until geometry.length()).mapNotNull { j -> geometry.optJSONObject(j)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) } }
                    val nodeArray = e.optJSONArray("nodes") ?: JSONArray()
                    val nodeIds = if (nodeArray.length() == pointList.size) (0 until nodeArray.length()).map { nodeArray.optLong(it) }
                        else pointList.map(::coordinateNodeId)
                    if (pointList.size >= 2) {
                        roads += Road("w$id", tags.optString("name", tags.optString("ref", "Unnamed road")), tags.optString("highway"), nodeIds, pointList)
                    }
                }
                if (placeKeys.any { tags.has(it) }) {
                    val pos = when (type) {
                        "node" -> GeoPoint(e.optDouble("lat", Double.NaN), e.optDouble("lon", Double.NaN))
                        "way" -> e.optJSONObject("center")?.let { GeoPoint(it.optDouble("lat", Double.NaN), it.optDouble("lon", Double.NaN)) }
                        else -> null
                    } ?: continue
                    if (!pos.lat.isFinite() || !pos.lon.isFinite()) continue
                    val key = placeKeys.firstOrNull { tags.has(it) } ?: continue
                    val display = tags.optString("name").ifBlank { tags.optString("brand").ifBlank { tags.optString(key) } }
                    val entrance = tags.optString("entrance").isNotBlank() || tags.optString("entrance:yes").isNotBlank()
                    val place = Place("$type$id", display, tags.optString(key), pos, entrance)
                    places[place.id] = place
                }
            }
            return MapData(islandName, System.currentTimeMillis(), splitRoadsAtSharedNodes(roads), places.values.toList())
        }
    }
}

private fun coordinateNodeId(point: GeoPoint): Long {
    val lat = (point.lat * 10_000_000).roundToLong().toInt()
    val lon = (point.lon * 10_000_000).roundToLong().toInt()
    return (lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)
}

/** Splits ways at shared network vertices so a junction inside a long curved way becomes a usable endpoint. */
fun splitRoadsAtSharedNodes(roads: List<Road>): List<Road> {
    val roadsAtNode = HashMap<Long, MutableSet<String>>()
    roads.forEach { road -> road.nodeIds.forEach { id -> roadsAtNode.getOrPut(id) { linkedSetOf() }.add(road.id) } }
    val shared = roadsAtNode.filterValues { it.size > 1 }.keys
    return buildList {
        roads.forEach { road ->
            if (road.points.size < 2 || road.nodeIds.size != road.points.size) return@forEach
            val cuts = (listOf(0, road.nodeIds.lastIndex) + road.nodeIds.indices.filter { it in 1 until road.nodeIds.lastIndex && road.nodeIds[it] in shared })
                .distinct().sorted()
            if (cuts.size <= 2) { add(road); return@forEach }
            for (i in 0 until cuts.lastIndex) {
                val start = cuts[i]
                val end = cuts[i + 1]
                if (end <= start) continue
                add(road.copy(
                    id = "${road.id}:part:$i",
                    points = road.points.subList(start, end + 1),
                    nodeIds = road.nodeIds.subList(start, end + 1)
                ))
            }
        }
    }
}

/** Keeps graph sections intact while presenting one searchable entry per named street. */
fun groupRoadsByName(roads: List<Road>, filter: String = ""): List<Pair<String, List<Road>>> =
    roads.groupBy { it.name.trim().ifBlank { "Unnamed road" } }
        .filterKeys { it.contains(filter, ignoreCase = true) }
        .toList()
        .sortedBy { it.first.lowercase() }

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val radius = 6_371_000.0
    val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * radius * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat); val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

fun polylineLength(points: List<GeoPoint>): Double = points.zipWithNext().sumOf { distanceMeters(it.first, it.second) }

fun compassName(bearing: Double): String = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")[(floor(((bearing % 360 + 22.5) % 360) / 45).toInt()) % 8]

