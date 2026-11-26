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

    @Test fun osmPlacesKeepAddressTagsAndRelationPlacesWithCenters() {
        val response = """{"elements":[
            {"type":"node","id":9,"lat":0.0,"lon":0.0005,"tags":{"amenity":"pharmacy","name":"Island Pharmacy","addr:street":"East Road"}},
            {"type":"relation","id":10,"center":{"lat":0.0002,"lon":0.0005},"tags":{"public_transport":"station","name":"Island Ferry Terminal"}}
        ]}"""
        val places = MapData.fromOverpass("Test", response).places
        assertEquals(2, places.size)
        assertTrue(places.single { it.name == "Island Pharmacy" }.roadAddressHints().contains("East Road"))
        assertEquals("public_transport", places.single { it.name == "Island Ferry Terminal" }.category)
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
        assertEquals("First", engine.nextPlace()?.place?.name)
        engine.start(eastRoad, eastRoad.lengthMeters)
        assertEquals("Second", engine.nextPlace()?.place?.name)
    }

    @Test fun previousPlaceWalksBackThroughTheSameRoadPlaces() {
        val first = Place("one", "First", "shop", GeoPoint(0.0, 0.0002), false)
        val second = Place("two", "Second", "shop", GeoPoint(0.0, 0.0008), false)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(first, second)))
        engine.start(eastRoad, 0.0)
        val firstNext = engine.nextPlace()!!
        assertEquals("First", firstNext.place.name)
        engine.moveAlongRoadTo(firstNext.alongDistance)
        engine.rememberPlaceNavigation(firstNext.place)
        val secondNext = engine.nextPlace()!!
        assertEquals("Second", secondNext.place.name)
        engine.moveAlongRoadTo(secondNext.alongDistance)
        engine.rememberPlaceNavigation(secondNext.place)
        assertEquals("First", engine.previousPlace()?.place?.name)
        engine.moveAlongRoadTo(firstNext.alongDistance, keepFacing = true)
        engine.rememberPlaceNavigation(firstNext.place)
        assertNull(engine.previousPlace())
        assertEquals("Second", engine.nextPlace()?.place?.name)
    }

    @Test fun nextAndPreviousCanTraversePlacesAtTheSameRoadPosition() {
        val first = Place("one", "Alpha", "shop", GeoPoint(0.0, 0.0005), false)
        val second = Place("two", "Beta", "shop", GeoPoint(0.0, 0.0005), false)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(second, first)))
        engine.start(eastRoad, 0.0)
        val alpha = engine.nextPlace()!!
        assertEquals("Alpha", alpha.place.name)
        engine.moveAlongRoadTo(alpha.alongDistance)
        engine.rememberPlaceNavigation(alpha.place)
        val beta = engine.nextPlace()!!
        assertEquals("Beta", beta.place.name)
        engine.moveAlongRoadTo(beta.alongDistance)
        engine.rememberPlaceNavigation(beta.place)
        assertEquals("Alpha", engine.previousPlace()?.place?.name)
    }

    @Test fun placeCursorTraversesSeparateSourceRecordsEvenWhenIdsMatch() {
        val alpha = Place("shared-id", "Alpha shop", "shop", GeoPoint(0.0, 0.0005), false, "google")
        val beta = Place("shared-id", "Beta office", "office", GeoPoint(0.0, 0.0005), false, "manual")
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(beta, alpha)))
        engine.start(eastRoad, 0.0)
        val first = engine.nextPlace()!!
        assertEquals("Alpha shop", first.place.name)
        engine.moveAlongRoadTo(first.alongDistance)
        engine.rememberPlaceNavigation(first)
        assertEquals("Beta office", engine.nextPlace()?.place?.name)
    }

    @Test fun nextPlaceDoesNotDropPlacesBeyondTheOldFortyMetreCutoff() {
        val farPlace = Place("far", "Distant mapped place", "shop", GeoPoint(0.00055, 0.0005), false, "google")
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(farPlace)))
        engine.start(eastRoad, 0.0)
        val next = engine.nextPlace()
        assertNotNull(next)
        assertTrue(next!!.roadDistance > 40.0)
    }

    @Test fun movementReportsEveryPlacePassedInOneLongStep() {
        val first = Place("one", "First place", "shop", GeoPoint(0.0, 0.0002), false)
        val second = Place("two", "Second place", "office", GeoPoint(0.0, 0.0008), false)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(eastRoad), listOf(first, second)))
        engine.start(eastRoad, 0.0)
        val end = eastRoad.lengthMeters
        val crossed = engine.placesPassedBetween(0.0, end)
        assertEquals(listOf("First place", "Second place"), crossed.map { it.place.name })
    }

    @Test fun placeAddressRoadHintIsRetainedAndUsedForRoadAssociation() {
        val addressed = Place("addressed", "Addressed place", "shop", GeoPoint(0.0006, 0.0005), false,
            "google", """{"formatted_address":"12 East Road, Malé","route":"East Road"}""")
        val otherRoad = Place("other", "Other road place", "shop", GeoPoint(0.0, 0.0006), false,
            "google", """{"street":"West Road"}""")
        val map = MapData("Test", 0, listOf(eastRoad), listOf(addressed, otherRoad))
        val restored = MapData.fromJson(map.toJson())
        val engine = ExplorerEngine(restored).apply { start(eastRoad, 0.0) }
        assertEquals(listOf("Addressed place"), engine.placesAlongCurrentRoad().map { it.place.name })
        assertTrue(engine.placesAlongCurrentRoad().single().place.roadAddressHints().contains("East Road"))
        assertEquals(2, restored.places.size)
    }

    @Test fun placeNavigationReportsNamedRoadCrossingsAndTheirSide() {
        val main = Road("main", "Main Road", "residential", listOf(1, 2, 3), listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0005), GeoPoint(0.0, 0.001)
        ), nodeIdsVerified = true)
        val side = Road("side", "North Street", "residential", listOf(2, 4), listOf(
            GeoPoint(0.0, 0.0005), GeoPoint(0.0005, 0.0005)
        ), nodeIdsVerified = true)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(main, side), emptyList()))
        engine.start(main, 0.0)
        val crossing = engine.roadCrossingsTo(main.lengthMeters).single()
        assertEquals("North Street", crossing.roadName)
        assertEquals("left", crossing.side)
        assertTrue(crossing.verified)
        assertTrue(engine.roadCrossingsTo(main.lengthMeters, keepFacing = true).isNotEmpty())
    }

    @Test fun sameNamedCrossRoadIsReportedOnBothSidesOfTheRoute() {
        val main = Road("main", "Main Road", "residential", listOf(1, 2, 3), listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0005), GeoPoint(0.0, 0.001)
        ), nodeIdsVerified = true)
        val north = Road("north", "Cross Street", "residential", listOf(2, 4), listOf(
            GeoPoint(0.0, 0.0005), GeoPoint(0.0005, 0.0005)
        ), nodeIdsVerified = true)
        val south = Road("south", "Cross Street", "residential", listOf(5, 2), listOf(
            GeoPoint(-0.0005, 0.0005), GeoPoint(0.0, 0.0005)
        ), nodeIdsVerified = true)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(main, north, south), emptyList()))
        engine.start(main, 0.0)
        val crossings = engine.roadCrossingsTo(main.lengthMeters)
        assertEquals(setOf("left", "right"), crossings.map { it.side }.toSet())
        assertEquals(2, crossings.count { it.roadName == "Cross Street" })
    }

    @Test fun geometricRoadCrossingWithoutSharedNodeIsNotAnnouncedAsAJunction() {
        val main = Road("main", "Main Road", "residential", listOf(1, 2), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)))
        val crossing = Road("crossing", "Crossing Road", "residential", listOf(3, 4), listOf(GeoPoint(-0.001, 0.0005), GeoPoint(0.001, 0.0005)))
        val engine = ExplorerEngine(MapData("Test", 0, listOf(main, crossing), emptyList()))
        engine.start(main, 0.0)
        assertTrue(engine.roadCrossingsTo(main.lengthMeters).isEmpty())
    }

    @Test fun connectedNamedSectionsBecomeOneEndToEndRoadPath() {
        val west = Road("w1", "Long Road", "residential", listOf(101, 102), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)), true)
        val east = Road("w2", "Long Road", "residential", listOf(102, 103), listOf(GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002)), true)
        val path = continuousNamedRoadPaths(listOf(east, west)).single()
        assertTrue(path.continuousPath)
        assertEquals(listOf(101L, 102L, 103L), path.nodeIds)
        assertEquals(3, path.points.size)
        assertEquals(west.lengthMeters + east.lengthMeters, path.lengthMeters, 0.1)
        assertTrue(path.nodeIdsVerified)
        assertEquals(path.id, findRoadPathById(listOf(west, east), path.id)?.id)
    }

    @Test fun nextPlaceSearchesWholeRoadInsteadOfStoppingAt150Metres() {
        val long = Road("long", "Long Road", "residential", listOf(1, 2), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.004)), true)
        val place = Place("far", "Far Shop", "shop", GeoPoint(0.0, 0.003), false)
        val engine = ExplorerEngine(MapData("Test", 0, listOf(long), listOf(place))).apply { start(long, 0.0) }
        assertTrue(engine.nextPlace()!!.distance > 300.0)
        val target = engine.nextPlace()!!.alongDistance
        engine.moveAlongRoadTo(target)
        assertEquals(target, engine.progressMeters, 0.1)
        assertNull(engine.nextPlace())
    }

    @Test fun continuousNamedRoadStopsAtInternalJunctionThenContinuesPastIt() {
        val west = Road("w1", "Long Road", "residential", listOf(101, 102), listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)), true)
        val east = Road("w2", "Long Road", "residential", listOf(102, 103), listOf(GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002)), true)
        val side = Road("side", "Side Street", "residential", listOf(102, 104), listOf(GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001)), true)
        val path = continuousNamedRoadPaths(listOf(west, east)).single()
        val engine = ExplorerEngine(MapData("Test", 0, listOf(path, side), emptyList())).apply { start(path, 0.0) }
        assertEquals(west.lengthMeters, engine.remainingToDecisionPoint(), 0.1)
        engine.move(west.lengthMeters)
        assertTrue(engine.atDecisionPoint())
        assertEquals("Side Street", engine.connections().single().road.name)
        assertTrue(engine.canContinueOnCurrentRoad())
        assertTrue(engine.continueOnCurrentRoad())
        engine.move(10.0)
        assertTrue(engine.progressMeters > west.lengthMeters)
        engine.turnAround()
        engine.move(10.0)
        assertEquals(west.lengthMeters, engine.progressMeters, 0.1)
        val facingBeforePrevious = engine.facing()
        assertTrue(engine.moveBackwardToDecisionPoint() > 100.0)
        assertEquals(path.lengthMeters, engine.progressMeters, 0.1)
        assertEquals(facingBeforePrevious, engine.facing())
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
        val backup = """{"app":"Haluvi Navigation","version":2,"kind":"full","osmRoads":{"K:Malé":[{"name":"Majeedhee Magu","points":[[4.175,73.51],[4.1751,73.511]],"kind":"residential","oneway":false}]},"locations":[{"id":"osm-1","name":"Pharmacy","lat":4.17515,"lon":73.5105,"source":"osm_map","categoryId":"health","islandKey":"K:Malé","islandName":"Kaafu Malé"},{"id":"google-1","name":"Google Place","lat":4.17516,"lon":73.5106,"source":"google","categoryId":"shop","islandKey":"K:Malé","islandName":"Kaafu Malé","formatted_address":"Majeedhee Magu, Malé","address_components":[{"long_name":"Majeedhee Magu","short_name":"Majeedhee Magu","types":["route"]}]}],"roadNames":[{"island":"K:Malé","name":"Recorded Lane","points":[[4.1751,73.511],[4.1752,73.512]],"recorded":true,"width":"narrow"}]}"""
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
        assertTrue(google.roadAddressHints().contains("Majeedhee Magu, Malé"))
        assertTrue(google.roadAddressHints().contains("Majeedhee Magu"))
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

    @Test fun backupImportPreservesEntriesWithoutNamesOrCoordinatesForReview() {
        val backup = """{"app":"Haluvi Navigation","version":2,"kind":"full","locations":[
            {"id":"missing-coordinate","name":"Place without position","source":"google","categoryId":"shop","islandKey":"K:Malé","islandName":"Kaafu Malé","formatted_address":"East Road"},
            {"id":"missing-name","lat":4.175,"lon":73.51,"source":"google","categoryId":"office","islandKey":"K:Malé","islandName":"Kaafu Malé"}
        ]}"""
        val batch = HaluviBackupImporter.parse(backup)
        assertEquals(0, batch.unsupportedPlacesSkipped)
        assertEquals(2, batch.unlocatedPlacesPreserved)
        assertEquals(2, batch.maps.single().unlocatedPlaces.size)
        assertTrue(batch.maps.single().unlocatedPlaces.first { it.id == "missing-coordinate" }.sourceData.contains("formatted_address"))
    }

    @Test fun unlocatedPlaceRecordsSurviveMapUpdatesAndSerialization() {
        val record = UnlocatedPlace("google-no-point", "Saved place", "shop", "google", "No usable coordinates.", """{"address":"East Road"}""")
        val old = MapData("Test", 1, listOf(eastRoad), emptyList(), unlocatedPlaces = listOf(record))
        val updated = mergeDownloadedNetwork(old, MapData("Test", 2, listOf(eastRoad), emptyList()))
        val restored = MapData.fromJson(updated.toJson())
        assertEquals(listOf(record), restored.unlocatedPlaces)
    }
}
