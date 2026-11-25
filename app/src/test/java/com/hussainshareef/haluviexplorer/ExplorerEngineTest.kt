package com.hussainshareef.haluviexplorer

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

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

    @Test fun sharedInternalNetworkNodeSplitsWayIntoConnectedRoadSections() {
        val through = Road("through", "Through Road", "residential", listOf(10, 11, 12), listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002)
        ))
        val branch = Road("branch", "Branch Road", "residential", listOf(11, 13), listOf(
            GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)
        ))
        val sections = splitRoadsAtSharedNodes(listOf(through, branch))
        val throughParts = sections.filter { it.name == "Through Road" }
        assertEquals(2, throughParts.size)
        assertEquals(11L, throughParts[0].endNode)
        assertTrue(sections.any { it.name == "Branch Road" && it.startNode == 11L })
        val map = MapData("Test", 0, sections, emptyList())
        val engine = ExplorerEngine(map).apply { start(throughParts[0], 0.0); move(throughParts[0].lengthMeters) }
        assertEquals(setOf("Through Road", "Branch Road"), engine.connections().map { it.road.name }.toSet())
    }

    @Test fun roadChooserGroupsShortGeometrySectionsUnderOneNamedRoad() {
        val sectionA = Road("w1:part:0", "Haveree Hingun", "residential", listOf(1, 2), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.00005)))
        val sectionB = Road("w1:part:1", "Haveree Hingun", "residential", listOf(2, 3), listOf(GeoPoint(0.0, 0.00005), GeoPoint(0.0, 0.0001)))
        val other = Road("w2", "Kanbaa Aisa Rani Hingun", "residential", listOf(4, 5), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0002)))
        val groups = groupRoadsByName(listOf(sectionA, sectionB, other))
        assertEquals(2, groups.size)
        val haveree = groups.single { it.first == "Haveree Hingun" }
        assertEquals(2, haveree.second.size)
        assertEquals(1, groupRoadsByName(listOf(sectionA, sectionB, other), "haveree").size)
    }

    @Test fun islandSearchRejectsBuildingsAndAcceptsSettlements() {
        assertTrue(isIslandOrSettlementResult(JSONObject("""{"category":"place","type":"city"}""")))
        assertTrue(isIslandOrSettlementResult(JSONObject("""{"category":"place","type":"island"}""")))
        assertFalse(isIslandOrSettlementResult(JSONObject("""{"category":"amenity","type":"courthouse"}""")))
        assertFalse(isIslandOrSettlementResult(JSONObject("""{"category":"building","type":"yes"}""")))
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

    @Test fun parserKeepsHighwayRoadWaysWhenNodeIdsAreMissing() {
        val response = """{"elements":[{"type":"way","id":77,"geometry":[{"lat":4.17,"lon":73.50},{"lat":4.171,"lon":73.501}],"tags":{"highway":"road","name":"Mapped Lane"}}]}"""
        val map = MapData.fromOverpass("Malé", response)
        assertEquals("Mapped Lane", map.roads.single().name)
        assertEquals(2, map.roads.single().nodeIds.size)
    }

    @Test fun importsNavigationSharedMapRoadsAndNonGooglePlacesOnly() {
        val backup = """{"app":"Haluvi Navigation","version":2,"kind":"full","osmRoads":{"K:Malé":[{"name":"Majeedhee Magu","points":[[4.175,73.51],[4.1751,73.511]],"kind":"residential","oneway":false}]},"locations":[{"id":"osm-1","name":"Pharmacy","lat":4.17515,"lon":73.5105,"source":"osm_map","categoryId":"health","islandKey":"K:Malé","islandName":"Kaafu Malé"},{"id":"google-1","name":"Google Place","lat":4.17516,"lon":73.5106,"source":"google","categoryId":"shop","islandKey":"K:Malé","islandName":"Kaafu Malé"}],"roadNames":[{"island":"K:Malé","name":"Recorded Lane","points":[[4.1751,73.511],[4.1752,73.512]],"recorded":true,"width":"narrow"}]}"""
        val batch = HaluviBackupImporter.parse(backup)
        assertEquals(1, batch.maps.size)
        val map = batch.maps.single()
        assertEquals("K:Malé", map.sourceIslandKey)
        assertEquals("Kaafu Malé", map.islandName)
        assertEquals(2, map.roads.size)
        assertTrue(map.roads.any { it.name == "Majeedhee Magu" })
        assertEquals("left", ExplorerEngine(map).also { it.start(map.roads.first(), map.roads.first().lengthMeters / 2) }
            .nearbyPlaces(50.0).single().side)
        assertEquals(1, map.places.size)
        assertEquals("osm-1", map.places.single().id)
        assertFalse(map.places.single().entranceVerified)
        assertEquals(1, batch.googlePlacesSkipped)
        assertEquals(0, batch.unsupportedPlacesSkipped)
        assertFalse(map.connectivityVerified)
    }

    @Test fun refusesNonNavigationJsonAsMapBackup() {
        try {
            HaluviBackupImporter.parse("{\"roads\":[]}")
            fail("A different JSON file must not be imported as a Navigation backup.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
