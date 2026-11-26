package com.hussainshareef.haluviexplorer

import kotlin.math.*

data class RoadConnection(val road: Road, val sharedNode: Long, val joinIndex: Int, val bearingOut: Double, val turn: Double)
data class NearbyPlace(
    val place: Place,
    val distance: Double,
    val roadDistance: Double,
    val side: String,
    val alongDistance: Double,
    /** Index in the original map list, so separate records with identical IDs remain traversable. */
    val recordIndex: Int = -1,
    /** Describes whether this road association comes from address text or only geometry. */
    val association: String = "estimated geometry"
)
data class RoadCrossing(val roadName: String, val side: String, val verified: Boolean, val alongDistance: Double)

private data class RoadPlaceProjection(
    val recordIndex: Int,
    val place: Place,
    val alongDistance: Double,
    val foot: GeoPoint,
    val roadDistance: Double,
    val segmentIndex: Int,
    val addressMatched: Boolean,
    val hasAddressHint: Boolean
)

class ExplorerEngine(private val map: MapData) {
    var road: Road? = null
        private set
    var progressMeters: Double = 0.0
        private set
    var travelSign: Int = 1
        private set
    var facingBearing: Double = 0.0
        private set
    var travelledMeters: Double = 0.0
        private set
    var visitedJunctions: Int = 0
        private set
    private var arrivedNodeId: Long? = null
    private val visitedPlaces = linkedSetOf<String>()
    private var placeNavigationRoadId: String? = null
    private var placeNavigationRecordIndex: Int? = null
    private var placeNavigationProgress: Double? = null
    /** The expensive place-to-road projection is computed once per selected road, then reused by every control. */
    private val placeProjectionCache = HashMap<String, List<RoadPlaceProjection>>()

    fun start(onRoad: Road, atProgress: Double, faceAlongRoad: Boolean = true) {
        clearPlaceNavigationCursor()
        road = onRoad
        arrivedNodeId = null
        progressMeters = atProgress.coerceIn(0.0, onRoad.lengthMeters)
        travelSign = if (atProgress <= onRoad.lengthMeters / 2) 1 else -1
        if (!faceAlongRoad) travelSign = -travelSign
        facingBearing = bearingAt(onRoad, progressMeters, travelSign)
    }

    fun nearestRoad(point: GeoPoint): Pair<Road, Double>? = map.roads.map { candidate ->
        val projected = projectToLine(candidate.points, point)
        Triple(candidate, projected.first, distanceMeters(projected.second, point))
    }.minByOrNull { it.third }?.let { it.first to it.second }

    fun startNear(point: GeoPoint): Road? {
        val nearest = nearestRoad(point) ?: return null
        start(nearest.first, nearest.second)
        return nearest.first
    }

    fun restore(onRoad: Road, progress: Double, sign: Int, facing: Double, distance: Double, junctions: Int) {
        clearPlaceNavigationCursor()
        start(onRoad, progress)
        travelSign = if (sign < 0) -1 else 1
        facingBearing = (facing % 360 + 360) % 360
        travelledMeters = distance.coerceAtLeast(0.0)
        visitedJunctions = junctions.coerceAtLeast(0)
        val cumulative = cumulativeDistances(onRoad)
        arrivedNodeId = onRoad.nodeIds.indices.firstOrNull { index ->
            abs(cumulative[index] - progressMeters) <= 1.5 &&
                (index == 0 || index == onRoad.nodeIds.lastIndex ||
                    onRoad.continuousPath && hasDifferentNamedConnection(onRoad, onRoad.nodeIds[index]))
        }?.let { onRoad.nodeIds[it] }
    }

