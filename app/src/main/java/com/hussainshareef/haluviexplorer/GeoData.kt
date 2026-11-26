package com.hussainshareef.haluviexplorer

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

private val ROAD_ADDRESS_HINT_KEY_REGEX = Regex("[^a-z0-9]")
private val ROAD_ADDRESS_HINT_KEYS = setOf(
    "road", "roadname", "road_name", "street", "streetname", "street_name", "addr:street",
    "route", "vicinity", "formattedaddress", "formatted_address", "address", "streetaddress",
    "street_address", "addressline", "address_line"
).mapTo(HashSet()) { it.replace(ROAD_ADDRESS_HINT_KEY_REGEX, "") }

data class GeoPoint(val lat: Double, val lon: Double)

data class Road(
    val id: String,
    val name: String,
    val kind: String,
    val nodeIds: List<Long>,
    val points: List<GeoPoint>,
    /** True only when nodeIds came from the source map's actual OSM way-node list. */
    val nodeIdsVerified: Boolean = false,
    /** One linear, connected named street path assembled from several OSM geometry sections. */
    val continuousPath: Boolean = false
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
    val entranceVerified: Boolean,
    val source: String = "",
    /** Original place record/tags retained locally so provider address and road hints are not discarded. */
    val sourceData: String = ""
) {
    private val cachedRoadAddressHints: List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { parseRoadAddressHints() }

    fun roadAddressHints(): List<String> = cachedRoadAddressHints

    private fun parseRoadAddressHints(): List<String> {
        if (sourceData.isBlank()) return emptyList()
        return runCatching {
        val root = JSONObject(sourceData)
        val values = mutableListOf<String>()
        fun visit(value: Any?, key: String = "") {
            when (value) {
                is JSONObject -> {
                    val types = value.optJSONArray("types")
                    if (types != null && (0 until types.length()).any { types.optString(it) == "route" }) {
                        listOf("long_name", "short_name").mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.forEach(values::add)
                    }
                    value.keys().forEach { child -> visit(value.opt(child), child.lowercase().replace(ROAD_ADDRESS_HINT_KEY_REGEX, "")) }
                }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i), key)
                is String -> if (key in ROAD_ADDRESS_HINT_KEYS) values += value
            }
        }
        visit(root)
        values
        }.getOrDefault(emptyList())
    }
}

/** A saved place record kept intact when it has no valid map coordinate or usable display name. */
data class UnlocatedPlace(
    val id: String,
    val name: String,
    val category: String,
    val source: String,
    val reason: String,
    val sourceData: String = ""
)

