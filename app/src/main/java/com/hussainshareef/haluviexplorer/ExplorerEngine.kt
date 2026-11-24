package com.hussainshareef.haluviexplorer

import kotlin.math.*

data class RoadConnection(val road: Road, val sharedNode: Long, val joinIndex: Int, val bearingOut: Double, val turn: Double)
data class NearbyPlace(val place: Place, val distance: Double, val roadDistance: Double, val side: String, val alongDistance: Double)

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
    private val visitedPlaces = linkedSetOf<String>()

    fun start(onRoad: Road, atProgress: Double, faceAlongRoad: Boolean = true) {
        road = onRoad
        progressMeters = atProgress.coerceIn(0.0, onRoad.lengthMeters)
        travelSign = if (atProgress <= onRoad.lengthMeters / 2) 1 else -1
        val pathBearing = bearingAt(onRoad, progressMeters, travelSign)
        facingBearing = if (faceAlongRoad) pathBearing else (pathBearing + 180) % 360
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
        start(onRoad, progress)
        travelSign = if (sign < 0) -1 else 1
        facingBearing = (facing % 360 + 360) % 360
        travelledMeters = distance.coerceAtLeast(0.0)
        visitedJunctions = junctions.coerceAtLeast(0)
    }

    fun position(): GeoPoint? = road?.let { pointAt(it, progressMeters) }
    fun facing(): String = compassName(facingBearing)
    fun remainingToRoadEnd(): Double = road?.let { if (travelSign > 0) it.lengthMeters - progressMeters else progressMeters } ?: 0.0
    fun atRoadEnd(): Boolean = remainingToRoadEnd() <= 1.5

    /** Moves along the current road geometry and stops at its endpoint. It never chooses a branch automatically. */
    fun move(metres: Double): Double {
        val current = road ?: return 0.0
        val moved = min(abs(metres), remainingToRoadEnd())
        progressMeters = (progressMeters + moved * travelSign).coerceIn(0.0, current.lengthMeters)
        travelledMeters += moved
        return moved
    }

    /** Moving backward changes travel along the road but deliberately keeps facing direction unchanged. */
    fun moveBackward(metres: Double): Double {
        travelSign = -travelSign
        val moved = move(metres)
        travelSign = -travelSign
        return moved
    }

    fun turnAround() {
        travelSign = -travelSign
        facingBearing = (facingBearing + 180.0) % 360.0
    }

    fun connections(): List<RoadConnection> {
        val current = road ?: return emptyList()
        val endpointId = if (travelSign > 0) current.endNode else current.startNode
        if (endpointId == null) return emptyList()
        val currentPoint = if (travelSign > 0) current.points.last() else current.points.first()
        return map.roads.asSequence().filter { it.id != current.id }
            .mapNotNull { candidate ->
                val index = candidate.nodeIds.indexOf(endpointId)
                if (index < 0 || index != 0 && index != candidate.nodeIds.lastIndex) return@mapNotNull null
                val nextIndex = if (index == 0) 1 else candidate.points.lastIndex - 1
                val bearing = bearingDegrees(currentPoint, candidate.points[nextIndex])
                val delta = signedAngle(facingBearing, bearing)
                RoadConnection(candidate, endpointId, index, bearing, delta)
            }.toList()
    }

    fun takeConnection(connection: RoadConnection) {
        road = connection.road
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

    fun chooseTurn(turn: String): RoadConnection? {
        if (!atRoadEnd()) return null
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
        return map.places.mapNotNull { p ->
            val (along, foot, _) = projectToLine(current.points, p.point)
            val d = distanceMeters(at, p.point)
            if (d > radiusMeters) null else {
                val path = pointAt(current, along)
                val segmentBearing = bearingAt(current, along, travelSign)
                val toPlace = bearingDegrees(path, p.point)
                val relative = signedAngle(facingBearing, toPlace)
                val side = when { d < 1.0 -> "nearby"; abs(relative) <= 55 -> "ahead"; abs(relative) >= 125 -> "behind"; relative < 0 -> "left"; else -> "right" }
                NearbyPlace(p, d, distanceMeters(foot, p.point), side, along)
            }
        }.sortedBy { it.distance }
    }

    fun nextPlace(radiusMeters: Double = 150.0): NearbyPlace? {
        val current = road ?: return null
        val position = progressMeters
        return nearbyPlaces(radiusMeters).filter { item ->
            if (item.roadDistance > 40.0) return@filter false
            val delta = (item.alongDistance - position) * travelSign
            delta >= 1.0
        }.minByOrNull { (it.alongDistance - position) * travelSign }
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
        val endpointKind = if (connections().isEmpty() && remainingToRoadEnd() < 1.0) " You have reached a road endpoint." else ""
        return "You are on ${r.name}, facing ${facing()}. ${remainingToRoadEnd().roundToInt()} metres to the end in your current direction.$endpointKind"
    }

    fun markVisited(place: Place) { visitedPlaces += place.id }
    fun visitedPlaceCount(): Int = visitedPlaces.size

    private fun bearingAt(r: Road, progress: Double, sign: Int): Double {
        val projected = pointAt(r, progress)
        val (_, _, index) = projectToLine(r.points, projected)
        val from = if (sign >= 0) r.points[index] else r.points[index + 1]
        val to = if (sign >= 0) r.points[index + 1] else r.points[index]
        return bearingDegrees(from, to)
    }

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
        var bestDistance = Double.MAX_VALUE; var bestAlong = 0.0; var bestPoint = points.first(); var bestIndex = 0; var prefix = 0.0
        for (i in 0 until points.lastIndex) {
            val a = points[i]; val b = points[i + 1]
            val meanLat = Math.toRadians((a.lat + b.lat + p.lat) / 3.0)
            val scaleX = cos(meanLat) * 111_320.0; val scaleY = 111_320.0
            val ax = a.lon * scaleX; val ay = a.lat * scaleY; val bx = b.lon * scaleX; val by = b.lat * scaleY
            val px = p.lon * scaleX; val py = p.lat * scaleY
            val dx = bx - ax; val dy = by - ay
            val fraction = if (dx * dx + dy * dy == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            val candidate = GeoPoint(a.lat + (b.lat - a.lat) * fraction, a.lon + (b.lon - a.lon) * fraction)
            val distance = distanceMeters(candidate, p)
            if (distance < bestDistance) {
                bestDistance = distance; bestPoint = candidate; bestIndex = i
                bestAlong = prefix + distanceMeters(a, b) * fraction
            }
            prefix += distanceMeters(a, b)
        }
        return Triple(bestAlong, bestPoint, bestIndex)
    }

    private fun signedAngle(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}