    fun position(): GeoPoint? = road?.let { pointAt(it, progressMeters) }
    fun facing(): String = compassName(facingBearing)
    fun remainingToRoadEnd(): Double = road?.let { if (travelSign > 0) it.lengthMeters - progressMeters else progressMeters } ?: 0.0
    fun remainingToDecisionPoint(): Double = road?.let { current ->
        val arrived = arrivedNodeId?.let(current.nodeIds::indexOf)?.takeIf { it >= 0 }
        if (arrived != null) return@let 0.0
        val index = nextDecisionNodeIndex(current) ?: return@let remainingToRoadEnd()
        abs(progressMeters - distanceAtNode(current, index))
    } ?: 0.0
    fun atDecisionPoint(): Boolean = remainingToDecisionPoint() <= 1.5
    fun atRoadEnd(): Boolean = atDecisionPoint()

    /** Moves along the current road geometry and stops at its endpoint. It never chooses a branch automatically. */
    fun move(metres: Double): Double {
        clearPlaceNavigationCursor()
        val current = road ?: return 0.0
        val nextStop = nextDecisionNodeIndex(current)
        val moved = min(abs(metres), remainingToDecisionPoint())
        progressMeters = (progressMeters + moved * travelSign).coerceIn(0.0, current.lengthMeters)
        travelledMeters += moved
        if (moved > 0.0) facingBearing = bearingAt(current, progressMeters, travelSign)
        if (moved > 0.0 && nextStop != null && abs(progressMeters - distanceAtNode(current, nextStop)) <= 1.5) {
            arrivedNodeId = current.nodeIds[nextStop]
        }
        return moved
    }

    /** Moving backward changes travel along the road but deliberately keeps facing direction unchanged. */
    fun moveBackward(metres: Double): Double {
        val facing = facingBearing
        val wasAtNode = arrivedNodeId
        arrivedNodeId = null
        travelSign = -travelSign
        val moved = move(metres)
        travelSign = -travelSign
        facingBearing = facing
        if (moved == 0.0) arrivedNodeId = wasAtNode
        return moved
    }

    fun moveBackwardToDecisionPoint(): Double {
        if (road == null) return 0.0
        val wasAtNode = arrivedNodeId
        arrivedNodeId = null
        travelSign = -travelSign
        val distance = remainingToDecisionPoint()
        travelSign = -travelSign
        if (distance <= 0.0) { arrivedNodeId = wasAtNode; return 0.0 }
        return moveBackward(distance)
    }

    /** Moves to a selected place farther along this exact road path, passing mapped junctions on the chosen road. */
    fun moveAlongRoadTo(targetProgress: Double, keepFacing: Boolean = false): Double {
        clearPlaceNavigationCursor()
        val current = road ?: return 0.0
        val target = targetProgress.coerceIn(0.0, current.lengthMeters)
        val originalFacing = facingBearing
        val previous = progressMeters
        val moved = abs(target - previous)
        travelledMeters += moved
        val cumulative = cumulativeDistances(current)
        visitedJunctions += current.nodeIds.indices.count { index ->
            val at = cumulative[index]
            at in min(previous, target)..max(previous, target) && hasDifferentNamedConnection(current, current.nodeIds[index])
        }
        progressMeters = target
        val targetSign = if (target >= previous) travelSign else -travelSign
        facingBearing = if (keepFacing) originalFacing else bearingAt(current, target, targetSign)
        arrivedNodeId = null
        return moved
    }

    fun turnAround(clearCurrentDecision: Boolean = false) {
        clearPlaceNavigationCursor()
        travelSign = -travelSign
        facingBearing = (facingBearing + 180.0) % 360.0
        if (clearCurrentDecision) arrivedNodeId = null
    }

    fun connections(): List<RoadConnection> {
        val current = road ?: return emptyList()
        val stopIndex = arrivedNodeId?.let(current.nodeIds::indexOf)?.takeIf { it >= 0 }
            ?: nextDecisionNodeIndex(current) ?: return emptyList()
        val endpointId = current.nodeIds.getOrNull(stopIndex)
        if (endpointId == null) return emptyList()
        val currentPoint = current.points.getOrNull(stopIndex) ?: return emptyList()
        return map.roads.asSequence().filter { it.id != current.id }
            .mapNotNull { candidate ->
                if (current.continuousPath && candidate.name.equals(current.name, ignoreCase = true)) return@mapNotNull null
                val index = candidate.nodeIds.indexOf(endpointId)
                if (index < 0 || index != 0 && index != candidate.nodeIds.lastIndex) return@mapNotNull null
                val nextIndex = if (index == 0) 1 else candidate.points.lastIndex - 1
                val bearing = bearingDegrees(currentPoint, candidate.points[nextIndex])
                val delta = signedAngle(facingBearing, bearing)
                RoadConnection(candidate, endpointId, index, bearing, delta)
            }.toList()
    }