data class MapData(
    val islandName: String,
    val downloadedAt: Long,
    val roads: List<Road>,
    val places: List<Place>,
    val sourceIslandKey: String = "",
    val connectivityVerified: Boolean = false,
    val unlocatedPlaces: List<UnlocatedPlace> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("islandName", islandName)
        put("sourceIslandKey", sourceIslandKey)
        put("connectivityVerified", connectivityVerified)
        put("downloadedAt", downloadedAt)
        put("roads", JSONArray().apply {
            roads.forEach { road -> put(JSONObject().apply {
                put("id", road.id); put("name", road.name); put("kind", road.kind)
                put("nodeIdsVerified", road.nodeIdsVerified)
                put("continuousPath", road.continuousPath)
                put("nodeIds", JSONArray().apply { road.nodeIds.forEach { put(it) } })
                put("points", JSONArray().apply { road.points.forEach { p -> put(JSONObject().apply { put("lat", p.lat); put("lon", p.lon) }) } })
            }) }
        })
        put("places", JSONArray().apply {
            places.forEach { place -> put(JSONObject().apply {
                put("id", place.id); put("name", place.name); put("category", place.category)
                put("lat", place.point.lat); put("lon", place.point.lon); put("entranceVerified", place.entranceVerified)
                put("source", place.source); put("sourceData", place.sourceData)
            }) }
        })
        put("unlocatedPlaces", JSONArray().apply {
            unlocatedPlaces.forEach { place -> put(JSONObject().apply {
                put("id", place.id); put("name", place.name); put("category", place.category)
                put("source", place.source); put("reason", place.reason); put("sourceData", place.sourceData)
            }) }
        })
    }

    fun saveName(): String = (sourceIslandKey.ifBlank { islandName }).lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "island" }

    companion object {
        private val roadKinds = setOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "service", "pedestrian", "footway", "path", "track", "steps", "cycleway", "road", "bridleway", "corridor", "platform", "raceway")
        private val placeKeys = listOf(
            "amenity", "shop", "tourism", "leisure", "healthcare", "office", "education",
            "public_transport", "aeroway", "man_made", "craft", "historic", "natural",
            "emergency", "barrier", "military", "power", "railway", "waterway"
        )

        fun fromJson(json: JSONObject): MapData {
            val roads = buildList {
                val array = json.optJSONArray("roads") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val ids = item.optJSONArray("nodeIds") ?: JSONArray()
                    val points = item.optJSONArray("points") ?: JSONArray()
                    val storedNodeIds = (0 until ids.length()).map { ids.optLong(it) }
                    val geometry = (0 until points.length()).mapNotNull { j -> points.optJSONObject(j)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) } }
                    val nodeIdsVerified = item.optBoolean("nodeIdsVerified", false) && storedNodeIds.size == geometry.size && storedNodeIds.all { it > 0L }
                    val nodeIds = if (nodeIdsVerified) storedNodeIds else geometry.map(::coordinateNodeId)
                    add(Road(item.optString("id"), item.optString("name", "Unnamed road"), item.optString("kind"),
                        nodeIds, geometry, nodeIdsVerified, item.optBoolean("continuousPath", false)))
                }
            }.filter { it.points.size >= 2 && it.lengthMeters > 0.5 }
            val places = buildList {
                val array = json.optJSONArray("places") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val place = Place(item.optString("id"), item.optString("name"), item.optString("category"),
                        GeoPoint(item.optDouble("lat"), item.optDouble("lon")), item.optBoolean("entranceVerified"), item.optString("source"), item.optString("sourceData"))
                    place.roadAddressHints()
                    add(place)
                }
            }
            val unlocated = buildList {
                val array = json.optJSONArray("unlocatedPlaces") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(UnlocatedPlace(item.optString("id"), item.optString("name"), item.optString("category"),
                        item.optString("source"), item.optString("reason"), item.optString("sourceData")))
                }
            }
            val verified = json.optBoolean("connectivityVerified", false) && roads.isNotEmpty() && roads.all { it.nodeIdsVerified }
            return MapData(json.optString("islandName", "Island"), json.optLong("downloadedAt"), roads, places,
                json.optString("sourceIslandKey"), verified, unlocated)
        }

        fun fromOverpass(islandName: String, response: String): MapData {
            val root = JSONObject(response)
            val elements = root.optJSONArray("elements") ?: JSONArray()
            val roads = mutableListOf<Road>()
            val places = linkedMapOf<String, Place>()
            val unlocatedPlaces = mutableListOf<UnlocatedPlace>()
            var allRoadsHaveOsmNodeIds = true
            for (i in 0 until elements.length()) {
                val e = elements.optJSONObject(i) ?: continue
                val tags = e.optJSONObject("tags") ?: JSONObject()
                val type = e.optString("type")
                val id = e.optLong("id")
                if (type == "way" && tags.optString("highway") in roadKinds) {
                    val geometry = e.optJSONArray("geometry") ?: JSONArray()
                    val pointList = (0 until geometry.length()).mapNotNull { j -> geometry.optJSONObject(j)?.let { GeoPoint(it.optDouble("lat"), it.optDouble("lon")) } }
                    val nodeArray = e.optJSONArray("nodes") ?: JSONArray()
                    val hasOsmNodeIds = nodeArray.length() == pointList.size && (0 until nodeArray.length()).all { nodeArray.optLong(it) > 0L }
                    val nodeIds = if (hasOsmNodeIds) (0 until nodeArray.length()).map { nodeArray.optLong(it) }
                        else pointList.map(::coordinateNodeId)
                    if (pointList.size >= 2) {
                        roads += Road("w$id", tags.optString("name:en").ifBlank { tags.optString("name", tags.optString("ref", "Unnamed road")) },
                            tags.optString("highway"), nodeIds, pointList, hasOsmNodeIds)
                        allRoadsHaveOsmNodeIds = allRoadsHaveOsmNodeIds && hasOsmNodeIds
                    }
                }
                if (placeKeys.any { tags.has(it) }) {
                    val pos = when (type) {
                        "node" -> GeoPoint(e.optDouble("lat", Double.NaN), e.optDouble("lon", Double.NaN))
                        "way" -> e.optJSONObject("center")?.let { GeoPoint(it.optDouble("lat", Double.NaN), it.optDouble("lon", Double.NaN)) }
                        "relation" -> e.optJSONObject("center")?.let { GeoPoint(it.optDouble("lat", Double.NaN), it.optDouble("lon", Double.NaN)) }
                        else -> null
                    }
                    val key = placeKeys.firstOrNull { tags.has(it) } ?: continue
                    val display = tags.optString("name").ifBlank { tags.optString("brand").ifBlank { tags.optString(key) } }
                    if (pos == null || !pos.lat.isFinite() || !pos.lon.isFinite()) {
                        unlocatedPlaces += UnlocatedPlace("$type$id", display, tags.optString(key), "osm_map", "OpenStreetMap record has no usable point coordinate.", tags.toString())
                        continue
                    }
                    val entrance = tags.optString("entrance").isNotBlank() || tags.optString("entrance:yes").isNotBlank()
                    val place = Place("$type$id", display, tags.optString(key), pos, entrance, "osm_map", tags.toString())
                    place.roadAddressHints()
                    places[place.id] = place
                }
            }
            return MapData(islandName, System.currentTimeMillis(), splitRoadsAtSharedNodes(roads), places.values.toList(),
                connectivityVerified = roads.isNotEmpty() && allRoadsHaveOsmNodeIds, unlocatedPlaces = unlocatedPlaces)
        }

        /** Joins the smaller road-only and place-only Overpass responses without issuing one oversized query. */
        fun fromOverpassParts(islandName: String, roadsResponse: String, placesResponse: String): MapData {
            val roads = JSONObject(roadsResponse).optJSONArray("elements") ?: JSONArray()
            val places = JSONObject(placesResponse).optJSONArray("elements") ?: JSONArray()
            val elements = JSONArray()
            val seen = HashSet<String>()
            fun appendUnique(array: JSONArray) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val key = "${item.optString("type")}:${item.optLong("id", i.toLong())}"
                    if (seen.add(key)) elements.put(item)
                }
            }
            appendUnique(roads)
            appendUnique(places)
            return fromOverpass(islandName, JSONObject().put("elements", elements).toString())
        }

        /** Parses Haluvi Navigation's dated, per-atoll OSM snapshot used for offline map installation. */
        fun fromNavigationAtoll(atollCode: String, atollName: String, jsonText: String,
                                south: Double, west: Double, north: Double, east: Double): MapData {
            val root = JSONObject(jsonText)
            val date = root.optString("date", "unknown date")
            val roads = buildList {
                val items = root.optJSONArray("roads") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val tags = item.optJSONObject("tags") ?: JSONObject()
                    val kind = tags.optString("highway")
                    if (kind !in roadKinds) continue
                    val geometry = item.optJSONArray("geometry") ?: item.optJSONArray("points") ?: continue
                    val points = (0 until geometry.length()).mapNotNull { j ->
                        val p = geometry.optJSONObject(j) ?: return@mapNotNull null
                        val lat = p.optDouble("lat", Double.NaN); val lon = p.optDouble("lon", Double.NaN)
                        if (lat.isFinite() && lon.isFinite()) GeoPoint(lat, lon) else null
                    }
                    if (points.size < 2 || points.none { it.lat in south..north && it.lon in west..east }) continue
                    val name = tags.optString("name:en").ifBlank { tags.optString("name") }
                        .ifBlank { tags.optString("ref") }.ifBlank { "Unnamed road" }
                    add(Road("builtin:$atollCode:road:$i", name, kind, points.map(::coordinateNodeId), points))
                }
            }
            val places = buildList {
                val items = root.optJSONArray("places") ?: JSONArray()
                val categoryKeys = listOf("amenity", "shop", "tourism", "healthcare", "office", "education", "leisure", "public_transport", "aeroway", "man_made", "craft")
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val lat = item.optDouble("lat", Double.NaN); val lon = item.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite() || lat !in south..north || lon !in west..east) continue
                    val tags = item.optJSONObject("tags") ?: JSONObject()
                    val key = categoryKeys.firstOrNull { tags.has(it) } ?: continue
                    val category = tags.optString(key).ifBlank { key }
                    val name = tags.optString("name:en").ifBlank { tags.optString("name") }
                        .ifBlank { tags.optString("brand") }.ifBlank { category.replace('_', ' ') }
                    val place = Place("builtin:$atollCode:place:$i", name, category, GeoPoint(lat, lon), false, "osm_map", tags.toString())
                    place.roadAddressHints()
                    add(place)
                }
            }
            return MapData("$atollName Atoll OpenStreetMap snapshot ($date)", System.currentTimeMillis(),
                splitRoadsAtSharedNodes(roads), places, "builtin:$atollCode:$date", connectivityVerified = false)
        }
    }
}

