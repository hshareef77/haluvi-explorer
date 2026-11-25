package com.hussainshareef.haluviexplorer

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

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

    @Test fun savedMapMatchingDoesNotTreatMaleAsPartOfHulhumale() {
        val kaafu = AtollEntry("K", "Kaafu", emptyList())
        val male = IslandEntry("Male'", 4.17, 73.5)
        val hulhumale = IslandEntry("Hulhumale'", 4.21, 73.54)
        val hulhumaleMap = StoredMapSummary(File("k-hulhumale.json"), "Kaafu Hulhumale'", "K:Hulhumale'", 20, 30, 0, 0, 0)
        val maleMap = StoredMapSummary(File("k-male.json"), "Kaafu Male'", "K:Male'", 10, 15, 0, 0, 0)
        assertFalse(storedMapMatchesIsland(hulhumaleMap, kaafu, male))
        assertTrue(storedMapMatchesIsland(hulhumaleMap, kaafu, hulhumale))
        assertTrue(storedMapMatchesIsland(maleMap, kaafu, male))
        assertFalse(storedMapMatchesIsland(maleMap, kaafu, hulhumale))
    }

    @Test fun separateOverpassRoadAndPlaceResponsesAreCombined() {
        val roadJson = """{"elements":[{"type":"way","id":42,"nodes":[1,2],"tags":{"highway":"residential","name":"Test Hingun"},"geometry":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":0.001}]}]}"""
        val placeJson = """{"elements":[{"type":"node","id":9,"lat":0.0,"lon":0.0005,"tags":{"amenity":"pharmacy","name":"Island Pharmacy"}}]}"""
        val map = MapData.fromOverpassParts("Test Island", roadJson, placeJson)
        assertEquals(1, map.roads.size)
        assertEquals("Test Hingun", map.roads.single().name)
        assertEquals(1, map.places.size)
        assertEquals("Island Pharmacy", map.places.single().name)
    }

    @Test fun bodyGeometryNodeIdsConfirmJunctionConnectivity() {
        val roadJson = """{"elements":[{"type":"way","id":41,"nodes":[1001,1002],"tags":{"highway":"residential","name":"First Road"},"geometry":[{"lat":4.17,"lon":73.5},{"lat":4.17,"lon":73.501}]},{"type":"way","id":42,"nodes":[1002,1003],"tags":{"highway":"residential","name":"Second Road"},"geometry":[{"lat":4.17,"lon":73.501},{"lat":4.171,"lon":73.501}]}]}"""
        val map = MapData.fromOverpassParts("Malé", roadJson, """{"elements":[]}""")
        assertTrue(map.connectivityVerified)
        assertTrue(map.roads.all { it.nodeIdsVerified })
        val restored = MapData.fromJson(map.toJson())
        assertTrue(restored.connectivityVerified)
        assertTrue(restored.roads.all { it.nodeIdsVerified })
        val engine = ExplorerEngine(map)
        val first = map.roads.single { it.name == "First Road" }
        engine.start(first, 0.0)
        engine.move(first.lengthMeters)
        assertEquals("Second Road", engine.connections().single().road.name)
    }

    @Test fun bundledAtollSnapshotIsParsedAndLimitedToSelectedIslandBounds() {
        val snapshot = """{"date":"2026-11-19","roads":[{"tags":{"highway":"residential","name":"Inside Road"},"geometry":[{"lat":1.0,"lon":1.0},{"lat":1.0,"lon":1.001}]},{"tags":{"highway":"residential","name":"Outside Road"},"geometry":[{"lat":2.0,"lon":2.0},{"lat":2.0,"lon":2.001}]}],"places":[{"lat":1.0,"lon":1.0,"tags":{"amenity":"pharmacy","name":"Island Pharmacy"}},{"lat":2.0,"lon":2.0,"tags":{"amenity":"school","name":"Other Island School"}}]}"""
        val map = MapData.fromNavigationAtoll("K", "Kaafu", snapshot, 0.99, 0.99, 1.01, 1.01)
        assertEquals("builtin:K:2026-11-19", map.sourceIslandKey)
        assertFalse(map.connectivityVerified)
        assertEquals(listOf("Inside Road"), map.roads.map { it.name })
        assertEquals(listOf("Island Pharmacy"), map.places.map { it.name })
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
        assertFalse(map.connectivityVerified)
        assertFalse(map.roads.single().nodeIdsVerified)
    }

    @Test fun importsNavigationRoadsAndAllPlaceSourcesIncludingGoogle() {
        val backup = """{"app":"Haluvi Navigation","version":2,"kind":"full","osmRoads":{"K:Malé":[{"name":"Majeedhee Magu","points":[[4.175,73.51],[4.1751,73.511]],"kind":"residential","oneway":false}]},"locations":[{"id":"osm-1","name":"Pharmacy","lat":4.17515,"lon":73.5105,"source":"osm_map","categoryId":"health","islandKey":"K:Malé","islandName":"Kaafu Malé"},{"id":"google-1","name":"Google Place","lat":4.17516,"lon":73.5106,"source":"google","categoryId":"shop","islandKey":"K:Malé","islandName":"Kaafu Malé"}],"roadNames":[{"island":"K:Malé","name":"Recorded Lane","points":[[4.1751,73.511],[4.1752,73.512]],"recorded":true,"width":"narrow"}]}"""
        val batch = HaluviBackupImporter.parse(backup)
        assertEquals(1, batch.maps.size)
        val map = batch.maps.single()
        assertEquals("K:Malé", map.sourceIslandKey)
        assertEquals("Kaafu Malé", map.islandName)
        assertEquals(2, map.roads.size)
        assertTrue(map.roads.any { it.name == "Majeedhee Magu" })
        val nearby = ExplorerEngine(map).also { it.start(map.roads.first(), map.roads.first().lengthMeters / 2) }.nearbyPlaces(50.0)
        assertEquals("left", nearby.single { it.place.id == "osm-1" }.side)
        assertEquals(2, map.places.size)
        assertEquals("osm-1", map.places.first { it.source == "osm_map" }.id)
        val google = map.places.single { it.source == "google" }
        assertEquals("google-1", google.id)
        assertEquals("Google Place", google.name)
        assertFalse(google.entranceVerified)
        assertEquals(0, batch.unsupportedPlacesSkipped)
        assertFalse(map.connectivityVerified)
    }

    @Test fun placeMergePreservesDifferentSourcesAtTheSameMappedPosition() {
        val point = GeoPoint(4.175, 73.51)
        val osm = Place("osm-1", "Central Pharmacy", "pharmacy", point, false, "osm_map")
        val google = Place("google-1", "Central Pharmacy", "pharmacy", point, false, "google")
        val merged = mergePlacesPreservingSources(listOf(osm), listOf(google, google))
        assertEquals(2, merged.size)
        assertEquals(setOf("osm_map", "google"), merged.map { it.source }.toSet())
    }

    @Test fun freshNodeAwareNetworkReplacesEstimatedRoadsAndKeepsImportedPlaces() {
        val points = listOf(GeoPoint(4.17, 73.5), GeoPoint(4.17, 73.501))
        val importedRoad = Road("nav:K:Male:0", "Main Road", "residential", listOf(-10, -11), points)
        val networkRoad = Road("w41", "Main Road", "residential", listOf(1001, 1002), points, nodeIdsVerified = true)
        val google = Place("google-place", "Local Shop", "shop", points.first(), false, "google")
        val oldMap = MapData("Kaafu Male'", 1, listOf(importedRoad), listOf(google), "K:Male'", false)
        val network = MapData("Kaafu Male'", 2, listOf(networkRoad), emptyList(), "K:Male", true)
        val updated = mergeDownloadedNetwork(oldMap, network)
        assertTrue(updated.connectivityVerified)
        assertEquals(listOf("w41"), updated.roads.map { it.id })
        assertEquals("google-place", updated.places.single().id)
    }

    @Test fun networkUpdateRetainsBackupRoadSectionsMissingFromOsm() {
        val verified = Road("w10", "Mapped Road", "residential", listOf(101, 102),
            listOf(GeoPoint(4.17, 73.5), GeoPoint(4.17, 73.501)), nodeIdsVerified = true)
        val olderUnmapped = Road("nav:K:Male:2", "Older Local Road", "user-recorded road", listOf(-201, -202),
            listOf(GeoPoint(4.18, 73.51), GeoPoint(4.18, 73.511)))
        val updated = mergeDownloadedNetwork(MapData("Male", 1, listOf(olderUnmapped), emptyList()),
            MapData("Male", 2, listOf(verified), emptyList(), connectivityVerified = true))
        assertEquals(2, updated.roads.size)
        assertTrue(updated.roads.single { it.id == "w10" }.nodeIdsVerified)
        assertFalse(updated.roads.single { it.id == "nav:K:Male:2" }.nodeIdsVerified)
        assertFalse(updated.connectivityVerified)
    }

    @Test fun importsRTLBusStopsInsteadOfDiscardingOtherSourceRecords() {
        val backup = """{"app":"Haluvi Navigation","version":2,"kind":"full","locations":[{"id":"rtl-1","name":"Bus Stop","lat":4.1751,"lon":73.5107,"source":"rtl","categoryId":"bus","islandKey":"K:Malé","islandName":"Kaafu Malé"}]}"""
        val batch = HaluviBackupImporter.parse(backup)
        val place = batch.maps.single().places.single()
        assertEquals("rtl-1", place.id)
        assertEquals("rtl", place.source)
        assertEquals("bus", place.category)
        assertEquals(0, batch.unsupportedPlacesSkipped)
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