    fun takeConnection(connection: RoadConnection) {
        clearPlaceNavigationCursor()
        road = connection.road
        arrivedNodeId = null
        if (connection.joinIndex == 0) {
            progressMeters = 0.0
            travelSign = 1
        } else {
            progressMeters = road!!.lengthMeters
            travelSign = -1
        }
        facingBearing = connection.bearingOut
        visitedJunctions++
    }

    fun canContinueOnCurrentRoad(): Boolean {
        val current = road ?: return false
        val index = arrivedNodeId?.let(current.nodeIds::indexOf) ?: return false
        return current.continuousPath && index in 1 until current.nodeIds.lastIndex && atDecisionPoint()
    }

    fun continueOnCurrentRoad(): Boolean {
        if (!canContinueOnCurrentRoad()) return false
        clearPlaceNavigationCursor()
        arrivedNodeId = null
        visitedJunctions++
        return true
    }

    fun chooseTurn(turn: String): RoadConnection? {
        if (!atDecisionPoint()) return null
        val options = connections()
        val chosen = when (turn) {
            "left" -> options.filter { it.turn < -25 }.minByOrNull { abs(it.turn + 90) }
            "right" -> options.filter { it.turn > 25 }.minByOrNull { abs(it.turn - 90) }
            "straight" -> options.minByOrNull { abs(it.turn) }
            else -> null
        } ?: return null
        takeConnection(chosen)
        return chosen
    }

    fun nearbyPlaces(radiusMeters: Double): List<NearbyPlace> {
        val current = road ?: return emptyList()
        val at = position() ?: return emptyList()
        return projectionsFor(current).mapNotNull { item ->
            val d = distanceMeters(at, item.place.point)
            if (d > radiusMeters) null else {
                val toPlace = bearingDegrees(item.foot, item.place.point)
                val relative = signedAngle(facingBearing, toPlace)
                val side = when { d < 1.0 -> "nearby"; abs(relative) <= 55 -> "ahead"; abs(relative) >= 125 -> "behind"; relative < 0 -> "left"; else -> "right" }
                NearbyPlace(item.place, d, item.roadDistance, side, item.alongDistance, item.recordIndex, item.association())
            }
        }.sortedBy { it.distance }
    }

    fun nextPlace(): NearbyPlace? {
        val current = road ?: return null
        val places = placesOnCurrentRoad(current, travelSign)
            .sortedWith(placeOrder(travelSign))
        val cursor = currentPlaceCursorIndex(current, places)
        if (cursor != null) return places.getOrNull(cursor + 1)
        return places.firstOrNull { (it.alongDistance - progressMeters) * travelSign >= 0.1 }
    }

    fun previousPlace(): NearbyPlace? {
        val current = road ?: return null
        val direction = -travelSign
        val places = placesOnCurrentRoad(current, travelSign, facingBearing)
            .sortedWith(placeOrder(travelSign))
        val cursor = currentPlaceCursorIndex(current, places)
        if (cursor != null) return places.getOrNull(cursor - 1)
        return places.firstOrNull { (it.alongDistance - progressMeters) * direction >= 0.1 }
    }

    /** All saved places that can be associated with this route, without a distance cut-off. */
    fun placesAlongCurrentRoad(): List<NearbyPlace> {
        val current = road ?: return emptyList()
        return placesOnCurrentRoad(current, travelSign).sortedWith(placeOrder(1))
    }

