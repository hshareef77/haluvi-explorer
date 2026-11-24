package com.hussainshareef.haluviexplorer

import org.junit.Assert.*
import org.junit.Test

class ExplorerEngineTest {
    private val eastRoad = Road("east", "East Road", "residential", listOf(1, 2), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)))
    private val northRoad = Road("north", "North Road", "residential", listOf(2, 3), listOf(GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)))

    @Test fun movementFollowsBentRoadGeometryAndStopsAtItsEndpoint() {
        val bent = Road("bent", "Bent Road", "residential", listOf(10, 11, 12), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)))
        val map = MapData("Test", 0, listOf(bent), emptyList())
        val engine = ExplorerEngine(map)
        engine.start(bent, 0.0)
        val moved = engine.move(10_000.0)
        assertEquals(bent.lengthMeters, moved, 0.1)
        assertTrue(engine.atRoadEnd())
        assertNotNull(engine.position())
        assertTrue(bent.lengthMeters > distanceMeters(bent.points.first(), bent.points.last()))
    }

    @Test fun sharedNodeCreatesConnectionAndLeftTurnFollowsRelativeFacing() {
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad, northRoad), emptyList()))
        engine.start(eastRoad, 0.0)
        engine.move(eastRoad.lengthMeters)
        assertEquals(1, engine.connections().size)
        val selected = engine.chooseTurn("left")
        assertEquals("North Road", selected?.road?.name)
        assertEquals("north", engine.facing())
    }

    @Test fun geometricalCrossingWithoutSharedNodeDoesNotCreateConnection() {
        val crossing = Road("crossing", "Crossing", "residential", listOf(20, 21), listOf(GeoPoint(-0.001, 0.0005), GeoPoint(0.001, 0.0005)))
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad, crossing), emptyList()))
        engine.start(eastRoad, eastRoad.lengthMeters)
        assertTrue(engine.connections().isEmpty())
    }

    @Test fun movingBackwardKeepsFacingAndUsesActualRoadDirection() {
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), emptyList()))
        engine.start(eastRoad, eastRoad.lengthMeters / 2)
        val originalFacing = engine.facingBearing
        val originalProgress = engine.progressMeters
        assertTrue(engine.moveBackward(10.0) > 9.9)
        assertEquals(originalFacing, engine.facingBearing, 0.001)
        assertTrue(engine.progressMeters < originalProgress)
    }

    @Test fun startingFacingOppositeSetsForwardMovementAndBearingCorrectly() {
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), emptyList()))
        engine.start(eastRoad, eastRoad.lengthMeters / 2, faceAlongRoad = false)
        val initial = engine.progressMeters
        assertEquals("west", engine.facing())
        assertTrue(engine.move(10.0) > 9.9)
        assertTrue(engine.progressMeters < initial)
    }

    @Test fun leftAndRightDescriptionsReverseWhenFacingReverses() {
        val northSide = Place("north-side", "North Place", "shop", GeoPoint(0.00005, 0.0005), false)
        val map = MapData("Test", 0, listOf(eastRoad), listOf(northSide))
        val eastbound = ExplorerEngine(map)
        eastbound.start(eastRoad, eastRoad.lengthMeters / 2)
        assertEquals("left", eastbound.nearbyPlaces(50.0).single().side)
        val westbound = ExplorerEngine(map)
        westbound.start(eastRoad, eastRoad.lengthMeters)
        assertEquals("right", westbound.nearbyPlaces(100.0).single().side)
    }

    @Test fun landmarkSequenceReversesWithTravelDirection() {
        val first = Place("one", "First", "shop", GeoPoint(0.0, 0.0002), false)
        val second = Place("two", "Second", "shop", GeoPoint(0.0, 0.0008), false)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(first, second)))
        engine.start(eastRoad, 0.0)
        assertEquals("First", engine.nextPlace(200.0)?.place?.name)
        engine.start(eastRoad, eastRoad.lengthMeters)
        assertEquals("Second", engine.nextPlace(200.0)?.place?.name)
    }

    @Test fun parserRetainsRoadNodeIdentityAndPlaceCoordinates() {
        val response = """{"elements":[{"type":"way","id":7,"nodes":[1,2],"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":0.001}],"tags":{"highway":"residential","name":"Main Road"}},{"type":"node","id":9,"lat":0.0001,"lon":0.0003,"tags":{"amenity":"pharmacy","name":"Island Pharmacy"}}]}"""
        val map = MapData.fromOverpass("Island", response)
        assertEquals("Main Road", map.roads.single().name)
        assertEquals(listOf(1L, 2L), map.roads.single().nodeIds)
        assertEquals("Island Pharmacy", map.places.single().name)
    }
}
