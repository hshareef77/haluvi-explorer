Warning: truncated output (original token count: 16540)
Total output lines: 1038

package com.hussainshareef.haluviexplorer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var store: MapStore
    private lateinit var prefs: android.content.SharedPreferences
    private val osm = OpenStreetMapRepository()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var loadedMap: MapData? = null
    private var loadedFile: File? = null
    private var engine: ExplorerEngine? = null
    private var statusView: TextView? = null
    private var stepLabel: TextView? = null
    private var stepSeek: SeekBar? = null
    private var autoWalking = false
    private var startPosition: GeoPoint? = null
    private var destination: Place? = null
    private var returnJourney = false
    private var lastScreenBack: (() -> Unit)? = null
    private var lastBackLabel = "Back to Main Menu"
    private val importRequestCode = 7412

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MapStore(this)
        prefs = getSharedPreferences("haluvi_explorer", MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        showHome()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault()
    }

    override fun onBackPressed() {
        if (autoWalking) { stopAutoWalk("Automatic walking stopped."); return }
        (lastScreenBack ?: ::showHome).invoke()
    }

    override fun onDestroy() {
        stopAutoWalk(null)
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    @Deprecated("Use the system document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importRequestCode && resultCode == RESULT_OK) data?.data?.let(::importMapBackup)
    }

    private fun showHome() {
        page("Haluvi Explorer", null, null) {
            addText("Explore islands, roads and mapped places by virtual walking.")
            addButton("Start Exploration") { chooseMapForExploration() }
            if (prefs.contains("session_map")) addButton("Resume Exploration") { resumeSession() }
            if (prefs.contains("session_map")) addButton("Manage Saved Exploration") { manageSavedExploration() }
            val savedBookmarks = runCatching { JSONArray(prefs.getString("bookmarks", "[]")) }.getOrDefault(JSONArray())
            if (savedBookmarks.length() > 0) addButton("Saved Bookmarks") { showBookmarks() }
            addButton("Explore Roads") { chooseMapForExploration() }
            addButton("Explore Places") { chooseMapForPlaces() }
            addButton("Learning and Challenges") { showLearning() }
            addButton("Download and Manage Maps") { showMapManager() }
            addButton("Settings") { showSettings() }
            addButton("About") { showAbout() }
            addText("Developer: Haluvi Services", 14f)
        }
    }

    private fun page(title: String, backLabel: String?, back: (() -> Unit)?, body: PageBuilder.() -> Unit) {
        lastScreenBack = back
        lastBackLabel = backLabel ?: "Back to Main Menu"
        val builder = PageBuilder(title)
        builder.body()
        setContentView(builder.scroll)
        if (back != null) builder.heading.requestFocus()
    }

    private inner class PageBuilder(title: String) {
        val scroll = android.widget.ScrollView(this@MainActivity)
        val column = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(28))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val heading = TextView(this@MainActivity).apply {
            text = title; textSize = 27f; setTextColor(Color.rgb(18, 52, 85));
            setPadding(0, dp(8), 0, dp(18)); isFocusable = true
            if (android.os.Build.VERSION.SDK_INT >= 28) setAccessibilityHeading(true)
        }
        init { scroll.addView(column); column.addView(heading) }
        fun addText(text: String, size: Float = 17f, live: Boolean = false): TextView = TextView(this@MainActivity).apply {
            this.text = text; textSize = size; setTextColor(Color.rgb(28, 33, 38));
            setPadding(0, dp(8), 0, dp(10)); if (live) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            column.addView(this)
        }
        fun addButton(label: String, enabled: Boolean = true, action: () -> Unit): Button = Button(this@MainActivity).apply {
            text = label; contentDescription = label; isAllCaps = false; isEnabled = enabled; minHeight = dp(58)
            setPadding(dp(14), dp(10), dp(14), dp(10)); setOnClickListener { action() }
            column.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        fun addEdit(hint: String, initial: String = ""): EditText = EditText(this@MainActivity).apply {
            setSingleLine(true); this.hint = hint; setText(initial); contentDescription = hint
            textSize = 18f; minHeight = dp(58); column.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun addSpinner(label: String, options: List<String>, selected: Int = 0, changed: (Int) -> Unit): Spinner {
            addText(label)
            return Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, options).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(selected); contentDescription = label
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { changed(position) }
                }
                column.addView(this, LinearLayout.LayoutParams(-1, dp(56)))
            }
        }
        fun addStatus(text: String): TextView = addText(text, 18f, live = true).also { statusView = it }
    }

    private fun showMapManager() {
        page("Download and Manage Maps", "Back to Main Menu", ::showHome) {
            addText("Map downloads need an internet connection. Once saved, the road network and places work offline.")
            addText("OpenStreetMap data is shown with source attribution. Map coverage varies by island.", 15f)
            addButton("Choose Atoll and Island") { chooseAtoll() }
            addButton("Search Island by Name") { searchIsland() }
            addButton("Import Haluvi Navigation Backup or Shared Map") { chooseBackupFile() }
            addText("Backup import keeps compatible OpenStreetMap roads, user roads and places. Google Places entries remain in your original backup, but are not copied into this offline OpenStreetMap map because Google restricts reuse of its place content with non-Google maps.", 15f)
            val files = store.all()
            if (files.isEmpty()) addText("No island maps downloaded yet.")
            files.forEach { file ->
                val map = runCatching { store.load(file) }.getOrNull() ?: return@forEach
                addText("${map.islandName}. ${map.roads.size} roads, ${map.places.size} mapped places, ${file.length() / 1024} kilobytes. Saved ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(map.downloadedAt))}.")
                addButton("Explore ${map.islandName}") { loadMap(file); chooseRoad() }
                addButton("Explore places on ${map.islandName}") { loadMap(file); showPlaceList() }
                addButton("Update ${map.islandName}") { searchIsland(map.islandName.substringBefore(",")) }
                addButton("Delete map for ${map.islandName}") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete island map?")
                        .setMessage("Delete the saved offline map for ${map.islandName} from this phone?")
                        .setPositiveButton("Delete") { _, _ ->
                            store.delete(file)
                            if (prefs.getString("session_map", null) == file.name) prefs.edit().remove("session_map").apply()
                            showMapManager()
                        }.setNegativeButton("Cancel", null).show()
                }
            }
            addButton("OpenStreetMap attribution") { showAttribution() }
        }
    }

    private fun chooseBackupFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        runCatching { startActivityForResult(intent, importRequestCode) }
            .onFailure { Toast.makeText(this, "The file picker could not be opened.", Toast.LENGTH_LONG).show() }
    }

    private fun importMapBackup(uri: Uri) {
        page("Import Map Data", "Back to Map Management", ::showMapManager) {
            val status = addStatus("Reading the selected backup. This may take a short time.")
            addButton("Back to Map Management") { showMapManager() }
            Thread {
                val result = runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("The selected file could not be read.")
                    store.importNavigationBackup(text)
                }
                mainHandler.post {
                    result.onSuccess { report ->
                        status.text = "Import complete. ${report.mapsProcessed} island maps checked, ${report.roadsAdded} roads and ${report.placesAdded} places added. " +
                            (if (report.googlePlacesSkipped > 0) "${report.googlePlacesSkipped} Google places skipped. " else "") +
                            (if (report.unsupportedPlacesSkipped > 0) "${report.unsupportedPlacesSkipped} unsupported place entries skipped." else "")
                        announce(status.text.toString(), status)
                    }.onFailure {
                        status.text = "Import failed. Choose a Haluvi Navigation backup or shared map JSON file. ${it.message ?: ""}"
                        announce(status.text.toString(), status)
                    }
                }
            }.start()
        }
    }

    private fun showMapOverview() {
        val map = loadedMap ?: return showMapManager()
        page("${map.islandName} Map Overview", "Back to Map Management", ::showMapManager) {
            addText("${map.islandName} map is saved on this phone and available offline.")
            addText("${map.roads.size} mapped roads, ${map.places.size} mapped places, and ${map.roads.sumOf { it.lengthMeters }.roundToInt()} metres of road geometry.")
            addText("This map comes from OpenStreetMap. Coverage and positional accuracy depend on available community mapping.")
            if (!map.connectivityVerified) addText("Road connections in this imported backup are inferred from identical coordinate points because the backup omits OpenStreetMap node IDs. Treat those junctions as estimates.")
            addButton("Explore Roads") { chooseRoad() }
            addButton("Explore Places") { showPlaceList() }
            addButton("Back to Main Menu") { showHome() }
        }
    }

    private fun searchIsland(initial: String = "Malé", selectedIsland: IslandEntry? = null, selectedAtoll: AtollEntry? = null) {
        page("Find an Island", "Back to Map Management", ::showMapManager) {
            addText(if (selectedIsland == null) "Enter an island or settlement in the Maldives. Search is limited to mapped island and settlement areas." else
                "Looking up ${selectedIsland.name} in ${selectedAtoll?.fullname}. Results are constrained around the island's catalog coordinates; a building or shop will not be offered as an island.")
            val query = addEdit("Island name", initial)
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            val status = addStatus("Ready to search.")
            val downloadProgress = ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                visibility = View.GONE
                contentDescription = "Map download in progress"
            }
            column.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(8)))
            val runSearch: () -> Unit = {
                val name = query.text.toString().trim()
                if (name.isBlank()) {
                    status.text = "Enter an island name."
                } else {
                    status.text = "Searching for $name."
                    val handle: (Result<List<IslandArea>>) -> Unit = { result ->
                        results.removeAllViews()
                        result.onSuccess { areas ->
                            status.text = if (areas.any { it.approximate }) "No island boundary was found. Showing an approximate area centred on the selected island. Review its label before downloading." else "${areas.size} island or settlement results found. Select the island area to download."
                            areas.forEach { area ->
                                val button = Button(this@MainActivity).apply {
                                    text = area.displayName
                                    contentDescription = "Download map for ${area.displayName}${if (area.approximate) ". Approximate area around the catalog island centre" else ""}"
                                    isAllCaps = false; minHeight = dp(58)
                                    setOnClickListener { downloadArea(area, status, downloadProgress) }
                                }
                                results.addView(button, LinearLayout.LayoutParams(-1, -2))
                            }
                        }.onFailure { status.text = "Search failed: ${it.message ?: "network error"}. Check your connection and try again." }
                    }
                    if (selectedIsland != null && selectedAtoll != null) osm.searchIsland(selectedIsland, selectedAtoll, handle)
                    else osm.searchIsland(name, handle)
                }
            }
            addButton("Search Island", action = runSearch)
            if (selectedIsland != null && selectedAtoll != null) runSearch()
        }
    }

    private fun chooseAtoll() {
        val atolls = runCatching { IslandCatalog.load(this) }.getOrElse {
            Toast.makeText(this, "The Maldives island list could not be loaded.", Toast.LENGTH_LONG).show()
            return
        }
        page("Choose an Atoll", "Back to Map Management", ::showMapManager) {
            addText("Choose an atoll, then choose an island from that atoll.")
            atolls.forEach { atoll ->
                addButton("${atoll.fullname} atoll. ${atoll.islands.size} islands") { chooseIsland(atoll) }
            }
        }
    }

    private fun chooseIsland(atoll: AtollEntry) {
        page("Choose an Island in ${atoll.fullname}", "Back to Atolls", ::chooseAtoll) {
            addText("Search within ${atoll.fullname} or choose an island.")
            val search = addEdit("Search islands in ${atoll.fullname}")
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            fun populate() {
                results.removeAllViews()
                val matches = atoll.islands.filter { it.name.contains(search.text.toString(), true) }
                matches.forEach { island ->
                    val label = "${island.name}, ${atoll.fullname}"
                    val button = Button(this@MainActivity).apply {
                        text = label; contentDescription = label; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener { searchIsland("${island.name}, ${atoll.fullname}", island, atoll) }
                    }
                    results.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                statusView?.text = "${matches.size} islands in ${atoll.fullname}."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addStatus("${atoll.islands.size} islands in ${atoll.fullname}.")
            populate()
        }
    }

    private fun downloadArea(area: IslandArea, status: TextView, progress: ProgressBar) {
        progress.visibility = View.VISIBLE
        status.text = "Connecting to the map service for ${area.shortName}. Download is starting."
        announce(status.text.toString(), status)
        osm.download(area, onProgress = { message ->
            status.text = message
            if (message.contains("processing", true)) announce(message, status)
        }) { result ->
            progress.visibility = View.GONE
            result.onSuccess { map ->
                val named = map.copy(islandName = area.displayName)
                store.save(named)
                loadMap(store.all().firstOrNull { it.nameWithoutExtension == named.saveName() } ?: store.all().last())
                announce("Download complete. The ${area.shortName} map is saved on this phone for offline use. ${named.roads.size} road sections and ${named.places.size} places saved.", status)
                showMapOverview()
            }.onFailure {
                status.text = "Download failed: ${it.message ?: "network error"}. No new map was saved. You can retry when the connection is available."
                announce(status.text.toString(), status)
            }
        }
    }

    private fun chooseMapForExploration() {
        chooseAtollForSavedMaps(forPlaces = false)
    }

    private fun chooseMapForPlaces() {
        chooseAtollForSavedMaps(forPlaces = true)
    }

    private fun chooseAtollForSavedMaps(forPlaces: Boolean) {
        val atolls = runCatching { IslandCatalog.load(this) }.getOrElse {
            showMapManager()
            return
        }
        page("Choose an Atoll", "Back to Main Menu", ::showHome) {
            addText("Choose an atoll, then an island. Downloaded maps are identified in the island list.")
            atolls.forEach { atoll -> addButton("${atoll.fullname} atoll") { chooseSavedIsland(atoll, forPlaces) } }
        }
    }

    private fun chooseSavedIsland(atoll: AtollEntry, forPlaces: Boolean) {
        page("Choose an Island in ${atoll.fullname}", "Back to Atolls", { chooseAtollForSavedMaps(forPlaces) }) {
            val search = addEdit("Search islands in ${atoll.fullname}")
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            fun findSavedFile(island: IslandEntry): File? {
                val islandKey = "${atoll.code}:${island.name}"
                return store.all().firstOrNull { file ->
                    val map = runCatching { store.load(file) }.getOrNull() ?: return@firstOrNull false
                    normalizedIsland(map.sourceIslandKey) == normalizedIsland(islandKey) ||
                        normalizedIsland(map.islandName).let { it.startsWith(normalizedIsland(island.name)) || it.endsWith(normalizedIsland(island.name)) } ||
                        file.nameWithoutExtension.equals(island.name.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-'), true)
                }
            }
            fun populate() {
                results.removeAllViews()
                val matches = atoll.islands.filter { it.name.contains(search.text.toString(), true) }
                matches.forEach { island ->
                    val file = findSavedFile(island)
                    val label = "${island.name}${if (file == null) ". Map not downloaded" else ". Map downloaded"}"
                    val button = Button(this@MainActivity).apply {
                        text = label; contentDescription = "$label, ${atoll.fullname}"; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener {
                            if (file == null) searchIsland("${island.name}, ${atoll.fullname}")
                            else {
                                loadMap(file)
                                if (forPlaces) showPlaceList() else chooseRoad()
                            }
                        }
                    }
                    results.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                statusView?.text = "${matches.size} islands in ${atoll.fullname}."
            }
            addStatus("${atoll.islands.size} islands in ${atoll.fullname}.")
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            populate()
        }
    }

    private fun normalizedIsland(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

    private fun loadMap(file: File) {
        loadedMap = store.load(file); loadedFile = file
    }

    private fun chooseRoad(filterText: String = "") {
        val map = loadedMap ?: return showMapManager()
        page("Explore Roads in ${map.islandName}", "Back to Island List", ::chooseMapForExploration) {
            addText("Search by road name. Each named road appears once; its mapped sections are grouped together.")
            val search = addEdit("Search roads", filterText)
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            fun populate() {
                results.removeAllViews()
                val matches = groupRoadsByName(map.roads, search.text.toString()).take(80)
                matches.forEach { (name, sections) ->
                    val total = sections.sumOf { it.lengthMeters }.roundToInt()
                    val label = "$name, $total metres across ${sections.size} sections"
                    val b = Button(this@MainActivity).apply { te…4540 tokens truncated…ius = prefs.getInt("radius", 100).toDouble()
        val places = e.nearbyPlaces(radius)
        val options = e.connections()
        page("Look Around", "Back to Exploration", ::showExplore) {
            addText("Surroundings within ${radius.roundToInt()} metres. Left and right are relative to your current facing direction.")
            addText(if (places.isEmpty()) "No mapped places within this radius." else "Mapped places:")
            places.forEach { item ->
                addButton("${item.place.name}, ${item.place.category}, ${item.side}, ${item.distance.roundToInt()} metres") {
                    destination = item.place
                    page("Nearby Place", "Back to Look Around", ::showLookAround) {
                        addText("${item.place.name}, ${item.place.category}, ${item.side}, ${item.distance.roundToInt()} metres away.")
                        addText(if (item.place.entranceVerified) "An entrance is mapped here." else "Entrance location is not verified.")
                        addButton("Move to this place along the road") {
                            val delta = item.alongDistance - (engine?.progressMeters ?: 0.0)
                            if (delta * (engine?.travelSign ?: 1) >= 0) engine?.move(abs(delta)) else engine?.moveBackward(abs(delta))
                            engine?.markVisited(item.place); showExplore()
                        }
                        addButton("Set as Find a Destination goal") { destination = item.place; announce("Destination set to ${item.place.name}.", null); showExplore() }
                    }
                }
            }
            addText(if (options.isEmpty()) "No connected road listed at the current road endpoint." else "Connected roads at this road endpoint:")
            options.forEach { addText("${it.road.name}, ${compassName(it.bearingOut)} from this junction.") }
            addText("The map does not confirm business entrances or unmapped features.")
        }
    }

    private fun checkNearbyPlaces(status: TextView?) {
        if (!prefs.getBoolean("announce_places", true)) return
        val e = engine ?: return
        val passed = e.nearbyPlaces(4.0).firstOrNull { it.place.id !in passedPlaceIds }
        if (passed != null) {
            passedPlaceIds += passed.place.id
            e.markVisited(passed.place)
            announce("Passing ${passed.place.name} on your ${passed.side}.", status)
        }
        val goal = destination
        if (goal != null) {
            val distance = e.position()?.let { distanceMeters(it, goal.point) } ?: Double.MAX_VALUE
            if (distance <= 20.0) { destination = null; announce("Destination reached: ${goal.name}.", status); history("Destination reached: ${goal.name}") }
        }
    }

    private val passedPlaceIds = linkedSetOf<String>()

    private fun sideAnnouncement(place: NearbyPlace): String = "It is on your ${place.side}. The map position does not confirm an entrance on this road."

    private fun announce(text: String, view: TextView?) {
        view?.text = text
        view?.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        if (prefs.getBoolean("speech_enabled", false)) {
            val rate = prefs.getInt("speech_rate", 100) / 100f
            val pitch = prefs.getInt("speech_pitch", 100) / 100f
            tts?.setSpeechRate(rate.coerceIn(0.6f, 1.8f)); tts?.setPitch(pitch.coerceIn(0.7f, 1.5f))
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "explorer-${System.currentTimeMillis()}")
        }
    }

    private fun changed(text: String, view: TextView?) {
        persistSession(); history(text); announce(text, view)
    }

    private fun startAutoWalk(status: TextView?) {
        autoWalking = true
        announce("Automatic walking started. It will pause at a junction or road endpoint.", status)
        val task = object : Runnable {
            override fun run() {
                if (!autoWalking) return
                val e = engine ?: return stopAutoWalk("Automatic walking stopped.")
                val chunk = (prefs.getInt("walk_speed", 14) / 10.0).coerceIn(0.2, 0.8)
                val moved = e.move(chunk)
                checkNearbyPlaces(status)
                if (e.atRoadEnd() || moved < chunk || moved == 0.0) {
                    val connections = e.connections()
                    if (!prefs.getBoolean("stop_junctions", true) && connections.size == 1 && e.atRoadEnd()) {
                        e.takeConnection(connections.first())
                        status?.text = "Continuing onto ${e.road?.name}."
                        persistSession()
                        mainHandler.postDelayed(this, 800)
                        return
                    }
                    autoWalking = false
                    val junctionMessage = if (loadedMap?.connectivityVerified == true) "Automatic walking paused at a junction. ${connections.joinToString { it.road.name }} connect here."
                    else "Automatic walking paused at a possible junction. ${connections.joinToString { it.road.name }} may connect here based on coordinates."
                    announce(if (connections.isEmpty()) "Automatic walking paused at the road endpoint. No road connection is mapped." else junctionMessage, status)
                    persistSession(); return
                }
                status?.text = "Walking on ${e.road?.name}. ${e.remainingToRoadEnd().roundToInt()} metres to the road endpoint."
                mainHandler.postDelayed(this, 800)
            }
        }
        mainHandler.postDelayed(task, 800)
    }

    private fun stopAutoWalk(message: String?) {
        autoWalking = false
        mainHandler.removeCallbacksAndMessages(null)
        if (message != null) announce(message, statusView)
        persistSession()
    }

    private fun persistSession() {
        val e = engine ?: return
        val mapFile = loadedFile ?: return
        val road = e.road ?: return
        prefs.edit().putString("session_map", mapFile.name).putString("session_name", prefs.getString("session_name", "My exploration")).putString("session_road", road.id)
            .putFloat("session_progress", e.progressMeters.toFloat()).putInt("session_sign", e.travelSign)
            .putFloat("session_facing", e.facingBearing.toFloat()).putFloat("session_distance", e.travelledMeters.toFloat())
            .putInt("session_junctions", e.visitedJunctions).apply()
    }

    private fun resumeSession() {
        val filename = prefs.getString("session_map", null) ?: return showHome()
        val file = store.all().firstOrNull { it.name == filename } ?: return showHome()
        loadMap(file)
        val map = loadedMap ?: return showHome()
        val road = map.roads.firstOrNull { it.id == prefs.getString("session_road", null) } ?: return chooseRoad()
        engine = ExplorerEngine(map).apply {
            restore(road, prefs.getFloat("session_progress", 0f).toDouble(), prefs.getInt("session_sign", 1),
                prefs.getFloat("session_facing", 0f).toDouble(), prefs.getFloat("session_distance", 0f).toDouble(),
                prefs.getInt("session_junctions", 0))
        }
        startPosition = engine?.position(); showExplore()
    }

    private fun showHistory() {
        val items = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        page("Exploration History", "Back to Exploration", ::showExplore) {
            addText("Recent virtual movements, turns and destinations are stored on this phone.")
            if (items.length() == 0) addText("No history yet.")
            for (i in items.length() - 1 downTo 0) addText(items.optString(i))
            addButton("Clear Exploration History") { prefs.edit().remove("history").apply(); showHistory() }
        }
    }

    private fun manageSavedExploration() {
        val mapName = prefs.getString("session_map", null)
        page("Manage Saved Exploration", "Back to Main Menu", ::showHome) {
            if (mapName == null) addText("There is no saved exploration.") else {
                addText("Saved session: ${prefs.getString("session_name", "My exploration")}. Island map file: $mapName.")
                addButton("Rename Saved Exploration") {
                    val input = EditText(this@MainActivity).apply { setSingleLine(true); setText(prefs.getString("session_name", "My exploration")); contentDescription = "Saved exploration name" }
                    AlertDialog.Builder(this@MainActivity).setTitle("Rename Saved Exploration").setView(input)
                        .setPositiveButton("Save") { _, _ -> prefs.edit().putString("session_name", input.text.toString().trim().ifBlank { "My exploration" }).apply(); manageSavedExploration() }
                        .setNegativeButton("Cancel", null).show()
                }
                addButton("Resume Saved Exploration") { resumeSession() }
                addButton("Delete Saved Exploration") {
                    prefs.edit().remove("session_map").remove("session_road").remove("session_progress").remove("session_sign").remove("session_facing").remove("session_distance").remove("session_junctions").remove("session_name").apply()
                    showHome()
                }
            }
        }
    }

    private fun showBookmarks() {
        val bookmarks = runCatching { JSONArray(prefs.getString("bookmarks", "[]")) }.getOrDefault(JSONArray())
        page("Saved Bookmarks", "Back to Main Menu", ::showHome) {
            if (bookmarks.length() == 0) addText("No saved bookmarks.")
            for (i in 0 until bookmarks.length()) {
                val item = bookmarks.optJSONObject(i) ?: continue
                val id = item.optString("id")
                addText(item.optString("label"))
                addButton("Start from ${item.optString("label")}") {
                    val parts = id.split(":", limit = 3)
                    val fileName = parts.getOrNull(1)
                    val file = store.all().firstOrNull { it.name == fileName }
                    if (file == null) { Toast.makeText(this@MainActivity, "The map for this bookmark is no longer downloaded.", Toast.LENGTH_LONG).show() }
                    else {
                        loadMap(file)
                        val map = loadedMap
                        val e = map?.let { ExplorerEngine(it) }
                        val point = when {
                            parts[0] == "position" -> parts.getOrNull(2)?.split(",")?.takeIf { it.size == 2 }?.let { xy -> GeoPoint(xy[0].toDoubleOrNull() ?: 0.0, xy[1].toDoubleOrNull() ?: 0.0) }
                            parts[0] == "place" -> map?.places?.firstOrNull { it.id == parts.getOrNull(2) }?.point
                            else -> null
                        }
                        if (point != null && e != null && e.startNear(point) != null) {
                            engine = e; startPosition = e.position(); destination = null; returnJourney = false; persistSession(); showExplore()
                        } else Toast.makeText(this@MainActivity, "Could not find a road near this bookmark.", Toast.LENGTH_LONG).show()
                    }
                }
                addButton("Rename ${item.optString("label")}") {
                    val input = EditText(this@MainActivity).apply { setSingleLine(true); setText(item.optString("label")); contentDescription = "Bookmark name" }
                    AlertDialog.Builder(this@MainActivity).setTitle("Rename Bookmark").setView(input)
                        .setPositiveButton("Save") { _, _ -> item.put("label", input.text.toString().trim()); prefs.edit().putString("bookmarks", bookmarks.toString()).apply(); showBookmarks() }
                        .setNegativeButton("Cancel", null).show()
                }
                addButton("Delete ${item.optString("label")}") {
                    val remaining = JSONArray()
                    for (j in 0 until bookmarks.length()) if (j != i) remaining.put(bookmarks.optJSONObject(j))
                    prefs.edit().putString("bookmarks", remaining.toString()).apply(); showBookmarks()
                }
            }
        }
    }

    private fun history(text: String) {
        val old = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (i in (old.length() - 99).coerceAtLeast(0) until old.length()) next.put(old.optString(i))
        next.put("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date())}: $text")
        prefs.edit().putString("history", next.toString()).apply()
    }

    private fun addBookmark(id: String, label: String) {
        val bookmarks = runCatching { JSONArray(prefs.getString("bookmarks", "[]")) }.getOrDefault(JSONArray())
        if ((0 until bookmarks.length()).any { bookmarks.optJSONObject(it)?.optString("id") == id }) { Toast.makeText(this, "Already bookmarked.", Toast.LENGTH_SHORT).show(); return }
        bookmarks.put(JSONObject().put("id", id).put("label", label))
        prefs.edit().putString("bookmarks", bookmarks.toString()).apply()
        announce("Bookmarked: $label", statusView)
    }

    private fun showLearning() {
        page("Learning and Challenges", "Back to Main Menu", ::showHome) {
            addText("Challenges use downloaded map information. Hints are optional. The application is for virtual learning, not real-world pedestrian safety.")
            addButton("Free Exploration") { chooseMapForExploration() }
            addButton("Find a Destination") { chooseMapForPlaces() }
            addButton("Road Memory Challenge") { startRoadMemoryChallenge() }
            addButton("Return Journey") { startReturnJourney() }
            addButton("Guided Learning") { chooseMapForExploration() }
            addButton("Route Practice") { chooseMapForPlaces() }
        }
    }

    private fun startReturnJourney() {
        if (engine == null) return chooseMapForExploration()
        returnJourney = true
        startPosition = engine?.position()
        announce("Return Journey started. Explore freely, then use Check Return Distance when ready.", statusView)
        page("Return Journey", "Back to Learning", ::showLearning) {
            addText("Explore without automatic directions, then return to the starting position.")
            addButton("Continue Exploring") { showExplore() }
            addButton("Check Return Distance") {
                val here = engine?.position(); val start = startPosition
                announce(if (here == null || start == null) "Starting position not available." else "You are ${distanceMeters(here, start).roundToInt()} metres from your return point.", null)
            }
        }
    }

    private fun startRoadMemoryChallenge() {
        val map = loadedMap
        if (map == null || engine == null) return chooseMapForExploration()
        val current = engine?.road ?: return chooseRoad()
        val candidates = connectionsAtEitherEnd(current, map).distinct()
        if (candidates.isEmpty()) {
            page("Road Memory Challenge", "Back to Learning", ::showLearning) { addText("No mapped connected road is available at either end of this road. Explore another road or download a more complete map.") }
            return
        }
        page("Road Memory Challenge", "Back to Learning", ::showLearning) {
            addText("Question based on downloaded map data: which road is connected to ${current.name} at one of its endpoints?")
            val correct = candidates.random()
            val choices = (listOf(correct) + map.roads.map { it.name }.distinct().filter { it !in candidates && it != current.name }.shuffled().take(3)).shuffled()
            choices.forEach { name -> addButton(name) { announce(if (name == correct) "Correct. $name is mapped as a connecting road." else "That road is not mapped as connected at this endpoint. Try another answer.", null); history("Road Memory Challenge answer: $name") } }
            addText("Answer choices come from this island's downloaded road data. Connections may be incomplete if the source map is incomplete.")
        }
    }

    private fun showSettings() {
        page("Settings", "Back to Main Menu", ::showHome) {
            addText("Adjust exploration controls and speech.")
            val movement = addSpinner("Movement mode", listOf("Step by step", "Place to place", "Junction to junction", "Automatic walking"), prefs.getInt("movement_mode", 0)) { prefs.edit().putInt("movement_mode", it).apply() }
            movement.contentDescription = "Default movement mode"
            val distanceLabel = addText("Step distance: ${stepMetres()} metres.")
            val steps = SeekBar(this@MainActivity).apply { max = 19; progress = prefs.getInt("step_distance", 5) - 1; contentDescription = "Step distance in metres" }
            column.addView(steps, LinearLayout.LayoutParams(-1, dp(58)))
            steps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { val v = p + 1; distanceLabel.text = "Step distance: $v metres."; if (user) prefs.edit().putInt("step_distance", v).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            addSwitch("Announce mapped places as I pass them", "announce_places", true)
            addSwitch("Stop automatic walking at every junction", "stop_junctions", true)
            addSwitch("Use app speech in addition to TalkBack", "speech_enabled", false)
            val speedLabel = addText("Automatic walking speed: ${prefs.getInt("walk_speed", 14) / 10.0} metres per second.")
            val speed = SeekBar(this@MainActivity).apply { max = 38; progress = (prefs.getInt("walk_speed", 14) - 2).coerceIn(0, 38); contentDescription = "Automatic walking speed" }
            column.addView(speed, LinearLayout.LayoutParams(-1, dp(58)))
            speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { val value = p + 2; speedLabel.text = "Automatic walking speed: ${value / 10.0} metres per second."; if (user) prefs.edit().putInt("walk_speed", value).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            val speechRateLabel = addText("Speech rate: ${prefs.getInt("speech_rate", 100)} percent.")
            val speechRate = SeekBar(this@MainActivity).apply { max = 120; progress = (prefs.getInt("speech_rate", 100) - 60).coerceIn(0, 120); contentDescription = "Speech rate" }
            column.addView(speechRate, LinearLayout.LayoutParams(-1, dp(58)))
            speechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { val value = p + 60; speechRateLabel.text = "Speech rate: $value percent."; if (user) prefs.edit().putInt("speech_rate", value).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            val pitchLabel = addText("Speech pitch: ${prefs.getInt("speech_pitch", 100)} percent.")
            val pitch = SeekBar(this@MainActivity).apply { max = 80; progress = (prefs.getInt("speech_pitch", 100) - 70).coerceIn(0, 80); contentDescription = "Speech pitch" }
            column.addView(pitch, LinearLayout.LayoutParams(-1, dp(58)))
            pitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { val value = p + 70; pitchLabel.text = "Speech pitch: $value percent."; if (user) prefs.edit().putInt("speech_pitch", value).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            val radiusLabel = addText("Look Around radius: ${prefs.getInt("radius", 100)} metres.")
            val radius = SeekBar(this@MainActivity).apply { max = 19; progress = (prefs.getInt("radius", 100) / 25 - 1).coerceIn(0, 19); contentDescription = "Look Around search radius in metres" }
            column.addView(radius, LinearLayout.LayoutParams(-1, dp(58)))
            radius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { val value = (p + 1) * 25; radiusLabel.text = "Look Around radius: $value metres."; if (user) prefs.edit().putInt("radius", value).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            addButton("Reset Settings") {
                listOf("movement_mode", "step_distance", "announce_places", "stop_junctions", "speech_enabled", "walk_speed", "radius", "speech_rate", "speech_pitch").forEach { prefs.edit().remove(it).apply() }
                showSettings()
            }
        }
    }

    private fun PageBuilder.addSwitch(label: String, key: String, default: Boolean) {
        val toggle = Switch(this@MainActivity).apply {
            text = label; contentDescription = label; isChecked = prefs.getBoolean(key, default); textSize = 17f; minHeight = dp(58)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        column.addView(toggle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun showAbout() {
        page("About Haluvi Explorer", "Back to Main Menu", ::showHome) {
            addText("Haluvi Explorer is an experimental Android application for blind and visually impaired people to learn the layout of mapped islands through virtual road exploration.")
            addText("Virtual movement is based on downloaded road geometry. It is not live navigation and does not provide real-world pedestrian safety guidance.")
            addText("Place coverage, road names and connection quality depend on OpenStreetMap data and can be incomplete or outdated.")
            addText("Version 0.1.0, build 1")
            addButton("OpenStreetMap attribution and licence") { showAttribution() }
            addText("Developer: Haluvi Services")
        }
    }

    private fun showAttribution() {
        page("Map Data and Licence", "Back to Main Menu", ::showHome) {
            addText("Map and place data: © OpenStreetMap contributors. OpenStreetMap data is available under the Open Database License (ODbL).")
            addText("Source and licence: openstreetmap.org/copyright")
            addText("Data is requested only when you search for and download an island. Downloaded map data is stored locally for offline use.")
            addText("Place coordinates do not guarantee building entrances. Road connections are inferred from shared road-node identifiers in the downloaded data.")
        }
    }

    private fun stepMetres(): Double = prefs.getInt("step_distance", 5).toDouble().coerceIn(1.0, 20.0)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun pointAt(road: Road, metres: Double): GeoPoint {
        var rest = metres.coerceIn(0.0, road.lengthMeters)
        for (i in 0 until road.points.lastIndex) {
            val a = road.points[i]; val b = road.points[i + 1]; val length = distanceMeters(a, b)
            if (length > 0 && rest <= length) { val f = rest / length; return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f) }
            rest -= length
        }
        return road.points.last()
    }
}