    /** Finds every road-associated place crossed during one movement, even if the step exceeds its 4 m vicinity. */
    fun placesPassedBetween(fromProgress: Double, toProgress: Double, keepFacing: Boolean = false): List<NearbyPlace> {
        val current = road ?: return emptyList()
        if (abs(toProgress - fromProgress) < 0.01) return emptyList()
        val low = min(fromProgress, toProgress)
        val high = max(fromProgress, toProgress)
        val direction = if (toProgress >= fromProgress) 1 else -1
        val at = pointAt(current, toProgress)
        return placesOnCurrentRoad(current, direction, if (keepFacing) facingBearing else null)
            .filter { it.alongDistance > low + 0.01 && it.alongDistance <= high + 0.01 }
            .map { it.copy(distance = distanceMeters(at, it.place.point)) }
            .sortedWith(placeOrder(direction))
    }

    /** Records the selected place so repeated next/previous actions traverse even co-located records. */
    fun rememberPlaceNavigation(place: Place) {
        val current = road ?: return
        val index = map.places.indexOfFirst { it === place || it == place }
        rememberPlaceNavigation(current, place, index)
    }

    /** Records the exact map row, including when two source records share the same ID and coordinates. */
    fun rememberPlaceNavigation(place: NearbyPlace) {
        val current = road ?: return
        rememberPlaceNavigation(current, place.place, place.recordIndex)
    }

    fun placeNavigationRecordIndex(): Int? = placeNavigationRecordIndex

    fun restorePlaceNavigation(recordIndex: Int?) {
        val current = road ?: return
        if (recordIndex == null || recordIndex !in map.places.indices) return
        placeNavigationRoadId = current.id
        placeNavigationRecordIndex = recordIndex
        placeNavigationProgress = progressMeters
    }

    private fun rememberPlaceNavigation(current: Road, place: Place, recordIndex: Int) {
        placeNavigationRoadId = current.id
        placeNavigationRecordIndex = recordIndex.takeIf { it >= 0 }
        placeNavigationProgress = progressMeters
    }

    /** Returns mapped side roads at shared road nodes crossed between the current and target positions. */
    fun roadCrossingsTo(targetProgress: Double, keepFacing: Boolean = false): List<RoadCrossing> {
        val current = road ?: return emptyList()
        if (current.nodeIds.size != current.points.size || current.nodeIds.size < 2) return emptyList()
        val target = targetProgress.coerceIn(0.0, current.lengthMeters)
        val from = progressMeters
        if (abs(target - from) < 0.5) return emptyList()
        val low = min(from, target)
        val high = max(from, target)
        val direction = if (target >= from) 1 else -1
        val cumulative = cumulativeDistances(current)
        val results = mutableListOf<RoadCrossing>()
        val seen = mutableSetOf<Triple<Long, String, String>>()
        for (index in current.nodeIds.indices) {
            val along = cumulative[index]
            if (along <= low + 0.5 || along > high + 0.5) continue
            val nodeId = current.nodeIds[index]
            val atNode = current.points[index]
            val currentBearing = if (keepFacing) facingBearing else bearingAt(current, along, direction)
            map.roads.asSequence()
                .filter { candidate -> candidate.id != current.id && !candidate.name.equals(current.name, ignoreCase = true) }
                .mapNotNull { candidate ->
                    val outward = when {
                        candidate.startNode == nodeId && candidate.points.size > 1 -> candidate.points[1]
                        candidate.endNode == nodeId && candidate.points.size > 1 -> candidate.points[candidate.points.lastIndex - 1]
                        else -> null
                    } ?: return@mapNotNull null
                    val delta = signedAngle(currentBearing, bearingDegrees(atNode, outward))
                    val side = when {
                        abs(delta) <= 35.0 -> "straight"
                        delta < 0.0 -> "left"
                        else -> "right"
                    }
                    val key = Triple(nodeId, candidate.name.trim().lowercase(), side)
                    if (!seen.add(key)) return@mapNotNull null
                    RoadCrossing(candidate.name, side,
                        current.nodeIdsVerified && candidate.nodeIdsVerified, along)
                }.forEach(results::add)
        }
        return results.sortedBy { if (direction > 0) it.alongDistance else -it.alongDistance }
    }