private fun coordinateNodeId(point: GeoPoint): Long {
    val lat = (point.lat * 10_000_000).roundToLong().toInt()
    val lon = (point.lon * 10_000_000).roundToLong().toInt()
    // Synthetic coordinate IDs stay negative so they cannot collide with positive OSM node IDs.
    return ((lat.toLong() shl 32) xor (lon.toLong() and 0xffffffffL)) or Long.MIN_VALUE
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

/** Combines connected, linear sections of one named street while preserving every network node. */
fun continuousNamedRoadPaths(sections: List<Road>): List<Road> {
    if (sections.size < 2) return sections
    val incident = linkedMapOf<Long, MutableList<Int>>()
    sections.forEachIndexed { index, section ->
        val a = section.startNode ?: return@forEachIndexed
        val b = section.endNode ?: return@forEachIndexed
        incident.getOrPut(a) { mutableListOf() }.add(index)
        incident.getOrPut(b) { mutableListOf() }.add(index)
    }
    val unseen = sections.indices.toMutableSet()
    val paths = mutableListOf<Road>()
    while (unseen.isNotEmpty()) {
        val seed = unseen.first()
        val component = linkedSetOf<Int>()
        val queue = ArrayDeque<Int>().apply { add(seed) }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!component.add(index)) continue
            unseen.remove(index)
            val road = sections[index]
            listOfNotNull(road.startNode, road.endNode).forEach { node ->
                incident[node].orEmpty().forEach { neighbor -> if (neighbor !in component) queue.add(neighbor) }
            }
        }
        val degrees = linkedMapOf<Long, Int>()
        component.forEach { index ->
            val road = sections[index]
            road.startNode?.let { degrees[it] = (degrees[it] ?: 0) + 1 }
            road.endNode?.let { degrees[it] = (degrees[it] ?: 0) + 1 }
        }
        val terminals = degrees.filterValues { it == 1 }.keys
        val isLinear = degrees.isNotEmpty() && degrees.values.all { it <= 2 } && terminals.size == 2 &&
            component.all { sections[it].startNode != sections[it].endNode }
        if (!isLinear) {
            component.forEach { paths += sections[it] }
            continue
        }
        val terminalPoints = terminals.associateWith { terminal ->
            val roadIndex = incident[terminal]!!.first()
            val road = sections[roadIndex]
            if (road.startNode == terminal) road.points.first() else road.points.last()
        }
        val firstNode = terminals.minWithOrNull(compareBy<Long>({ terminalPoints[it]?.lon ?: 0.0 }, { terminalPoints[it]?.lat ?: 0.0 })) ?: continue
        val orderedIndices = mutableListOf<Int>()
        val visited = hashSetOf<Int>()
        var node = firstNode
        while (true) {
            val next = incident[node].orEmpty().firstOrNull { it in component && it !in visited } ?: break
            visited += next
            orderedIndices += next
            val road = sections[next]
            node = if (road.startNode == node) road.endNode ?: break else road.startNode ?: break
        }
        if (orderedIndices.size != component.size) {
            component.forEach { paths += sections[it] }
            continue
        }
        val first = sections[orderedIndices.first()]
        val points = mutableListOf<GeoPoint>()
        val nodes = mutableListOf<Long>()
        var pathNode = firstNode
        orderedIndices.forEachIndexed { sequenceIndex, sectionIndex ->
            val road = sections[sectionIndex]
            val forward = road.startNode == pathNode
            val roadPoints = if (forward) road.points else road.points.asReversed()
            val roadNodes = if (forward) road.nodeIds else road.nodeIds.asReversed()
            if (sequenceIndex == 0) { points += roadPoints; nodes += roadNodes }
            else { points += roadPoints.drop(1); nodes += roadNodes.drop(1) }
            pathNode = if (forward) road.endNode!! else road.startNode!!
        }
        paths += Road(
            id = "path:" + orderedIndices.joinToString("+") { sections[it].id },
            name = first.name,
            kind = first.kind,
            nodeIds = nodes,
            points = points,
            nodeIdsVerified = orderedIndices.all { sections[it].nodeIdsVerified },
            continuousPath = true
        )
    }
    return paths
}

fun findRoadPathById(roads: List<Road>, id: String): Road? =
    groupRoadsByName(roads).asSequence()
        .flatMap { (_, sections) -> continuousNamedRoadPaths(sections).asSequence() }
        .firstOrNull { it.id == id }

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