    fun nearestJunctionDistance(): Double? {
        val options = connections()
        if (options.isNotEmpty()) return remainingToRoadEnd()
        val current = road ?: return null
        val end = if (travelSign > 0) current.endNode else current.startNode
        val at = position() ?: return null
        return if (end != null && map.roads.any { it.id != current.id && end in listOf(it.startNode, it.endNode) }) remainingToRoadEnd() else null
    }

    fun whereAmI(): String {
        val r = road ?: return "No road selected."
        val endpointKind = if (atDecisionPoint() && connections().isEmpty()) " You are at the road end." else ""
        val next = if (connections().isEmpty()) "road end" else "junction"
        return "${r.name}. Facing ${facing()}. $next in ${remainingToDecisionPoint().roundToInt()} metres.$endpointKind"
    }

    fun markVisited(place: Place) { visitedPlaces += place.recordIdentity() }
    fun markVisited(place: NearbyPlace) {
        val p = place.place
        visitedPlaces += "${place.recordIndex}|${p.source}|${p.id}|${p.name}|${p.category}|${p.point.lat}|${p.point.lon}"
    }
    fun visitedPlaceCount(): Int = visitedPlaces.size

    private fun placeOrder(direction: Int): Comparator<NearbyPlace> =
        compareBy<NearbyPlace> { it.alongDistance * direction }.thenBy { it.place.name.lowercase() }
            .thenBy { it.place.source.lowercase() }.thenBy { it.place.id }.thenBy { it.recordIndex }

    private fun currentPlaceCursorIndex(current: Road, ordered: List<NearbyPlace>): Int? {
        if (placeNavigationRoadId != current.id || abs((placeNavigationProgress ?: Double.MAX_VALUE) - progressMeters) > 1.5) return null
        val exactRecord = placeNavigationRecordIndex?.let { index -> ordered.indexOfFirst { it.recordIndex == index } }
        if (exactRecord != null && exactRecord >= 0) return exactRecord
        return null
    }

    private fun clearPlaceNavigationCursor() {
        placeNavigationRoadId = null
        placeNavigationRecordIndex = null
        placeNavigationProgress = null
    }

    private fun Place.recordIdentity(): String = "$source|$id|$name|$category|${point.lat}|${point.lon}"

    private fun bearingAt(r: Road, progress: Double, sign: Int): Double {
        val projected = pointAt(r, progress)
        val (_, _, index) = projectToLine(r.points, projected)
        val from = if (sign >= 0) r.points[index] else r.points[index + 1]
        val to = if (sign >= 0) r.points[index + 1] else r.points[index]
        return bearingDegrees(from, to)
    }

    private fun placesOnCurrentRoad(current: Road, direction: Int, facingOverride: Double? = null): List<NearbyPlace> {
        return projectionsFor(current).asSequence().filter { !it.hasAddressHint || it.addressMatched }.map { item ->
                val bearing = bearingDegrees(current.points[item.segmentIndex], current.points[item.segmentIndex + 1])
                val travelBearing = if (direction >= 0) bearing else (bearing + 180.0) % 360.0
                val relative = signedAngle(facingOverride ?: travelBearing, bearingDegrees(item.foot, item.place.point))
                val side = when {
                    item.roadDistance < 1.0 -> "nearby"
                    abs(relative) <= 55 -> "ahead"
                    abs(relative) >= 125 -> "behind"
                    relative < 0 -> "left"
                    else -> "right"
                }
                NearbyPlace(item.place, abs(item.alongDistance - progressMeters), item.roadDistance, side,
                    item.alongDistance, item.recordIndex, item.association())
        }.toList()
    }

    private fun projectionsFor(current: Road): List<RoadPlaceProjection> = placeProjectionCache.getOrPut(current.id) {
        map.places.mapIndexedNotNull { index, place ->
            val hints = place.roadAddressHints()
            val addressMatched = hints.any { roadNameInAddress(current.name, it) }
            val (along, foot, segmentIndex) = projectToLine(current.points, place.point)
            RoadPlaceProjection(index, place, along, foot, distanceMeters(foot, place.point), segmentIndex, addressMatched, hints.isNotEmpty())
        }
    }

    private fun RoadPlaceProjection.association(): String = when {
        addressMatched -> "address names this road"
        hasAddressHint -> "address names another road"
        roadDistance <= 40.0 -> "nearby geometry estimate"
        else -> "distant nearest-road estimate"
    }

    private fun roadNameInAddress(roadName: String, address: String): Boolean {
        fun normalize(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val road = normalize(roadName)
        val text = normalize(address)
        return road.isNotBlank() && " $text ".contains(" $road ")
    }

    private fun nextDecisionNodeIndex(current: Road): Int? {
        if (current.nodeIds.size != current.points.size || current.nodeIds.size < 2) return null
        val cumulative = cumulativeDistances(current)
        val indices = current.nodeIds.indices.filter { index ->
            val endpoint = index == 0 || index == current.nodeIds.lastIndex
            endpoint || current.continuousPath && hasDifferentNamedConnection(current, current.nodeIds[index])
        }
        return if (travelSign > 0) indices.firstOrNull { cumulative[it] > progressMeters + 1.5 }
            else indices.lastOrNull { cumulative[it] < progressMeters - 1.5 }
    }

    private fun hasDifferentNamedConnection(current: Road, nodeId: Long): Boolean = map.roads.any { candidate ->
        candidate.id != current.id && !candidate.name.equals(current.name, ignoreCase = true) &&
            (candidate.startNode == nodeId || candidate.endNode == nodeId)
    }

    private fun cumulativeDistances(current: Road): DoubleArray {
        val distances = DoubleArray(current.points.size)
        for (index in 1 until current.points.size) {
            distances[index] = distances[index - 1] + distanceMeters(current.points[index - 1], current.points[index])
        }
        return distances
    }

    private fun distanceAtNode(current: Road, index: Int): Double = cumulativeDistances(current).getOrElse(index) { 0.0 }

    private fun pointAt(r: Road, distance: Double): GeoPoint {
        var rest = distance.coerceIn(0.0, r.lengthMeters)
        for (i in 0 until r.points.lastIndex) {
            val a = r.points[i]; val b = r.points[i + 1]
            val length = distanceMeters(a, b)
            if (length == 0.0) continue
            if (rest <= length) {
                val fraction = rest / length
                return GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
            }
            rest -= length
        }
        return r.points.last()
    }

    private fun projectToLine(points: List<GeoPoint>, p: GeoPoint): Triple<Double, GeoPoint, Int> {
        // Compare candidates in a local equirectangular plane, then callers calculate one
        // final great-circle distance. This avoids a trigonometric distance call per segment
        // for every imported Google/OSM place, which was the hot path behind slow place steps.
        val scaleX = cos(Math.toRadians(p.lat)) * 111_320.0
        val scaleY = 111_320.0
        val px = p.lon * scaleX
        val py = p.lat * scaleY
        var bestSquaredDistance = Double.MAX_VALUE
        var bestAlong = 0.0
        var bestPoint = points.first()
        var bestIndex = 0
        var prefix = 0.0
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]
            val ax = a.lon * scaleX; val ay = a.lat * scaleY; val bx = b.lon * scaleX; val by = b.lat * scaleY
            val dx = bx - ax; val dy = by - ay
            val fraction = if (dx * dx + dy * dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val candidate = GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
            val offsetX = px - (ax + dx * fraction)
            val offsetY = py - (ay + dy * fraction)
            val squaredDistance = offsetX * offsetX + offsetY * offsetY
            val segmentLength = sqrt(dx * dx + dy * dy)
            if (squaredDistance < bestSquaredDistance) {
                bestSquaredDistance = squaredDistance; bestPoint = candidate; bestIndex = i
                bestAlong = prefix + segmentLength * fraction
            }
            prefix += segmentLength
        }
        return Triple(bestAlong, bestPoint, bestIndex)
    }

    private fun signedAngle(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}


