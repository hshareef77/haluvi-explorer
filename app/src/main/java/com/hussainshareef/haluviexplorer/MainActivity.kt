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
    private val osm by lazy { OpenStreetMapRepository(applicationContext) }
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
    private var screenGeneration = 0
    private var activeDownload: MapDownloadHandle? = null
    private var activeDownloadDialog: AlertDialog? = null

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
        activeDownload?.cancel()
        activeDownloadDialog?.dismiss()
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
            addButton("Explore Places") { chooseMapForPlaces() }
            addButton("Learning and Challenges") { showLearning() }
            addButton("Download and Manage Maps") { showMapManager() }
            addButton("Settings") { showSettings() }
            addButton("About") { showAbout() }
            addText("Developer: Haluvi Services", 14f)
        }
    }

    private fun page(title: String, backLabel: String?, back: (() -> Unit)?, body: PageBuilder.() -> Unit) {
        screenGeneration++
        lastScreenBack = back
        lastBackLabel = backLabel ?: "Back to Main Menu"
        val builder = PageBuilder(title)
        builder.body()
        setContentView(builder.scroll)
        if (back != null) builder.heading.requestFocus()
    }

    private inner class PageBuilder(title: String) {
        val scroll = android.widget.ScrollView(this@MainActivity).apply {
            isFillViewport = true
            isSmoothScrollingEnabled = false
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (android.os.Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = title
        }
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
            val generation = screenGeneration
            addText("Map downloads need an internet connection. Once saved, the road network and places work offline.")
            addText("For confirmed junctions, update a saved island map while online. Explorer requests OSM way-node IDs and keeps your saved places. Imported backups and bundled atoll snapshots do not contain those IDs, so their links remain estimates.", 15f)
            addText("OpenStreetMap data is shown with source attribution. Map coverage varies by island.", 15f)
            addButton("Choose Atoll and Island") { chooseAtoll() }
            addButton("Search Island by Name") { searchIsland() }
            addButton("Import Haluvi Navigation Backup or Shared Map") { chooseBackupFile() }
            addText("Backup import keeps valid saved roads and places on this phone, including Google Places entries. Place source labels are preserved.", 15f)
            addText("Reading saved map summaries…", live = true)
            addButton("OpenStreetMap attribution") { showAttribution() }
            Thread {
                val summaries = store.summaries()
                mainHandler.post { if (screenGeneration == generation) renderMapManager(summaries) }
            }.start()
        }
    }

    private fun renderMapManager(summaries: List<StoredMapSummary>) {
        page("Download and Manage Maps", "Back to Main Menu", ::showHome) {
            addText("Map downloads need an internet connection. Once saved, the road network and places work offline.")
            addText("For confirmed junctions, update a saved island map while online. Explorer requests OSM way-node IDs and keeps your saved places. Imported backups and bundled atoll snapshots do not contain those IDs, so their links remain estimates.", 15f)
            addText("OpenStreetMap data is shown with source attribution. Map coverage varies by island.", 15f)
            addButton("Choose Atoll and Island") { chooseAtoll() }
            addButton("Search Island by Name") { searchIsland() }
            addButton("Import Haluvi Navigation Backup or Shared Map") { chooseBackupFile() }
            addText("Backup import keeps valid saved roads and places on this phone, including Google Places entries. Place source labels are preserved.", 15f)
            val roads = summaries.sumOf { it.roadCount }
            val places = summaries.sumOf { it.placeCount }
            val unlocated = summaries.sumOf { it.unlocatedPlaceCount }
            addText("${summaries.size} islands have an offline map. $roads road sections and ${places + unlocated} saved place records: $places with coordinates and $unlocated without usable coordinates.", 19f, live = true)
            if (summaries.isEmpty()) addText("No island maps downloaded yet.")
            summaries.forEach { summary ->
                val file = summary.file
                addText("${summary.islandName}. ${summary.roadCount} road sections, ${summary.placeCount} places with coordinates, ${summary.unlocatedPlaceCount} place records without usable coordinates, ${summary.length / 1024} kilobytes. Saved ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(summary.downloadedAt))}.")
                addButton("Explore ${summary.islandName}") { openSavedMap(summary, false, "Back to Map Management", ::showMapManager) }
                addButton("Explore places on ${summary.islandName}") { openSavedMap(summary, true, "Back to Map Management", ::showMapManager) }
                addButton("Update ${summary.islandName}") { updateSavedIslandMap(summary) }
                addButton("Delete map for ${summary.islandName}") {
                    AlertDialog.Builder(this@MainActivity).setTitle("Delete island map?")
                        .setMessage("Delete the saved offline map for ${summary.islandName} from this phone?")
                        .setPositiveButton("Delete") { _, _ -> Thread { store.delete(file); mainHandler.post { if (prefs.getString("session_map", null) == file.name) prefs.edit().remove("session_map").apply(); showMapManager() } }.start() }
                        .setNegativeButton("Cancel", null).show()
                }
            }
            addButton("OpenStreetMap attribution") { showAttribution() }
        }
    }

    private fun updateSavedIslandMap(summary: StoredMapSummary) {
        val atolls = runCatching { IslandCatalog.load(this) }.getOrNull().orEmpty()
        val match = atolls.firstNotNullOfOrNull { atoll ->
            atoll.islands.firstOrNull { storedMapMatchesIsland(summary, atoll, it) }?.let { atoll to it }
        }
        if (match != null) {
            val (atoll, island) = match
            searchIsland("${island.name}, ${atoll.fullname}", island, atoll)
        } else searchIsland(summary.islandName.substringBefore(","))
    }

    private fun openSavedMap(summary: StoredMapSummary, forPlaces: Boolean, backLabel: String, back: () -> Unit) {
        page("Opening ${summary.islandName}", backLabel, back) { addText("Loading the saved map…", live = true) }
        val generation = screenGeneration
        Thread {
            val result = runCatching { store.load(summary.file) }
            mainHandler.post {
                if (screenGeneration != generation) return@post
                result.onSuccess { map ->
                    loadedMap = map; loadedFile = summary.file
                    if (forPlaces) showPlaceList() else chooseRoad()
                }.onFailure { Toast.makeText(this, "Could not open the saved map. ${it.message ?: ""}", Toast.LENGTH_LONG).show(); back() }
            }
        }.start()
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
                        status.text = "Import complete. ${report.mapsProcessed} island maps checked, ${report.roadsAdded} roads and ${report.placesAdded} places with coordinates added, including saved Google Places entries. ${report.unlocatedPlacesAdded} place records without usable names or coordinates were preserved for review."
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
            addText("${map.roads.size} mapped roads, ${map.places.size} place records with coordinates, ${map.unlocatedPlaces.size} place records without usable coordinates, and ${map.roads.sumOf { it.lengthMeters }.roundToInt()} metres of road geometry.")
            addText("Road geometry comes from OpenStreetMap. Saved place entries retain their original sources; coverage and positional accuracy depend on each source.")
            if (!map.connectivityVerified) addText("Some road sections lack OpenStreetMap node IDs. Connections for those sections are estimated from identical coordinates and are not confirmed junctions.")
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
        val detail = TextView(this).apply { text = "Preparing map download…"; textSize = 18f; setPadding(dp(20), dp(18), dp(20), dp(8)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; max = 100; contentDescription = "Map download progress"; setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(detail); addView(bar) }
        val dialog = AlertDialog.Builder(this).setTitle("Downloading ${area.shortName} map")
            .setView(content).setNegativeButton("Cancel", null).create()
        activeDownloadDialog = dialog
        dialog.setOnCancelListener { activeDownload?.cancel(); activeDownload = null; status.text = "Map download cancelled."; progress.visibility = View.GONE; announce(status.text.toString(), status) }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { activeDownload?.cancel(); dialog.dismiss() } }
        dialog.show()
        activeDownload = osm.download(area, onProgress = { update ->
            detail.text = if (update.totalBytes > 0 && update.stage.startsWith("Downloading")) {
                val percent = ((100.0 * update.bytesRead / update.totalBytes).toInt()).coerceIn(0, 100)
                bar.isIndeterminate = false; bar.progress = percent
                "${update.stage}. $percent percent, ${update.bytesRead / 1024} of ${update.totalBytes / 1024} kilobytes received."
            } else {
                bar.isIndeterminate = update.stage.startsWith("Connecting") || update.stage.startsWith("Processing")
                update.stage + if (update.bytesRead > 0) ". ${update.bytesRead / 1024} kilobytes received." else ""
            }
            status.text = detail.text
        }) { result ->
            activeDownload = null
            progress.visibility = View.GONE
            result.onSuccess { map ->
                detail.text = "Saving downloaded roads and places on this phone…"
                status.text = detail.text
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                dialog.setCancelable(false)
                val islandKey = area.atollCode.takeIf { it.isNotBlank() }?.let { "$it:${area.shortName}" }.orEmpty()
                val named = map.copy(islandName = area.displayName, sourceIslandKey = islandKey)
                Thread {
                    val saved = runCatching { store.updateWithNetwork(area, named) }
                    mainHandler.post {
                        if (saved.isSuccess) {
                            val (savedFile, savedMap) = saved.getOrThrow()
                            loadedMap = savedMap; loadedFile = savedFile
                            dialog.dismiss(); activeDownloadDialog = null
                            val sourceNote = if (map.sourceIslandKey.startsWith("builtin:"))
                                " Online service was unavailable, so the bundled atoll OpenStreetMap snapshot dated ${map.sourceIslandKey.substringAfterLast(":")} was used."
                            else ""
                            val accuracyNote = if (savedMap.connectivityVerified) " Road connections use OSM node IDs from the downloaded data."
                                else " Some connections remain estimates because this map source did not include OSM node IDs."
                            announce("Map ready. ${area.shortName} is saved offline with ${savedMap.roads.size} road sections, ${savedMap.places.size} places with coordinates, and ${savedMap.unlocatedPlaces.size} place records without usable coordinates.$sourceNote$accuracyNote", status)
                            showMapOverview()
                        } else {
                            detail.text = "Could not save this map: ${saved.exceptionOrNull()?.message ?: "storage error"}. You can retry."
                            status.text = detail.text; dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Close"
                        }
                    }
                }.start()
            }.onFailure { error ->
                if (error.message?.contains("cancelled", true) == true) {
                    status.text = "Map download cancelled. No new map was saved."
                    dialog.dismiss(); activeDownloadDialog = null
                } else {
                    detail.text = "Download failed: ${error.message ?: "network error"}. No new map was saved. You can retry."
                    status.text = detail.text; dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Close"
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { dialog.dismiss(); activeDownloadDialog = null }
                }
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
            val generation = screenGeneration
            val search = addEdit("Search islands in ${atoll.fullname}")
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            val status = addStatus("Reading downloaded island maps…")
            var summaries: List<StoredMapSummary>? = null
            fun findSavedMap(island: IslandEntry): StoredMapSummary? {
                return summaries.orEmpty().firstOrNull { summary -> storedMapMatchesIsland(summary, atoll, island) }
            }
            fun populate() {
                results.removeAllViews()
                val matches = atoll.islands.filter { it.name.contains(search.text.toString(), true) }
                if (summaries == null) { status.text = "Reading downloaded island maps…"; return }
                matches.forEach { island ->
                    val saved = findSavedMap(island)
                    val label = "${island.name}${if (saved == null) ". Map not downloaded" else ". Map downloaded, ${saved.roadCount} road sections, ${saved.placeCount} places with coordinates, ${saved.unlocatedPlaceCount} without usable coordinates"}"
                    val button = Button(this@MainActivity).apply {
                        text = label; contentDescription = "$label, ${atoll.fullname}"; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener {
                            if (saved == null) searchIsland("${island.name}, ${atoll.fullname}", island, atoll)
                            else {
                                openSavedMap(saved, forPlaces, "Back to Island List") { chooseSavedIsland(atoll, forPlaces) }
                            }
                        }
                    }
                    results.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                status.text = "${matches.size} islands in ${atoll.fullname}."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            populate()
            Thread {
                val loaded = store.summaries()
                mainHandler.post { if (screenGeneration == generation) { summaries = loaded; populate() } }
            }.start()
        }
    }

    private fun loadMap(file: File) {
        loadedMap = store.load(file); loadedFile = file
    }

    private fun chooseRoad(filterText: String = "") {
        val map = loadedMap ?: return showMapManager()
        // Group and sort the road index once per screen. Repeating this over every
        // geometry section on every keystroke made large imported maps sluggish.
        val groupedRoads = groupRoadsByName(map.roads)
        page("Explore Roads in ${map.islandName}", "Back to Island List", ::chooseMapForExploration) {
            addText("Search by road name. Each named road appears once; its mapped sections are grouped together.")
            val search = addEdit("Search roads", filterText)
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            var displayedCount = 100
            fun populate() {
                results.removeAllViews()
                val allMatches = groupedRoads.filter { it.first.contains(search.text.toString(), ignoreCase = true) }
                val matches = allMatches.take(displayedCount)
                matches.forEach { (name, sections) ->
                    val total = sections.sumOf { it.lengthMeters }.roundToInt()
                    val label = "$name, $total metres across ${sections.size} sections"
                    val b = Button(this@MainActivity).apply { text = label; contentDescription = label; isAllCaps = false; minHeight = dp(58); setOnClickListener { showRoadSections(name, sections) } }
                    results.addView(b, LinearLayout.LayoutParams(-1, -2))
                }
                if (matches.size < allMatches.size) {
                    val more = Button(this@MainActivity).apply {
                        text = "Show next 100 roads"; contentDescription = "Show the next 100 of ${allMatches.size} matching roads"; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener { displayedCount += 100; populate() }
                    }
                    results.addView(more, LinearLayout.LayoutParams(-1, -2))
                }
                statusView?.text = if (allMatches.isEmpty()) "No matching roads." else "Showing ${matches.size} of ${allMatches.size} named roads. Their individual geometry sections stay available for accurate junction movement."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { displayedCount = 100; populate() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addStatus("${map.roads.map { it.name.trim().lowercase() }.distinct().size} named roads are available offline across ${map.roads.size} geometry sections.")
            populate()
        }
    }

    private fun showRoadSections(name: String, sections: List<Road>) {
        val ordered = continuousNamedRoadPaths(sections.sortedBy { it.id })
        if (ordered.size == 1) return showRoadOverview(ordered.single())
        val map = loadedMap ?: return
        page("$name Road Sections", "Back to Roads", { chooseRoad(name) }) {
            addText("$name has ${ordered.size} connected parts. Explore each part to cover the full mapped road. Every part follows the mapped shape and stops at mapped junctions.")
            ordered.forEachIndexed { index, road ->
                val label = "Part ${index + 1}, ${road.lengthMeters.roundToInt()} metres. ${endpointDescription(road, map)}"
                addButton(label) { showRoadOverview(road) }
            }
        }
    }

    private fun showRoadOverview(road: Road) {
        val map = loadedMap ?: return
        page("Road Overview", "Back to Roads", { chooseRoad() }) {
            addText("Loading road details and matching saved places…", live = true)
        }
        val generation = screenGeneration
        Thread {
            val result = runCatching {
                val e = ExplorerEngine(map)
                e.start(road, 0.0)
                Triple(endpointDescription(road, map), connectionsAtEitherEnd(road, map), e.placesAlongCurrentRoad())
            }
            mainHandler.post {
                if (generation != screenGeneration || isFinishing || isDestroyed) return@post
                result.onSuccess { (endpoints, connections, orderedPlaces) ->
                    showRoadOverviewContent(road, map, endpoints, connections, orderedPlaces)
                }.onFailure {
                    page("Road Overview", "Back to Roads", { chooseRoad() }) {
                        addText("Road details could not be prepared.")
                        addButton("Try again") { showRoadOverview(road) }
                    }
                }
            }
        }.start()
    }

    private fun showRoadOverviewContent(
        road: Road,
        map: MapData,
        endpoints: String,
        connections: List<String>,
        orderedPlaces: List<NearbyPlace>
    ) {
        page("Road Overview", "Back to Roads", { chooseRoad() }) {
            addText("Road: ${road.name}")
            addText("Choose where to start. These controls are at the top of the screen.")
            val (firstEnd, otherEnd) = endpointNames(road)
            addButton("Start at the $firstEnd end of ${road.name}, facing ${compassName(bearingAtEndpoint(road, true))}") { launchOnRoad(road, 0.0) }
            addButton("Start at the $otherEnd end of ${road.name}, facing ${compassName(bearingAtEndpoint(road, false))}") { launchOnRoad(road, road.lengthMeters) }
            addButton("Choose a position along ${road.name}") { chooseRoadPosition(road) }
            addButton("Places along ${road.name}, ${orderedPlaces.size} places") { showRoadPlaces(road, orderedPlaces) }
            addText("Road details")
            addText("Mapped length: ${road.lengthMeters.roundToInt()} metres. Road type: ${road.kind}.")
            addText(endpoints)
            addText("Roads at the ends: ${if (connections.isEmpty()) "None mapped." else connections.joinToString("; ")}")
            addText("Next Place uses a saved address when it names a mapped road. Generic addresses use the nearest mapped road geometry, so a place is not repeated on every nearby road. No distance cut-off is used; distant matches are labeled estimates. Next Junction stops at each mapped junction.")
        }
    }

    private fun showRoadPlaces(road: Road, orderedPlaces: List<NearbyPlace>) {
        page("Places Along ${road.name}", "Back to Road Overview", { showRoadOverview(road) }) {
            addText("${orderedPlaces.size} place records with coordinates are ordered from the first end of ${road.name}. Distance and address labels show how strongly each matches this road. Records without usable coordinates are available from Explore Places.")
            if (orderedPlaces.isEmpty()) {
                addText("No mapped places were found along this road.")
            } else {
                val rows = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(rows)
                var displayedCount = 100
                val status = addStatus("Showing 0 of ${orderedPlaces.size} places.")
                fun populate() {
                    rows.removeAllViews()
                    orderedPlaces.take(displayedCount).forEachIndexed { index, item ->
                        val source = placeSourceLabel(item.place).ifBlank { "unknown source" }
                        val label = "Place ${index + 1}: ${item.place.name}, ${item.place.category}, source $source, ${item.alongDistance.roundToInt()} metres along the road, ${item.roadDistance.roundToInt()} metres from it. ${item.association}. ${if (item.place.entranceVerified) "Entrance mapped." else "Entrance not confirmed."}"
                        val button = Button(this@MainActivity).apply {
                            text = label; contentDescription = label; isAllCaps = false; minHeight = dp(58)
                            setOnClickListener { showPlaceDetail(item.place, "Back to Places Along ${road.name}") { showRoadPlaces(road, orderedPlaces) } }
                        }
                        rows.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                    }
                    if (displayedCount < orderedPlaces.size) {
                        val more = Button(this@MainActivity).apply {
                            text = "Show next 100 places"; contentDescription = "Show the next 100 of ${orderedPlaces.size} places"; isAllCaps = false; minHeight = dp(58)
                            setOnClickListener { displayedCount += 100; populate() }
                        }
                        rows.addView(more, LinearLayout.LayoutParams(-1, -2))
                    }
                    status.text = "Showing ${minOf(displayedCount, orderedPlaces.size)} of ${orderedPlaces.size} places."
                }
                populate()
            }
            addButton("Back to Road Overview") { showRoadOverview(road) }
        }
    }

    private fun bearingAtEndpoint(road: Road, first: Boolean): Double = if (first)
        bearingDegrees(road.points.first(), road.points.getOrElse(1) { road.points.last() })
    else bearingDegrees(road.points.last(), road.points.getOrElse(road.points.lastIndex - 1) { road.points.first() })

    private fun endpointNames(road: Road): Pair<String, String> {
        val first = road.points.first()
        val last = road.points.last()
        val eastWestDistance = abs(last.lon - first.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians((first.lat + last.lat) / 2))
        val northSouthDistance = abs(last.lat - first.lat) * 111_320.0
        return if (eastWestDistance >= northSouthDistance) {
            if (first.lon <= last.lon) "west" to "east" else "east" to "west"
        } else {
            if (first.lat <= last.lat) "south" to "north" else "north" to "south"
        }
    }

    private fun endpointDescription(road: Road, map: MapData): String {
        val firstBearing = bearingDegrees(road.points.first(), road.points.last())
        val otherBearing = (firstBearing + 180) % 360.0
        fun details(at: GeoPoint, id: Long?): String {
            val connected = if (id == null) emptyList() else map.roads.filter { it.id != road.id && !it.name.equals(road.name, true) && id in listOf(it.startNode, it.endNode) }.map { it.name }.distinct()
            val landmark = map.places.minByOrNull { distanceMeters(it.point, at) }?.takeIf { distanceMeters(it.point, at) < 100 }?.let { ", near ${it.name}" }.orEmpty()
            val qualifier = if (!road.nodeIdsVerified) "possible roads" else "roads"
            val junction = if (connected.isNotEmpty()) ", $qualifier: ${connected.take(3).joinToString()}" else ", no other road mapped"
            return "$junction$landmark"
        }
        val (firstEnd, otherEnd) = endpointNames(road)
        return "$firstEnd end, facing ${compassName(firstBearing)}${details(road.points.first(), road.startNode)}. $otherEnd end, facing ${compassName(otherBearing)}${details(road.points.last(), road.endNode)}."
    }

    private fun connectionsAtEitherEnd(road: Road, map: MapData): List<String> = listOfNotNull(road.startNode, road.endNode)
        .flatMap { id -> map.roads.filter { it.id != road.id && !it.name.equals(road.name, true) && id in listOf(it.startNode, it.endNode) }.map { it.name } }.distinct()

    private fun chooseRoadPosition(road: Road) {
        page("Choose Starting Position", "Back to Road Overview", { showRoadOverview(road) }) {
            addText("Choose where to begin along ${road.name}. The position follows the mapped road shape.")
            val positionLabel = addText("Position: 0 percent from the first endpoint.")
            val slider = SeekBar(this@MainActivity).apply { max = 100; progress = 0; contentDescription = "Starting position along road" }
            column.addView(slider, LinearLayout.LayoutParams(-1, dp(58)))
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { positionLabel.text = "Position: $p percent from the first endpoint." }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            addButton("Start at selected position") { launchOnRoad(road, road.lengthMeters * slider.progress / 100.0) }
            addButton("Start facing opposite direction") { launchOnRoad(road, road.lengthMeters * slider.progress / 100.0, false) }
        }
    }

    private fun launchOnRoad(road: Road, progress: Double, faceAlongRoad: Boolean = true) {
        val map = loadedMap ?: return
        engine = ExplorerEngine(map).apply { start(road, progress, faceAlongRoad) }
        startPosition = engine?.position()
        returnJourney = false; destination = null
        persistSession()
        showExplore()
    }

    private fun showPlaceList(filterText: String = "") {
        val map = loadedMap ?: return showMapManager()
        page("Explore Places in ${map.islandName}", "Back to Island List", ::chooseMapForPlaces) {
            addText("Search mapped places. A mapped place position does not confirm that an entrance faces a particular road.")
            if (map.unlocatedPlaces.isNotEmpty()) addButton("Review ${map.unlocatedPlaces.size} saved place records without usable coordinates") { showUnlocatedPlaceList() }
            val search = addEdit("Search places", filterText)
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            var displayedCount = 100
            fun populate() {
                results.removeAllViews()
                val allMatches = map.places.filter {
                    (it.name + " " + it.category + " " + placeSourceLabel(it) + " " + it.roadAddressHints().joinToString(" "))
                        .contains(search.text.toString(), true)
                }
                val matches = allMatches.take(displayedCount)
                matches.forEach { place ->
                    val label = "${place.name}, ${place.category}" + placeSourceLabel(place).takeIf { it.isNotBlank() }?.let { ", source: $it" }.orEmpty()
                    val b = Button(this@MainActivity).apply { text = label; contentDescription = "Place $label"; isAllCaps = false; minHeight = dp(58); setOnClickListener { showPlaceDetail(place) } }
                    results.addView(b, LinearLayout.LayoutParams(-1, -2))
                }
                if (matches.size < allMatches.size) {
                    val more = Button(this@MainActivity).apply {
                        text = "Show next 100 places"; contentDescription = "Show the next 100 of ${allMatches.size} matching places"; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener { displayedCount += 100; populate() }
                    }
                    results.addView(more, LinearLayout.LayoutParams(-1, -2))
                }
                statusView?.text = "${map.places.size} places with coordinates available offline. ${map.unlocatedPlaces.size} saved records have no usable coordinates. Showing ${matches.size} of ${allMatches.size} matching places."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { displayedCount = 100; populate() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addStatus("${map.places.size} places with coordinates available offline. ${map.unlocatedPlaces.size} saved records have no usable coordinates.")
            populate()
        }
    }

    private fun showUnlocatedPlaceList() {
        val map = loadedMap ?: return showMapManager()
        page("Saved Place Records Without Coordinates", "Back to Places", { showPlaceList() }) {
            addText("These records were preserved from the backup but cannot be placed on a road because their coordinates or names are missing or invalid. Their source data remains saved.")
            val rows = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(rows)
            var displayedCount = 100
            val status = addStatus("Showing 0 of ${map.unlocatedPlaces.size} saved records.")
            fun populate() {
                rows.removeAllViews()
                map.unlocatedPlaces.take(displayedCount).forEachIndexed { index, place ->
                    val display = place.name.ifBlank { "Unnamed place record" }
                    val label = "Record ${index + 1}: $display, ${place.category.ifBlank { "uncategorized" }}, source ${place.source.ifBlank { "unknown" }}. ${place.reason}"
                    val button = Button(this@MainActivity).apply {
                        text = label; contentDescription = label; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener {
                            page("Saved Place Record", "Back to Saved Place Records", ::showUnlocatedPlaceList) {
                                addText(display)
                                addText("Category: ${place.category.ifBlank { "not provided" }}. Source: ${place.source.ifBlank { "unknown" }}. Record ID: ${place.id.ifBlank { "not provided" }}.")
                                addText(place.reason)
                                addText("Original saved provider record:")
                                addText(place.sourceData.ifBlank { "No original fields were provided." })
                            }
                        }
                    }
                    rows.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
                if (displayedCount < map.unlocatedPlaces.size) {
                    val more = Button(this@MainActivity).apply {
                        text = "Show next 100 records"; contentDescription = "Show the next 100 of ${map.unlocatedPlaces.size} saved records"; isAllCaps = false; minHeight = dp(58)
                        setOnClickListener { displayedCount += 100; populate() }
                    }
                    rows.addView(more, LinearLayout.LayoutParams(-1, -2))
                }
                status.text = "Showing ${minOf(displayedCount, map.unlocatedPlaces.size)} of ${map.unlocatedPlaces.size} saved records."
            }
            populate()
            addButton("Back to Places") { showPlaceList() }
        }
    }

    private fun showPlaceDetail(place: Place, backLabel: String = "Back to Places", back: () -> Unit = { showPlaceList() }) {
        val map = loadedMap ?: return
        val nearest = ExplorerEngine(map).nearestRoad(place.point)
        page("Place Details", backLabel, back) {
            addText("${place.name}. Category: ${place.category}.")
            placeSourceLabel(place).takeIf { it.isNotBlank() }?.let { addText("Source: $it.") }
            addText(if (place.entranceVerified) "OpenStreetMap includes an entrance tag at this position." else "This is a mapped place position. Its building entrance and which road it faces are not verified.")
            addText("Position: ${place.point.lat}, ${place.point.lon}.")
            place.roadAddressHints().takeIf { it.isNotEmpty() }?.let { addText("Road or address information: ${it.distinct().joinToString()}") }
            addText(nearest?.let { "Nearest mapped road: ${it.first.name}, about ${distanceMeters(place.point, pointAt(it.first, it.second)).roundToInt()} metres away." } ?: "No mapped road found nearby.")
            addButton("Start exploration near this place", enabled = nearest != null) {
                engine = ExplorerEngine(map).apply { startNear(place.point) }
                startPosition = engine?.position(); destination = null; returnJourney = false; persistSession(); showExplore()
            }
            addButton("Bookmark this place") { addBookmark("place:${loadedFile?.name}:${place.id}", "${place.name}, ${map.islandName}") }
            if (engine != null) addButton("Set as destination for current exploration") {
                destination = place
                history("Destination set: ${place.name}")
                showExplore()
            }
        }
    }

    private fun placeSourceLabel(place: Place): String = when (place.source.trim().lowercase()) {
        "google" -> "Google Places"
        "osm_map" -> "OpenStreetMap"
        "osm_search" -> "OpenStreetMap search"
        "rtl" -> "RTL transport data"
        "manual" -> "saved place"
        "" -> ""
        else -> place.source.replace('_', ' ')
    }

    private fun showExplore() {
        val e = engine ?: return showHome()
        val map = loadedMap ?: return showHome()
        page("Exploration", "Back to ${map.islandName} Roads", { chooseRoad() }) {
            val current = e.road
            addText("${map.islandName}. ${current?.name ?: "No road selected"}.")
            if (current?.nodeIdsVerified != true) addText("Road connections may be estimated.")
            val live = addStatus(e.whereAmI())
            val pos = e.position()
            addText(pos?.let { "Current virtual position: ${"%.6f".format(Locale.US, it.lat)}, ${"%.6f".format(Locale.US, it.lon)}." }.orEmpty())
            addText("Distance: ${e.travelledMeters.roundToInt()} metres. Places visited: ${e.visitedPlaceCount()}.")
            addText("Step: ${stepMetres()} metres. Backward keeps your facing direction.")
            addButton("Move Forward") { moveForward(live) }
            addButton("Move Backward") { moveBackward(live) }
            addButton("Next Place on Road") { moveToNextPlace(live) }
            addButton("Previous Place on Road") { moveToPreviousPlace(live) }
            addButton("Previous Junction / Road End") { moveToPreviousJunction(live) }
            addButton("Next Junction / Road End") { moveToJunction(live) }
            addButton("Look Around") { showLookAround() }
            addButton("Where Am I?") { announce(e.whereAmI(), live) }
            addButton("Turn Around") { e.turnAround(); changed("You turned around. You are facing ${e.facing()}.", live) }
            val atEnd = e.atDecisionPoint()
            val branches = e.connections()
            if (atEnd) {
                val junctionVerified = current?.nodeIdsVerified == true && branches.all { it.road.nodeIdsVerified }
                val junctionLabel = if (junctionVerified) "Junction" else "Possible junction (map estimate)"
                addText(if (branches.isEmpty()) "Road end. No connecting road mapped." else "$junctionLabel. ${branches.size} roads.")
                if (branches.isNotEmpty()) {
                    if (e.canContinueOnCurrentRoad()) addButton("Continue on ${current?.name}") {
                        e.continueOnCurrentRoad()
                        changed("Continuing on ${current?.name}.", live)
                    }
                    addButton("Turn Left") { chooseBranch("left", live) }
                    if (!e.canContinueOnCurrentRoad()) addButton("Continue Straight") { chooseBranch("straight", live) }
                    addButton("Turn Right") { chooseBranch("right", live) }
                    addButton("Choose Connected Road") { chooseConnection(branches) }
                    branches.forEach { c -> addText("${relativeDirection(c.turn)}: ${c.road.name}, ${compassName(c.bearingOut)}.") }
                }
            }
            addButton(if (autoWalking) "Stop Auto Walk" else "Start Auto Walk") { if (autoWalking) stopAutoWalk("Automatic walking stopped.") else startAutoWalk(live) }
            addButton("Bookmark This Position") { addBookmark("position:${loadedFile?.name}:${pos?.lat},${pos?.lon}", "${current?.name}, ${map.islandName}, ${e.travelledMeters.roundToInt()} metres travelled") }
            addButton("Exploration History") { showHistory() }
            if (returnJourney) addButton("Check Return Distance") {
                val here = e.position(); val start = startPosition
                announce(if (here == null || start == null) "Starting position is unavailable." else "You are ${distanceMeters(here, start).roundToInt()} metres from your return point.", live)
            }
            destination?.let { goal -> addText("Find a Destination goal: ${goal.name}.") }
            addButton("Save and Return to Main Menu") { persistSession(); showHome() }
        }
    }

    private fun moveForward(status: TextView?) {
        val e = engine ?: return
        val before = e.progressMeters
        val amount = e.move(stepMetres())
        if (amount == 0.0) { announce("You are already at the road endpoint.", status); return }
        val ending = if (e.atRoadEnd()) {
            val branches = e.connections()
            val verified = e.road?.nodeIdsVerified == true && branches.all { it.road.nodeIdsVerified }
            if (branches.isEmpty()) "You reached the end of the road." else if (verified)
                "You reached a junction. ${branches.size} connected roads. ${branches.joinToString { it.road.name }}. Choose a road to continue."
            else "Possible junction based on shared map coordinates. ${branches.joinToString { it.road.name }} may connect here. Choose only if this imported connection seems correct."
        } else "Moved forward ${amount.roundToInt()} metres."
        checkNearbyPlaces(status, before)
        changed(ending, status)
    }

    private fun moveBackward(status: TextView?) {
        val e = engine ?: return
        val before = e.progressMeters
        val amount = e.moveBackward(stepMetres())
        checkNearbyPlaces(status, before, keepFacing = true)
        changed(if (amount == 0.0) "No road geometry behind you." else "Moved backward ${amount.roundToInt()} metres. You are still facing ${e.facing()}.", status)
    }

    private fun moveToJunction(status: TextView?) {
        val e = engine ?: return
        val dist = e.remainingToDecisionPoint()
        if (dist < 0.5) {
            val options = e.connections()
            val verified = e.road?.nodeIdsVerified == true && options.all { it.road.nodeIdsVerified }
            announce(if (options.isEmpty()) "Road end. No other road mapped." else
                "${if (verified) "Junction" else "Possible junction"} on ${e.road?.name}. ${options.joinToString { "${relativeDirection(it.turn)}: ${it.road.name}" }}.", status)
            return
        }
        val before = e.progressMeters
        e.move(dist)
        checkNearbyPlaces(status, before)
        val options = e.connections()
        val verified = e.road?.nodeIdsVerified == true && options.all { it.road.nodeIdsVerified }
        changed(if (options.isEmpty()) "End of ${e.road?.name}. No other road mapped." else
            "${if (verified) "Junction" else "Possible junction"} on ${e.road?.name}. ${options.joinToString { "${relativeDirection(it.turn)}: ${it.road.name}" }}.", status)
    }

    private fun moveToPreviousJunction(status: TextView?) {
        val e = engine ?: return
        val before = e.progressMeters
        val moved = e.moveBackwardToDecisionPoint()
        if (moved == 0.0) { announce("No earlier junction or road end mapped.", status); return }
        checkNearbyPlaces(status, before, keepFacing = true)
        val options = e.connections()
        val verified = e.road?.nodeIdsVerified == true && options.all { it.road.nodeIdsVerified }
        changed(if (options.isEmpty()) "Previous road end on ${e.road?.name}." else
            "${if (verified) "Previous junction" else "Previous possible junction"} on ${e.road?.name}. ${options.joinToString { "${relativeDirection(it.turn)}: ${it.road.name}" }}.", status)
    }

    private fun relativeDirection(turn: Double): String = when {
        turn < -35 -> "Left"
        turn > 35 -> "Right"
        else -> "Straight"
    }

    private fun chooseBranch(turn: String, status: TextView?) {
        val e = engine ?: return
        val currentVerified = e.road?.nodeIdsVerified == true
        val selected = e.chooseTurn(turn)
        if (selected == null) { announce("No mapped ${turn} turn is available here. Choose one of the connected roads.", status); return }
        changed(if (currentVerified && selected.road.nodeIdsVerified) "Turned $turn onto ${selected.road.name}. Facing ${e.facing()}."
            else "Followed the estimated $turn connection onto ${selected.road.name}. This map does not verify this junction. Facing ${e.facing()}.", status)
    }

    private fun chooseConnection(options: List<RoadConnection>) {
        if (loadedMap == null) return
        val currentVerified = engine?.road?.nodeIdsVerified == true
        page("Choose Connected Road", "Back to Exploration", ::showExplore) {
            addText(if (currentVerified && options.all { it.road.nodeIdsVerified }) "Choose one of the roads connected at this junction."
                else "These possible connections include links estimated from shared coordinates.")
            options.forEach { option -> addButton("${option.road.name}, ${compassName(option.bearingOut)}, ${if (currentVerified && option.road.nodeIdsVerified) "confirmed OSM connection" else "estimated connection"}") {
                engine?.takeConnection(option)
                showExplore()
            } }
        }
    }

    private fun moveToNextPlace(status: TextView?) {
        val e = engine ?: return
        val next = e.nextPlace()
        if (next == null) { announce("No more saved places are matched to ${e.road?.name} ahead. Other island places remain available in Explore Places.", status); return }
        val crossings = e.roadCrossingsTo(next.alongDistance)
        e.moveAlongRoadTo(next.alongDistance)
        e.rememberPlaceNavigation(next)
        e.markVisited(next)
        changed(formatPlaceStepAnnouncement("Next place", next, e.road?.name.orEmpty(), crossings), status)
    }

    private fun moveToPreviousPlace(status: TextView?) {
        val e = engine ?: return
        val place = e.previousPlace()
        if (place == null) { announce("No more saved places are matched to ${e.road?.name} behind. Other island places remain available in Explore Places.", status); return }
        val crossings = e.roadCrossingsTo(place.alongDistance, keepFacing = true)
        e.moveAlongRoadTo(place.alongDistance, keepFacing = true)
        e.rememberPlaceNavigation(place)
        e.markVisited(place)
        changed(formatPlaceStepAnnouncement("Previous place", place, e.road?.name.orEmpty(), crossings), status)
    }


    private fun showLookAround() {
        val e = engine ?: return
        val center = e.position() ?: return
        val radius = prefs.getInt("radius", 100).toDouble()
        val places = e.nearbyPlaces(radius)
        val options = e.connections()
        val mode = prefs.getInt("google_places_mode", 0).coerceIn(0, 2)
        val googleEnabled = prefs.getBoolean("google_places_enabled", false)
        page("Look Around", "Back to Exploration", ::showExplore) {
            val generation = screenGeneration
            addText("Surroundings within ${radius.roundToInt()} metres. Left and right are relative to your current facing direction.")
            if (mode > 0) {
                addText("Google Maps live results are separate from your saved Google Places and other offline places. They do not change Next Place or Previous Place on this road.")
                val googleStatus = addText("", live = true)
                val googleRows = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(googleRows)
                fun lookupGoogle() {
                    if (!googleEnabled) {
                        googleStatus.text = "Google Places is turned off. Your saved offline places are shown below."
                        googleStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                        return
                    }
                    val key = GooglePlacesKeyStore.load(this@MainActivity)
                    if (key.isNullOrBlank()) {
                        googleStatus.text = "No Google API key is saved. Your saved offline places are shown below."
                        googleStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                        return
                    }
                    googleStatus.text = "Searching Google Places near your virtual position."
                    GooglePlacesRepository.search(this@MainActivity, key, center, radius) { results, errorCode ->
                        if (generation != screenGeneration || isFinishing) return@search
                        googleRows.removeAllViews()
                        if (results == null) {
                            googleStatus.text = when (errorCode) {
                                -2 -> "The API key changed after Google Places started. Restart Haluvi Explorer to use the new key. Saved offline places are shown below."
                                null -> "Google Places lookup failed. Your saved offline places are shown below."
                                else -> "Google Places lookup failed with error $errorCode. Your saved offline places are shown below."
                            }
                            googleStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                        } else if (results.isEmpty()) {
                            googleStatus.text = "Google Maps returned no places for this search. Your saved offline places are shown below."
                            googleStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                        } else {
                            googleStatus.text = "Google Maps returned ${results.size} nearby results. Results are limited to 20 per search and may not include every place."
                            googleStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                            results.forEach { result ->
                                val label = listOf(result.name, result.category, result.address).filter { it.isNotBlank() }.joinToString(", ")
                                val item = Button(this@MainActivity).apply {
                                    text = label
                                    contentDescription = "Google Maps place: $label"
                                    isAllCaps = false
                                    minHeight = dp(58)
                                    setOnClickListener {
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle(result.name)
                                            .setMessage(listOfNotNull(result.category, result.address, "Google Maps result. Road side and entrance are not confirmed here.").joinToString(". "))
                                            .setPositiveButton("Open Google Maps") { _, _ ->
                                                val query = Uri.encode(listOfNotNull(result.name, result.address).joinToString(" "))
                                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")))
                                            }
                                            .setNegativeButton("Close", null)
                                            .show()
                                    }
                                }
                                googleRows.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                            }
                        }
                    }
                }
                addButton(if (mode == 2) "Search Google Places again" else "Search Google Places here") { lookupGoogle() }
                if (mode == 2) lookupGoogle()
            }
            addText(if (places.isEmpty()) "No saved mapped places within this radius." else "Saved offline places:")
            places.forEach { item ->
                val source = placeSourceLabel(item.place).takeIf { it.isNotBlank() }?.let { ", source: $it" }.orEmpty()
                addButton("${item.place.name}, ${item.place.category}$source, ${item.side}, ${item.distance.roundToInt()} metres") {
                    destination = item.place
                    page("Nearby Place", "Back to Look Around", ::showLookAround) {
                        addText("${item.place.name}, ${item.place.category}, ${item.side}, ${item.distance.roundToInt()} metres away.")
                        placeSourceLabel(item.place).takeIf { it.isNotBlank() }?.let { addText("Source: $it.") }
                        addText("Mapped point is ${item.roadDistance.roundToInt()} metres from ${engine?.road?.name}. ${item.association.replaceFirstChar { it.uppercase() }}.")
                        addText(if (item.place.entranceVerified) "An entrance is mapped here." else "Entrance location is not verified.")
                        addButton("Move to this place along the road") {
                            val delta = item.alongDistance - (engine?.progressMeters ?: 0.0)
                            val before = engine?.progressMeters ?: 0.0
                            val keepFacing = delta * (engine?.travelSign ?: 1) < 0
                            engine?.moveAlongRoadTo(item.alongDistance, keepFacing)
                            checkNearbyPlaces(null, before, keepFacing)
                            engine?.markVisited(item); persistSession(); showExplore()
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

    private fun checkNearbyPlaces(status: TextView?, previousProgress: Double? = null, keepFacing: Boolean = false) {
        val e = engine ?: return
        if (prefs.getBoolean("announce_places", true)) {
            val passed = if (previousProgress != null) e.placesPassedBetween(previousProgress, e.progressMeters, keepFacing)
                else e.nearbyPlaces(4.0)
            val newPlaces = passed.filter { placeRecordKey(it) !in passedPlaceIds }
            if (newPlaces.isNotEmpty()) {
                newPlaces.forEach { item ->
                    passedPlaceIds += placeRecordKey(item)
                    e.markVisited(item)
                }
                val descriptions = newPlaces.joinToString(separator = "; ") { item ->
                    if (item.roadDistance > 40.0) "${item.place.name}, mapped point ${item.roadDistance.roundToInt()} metres from the road; side uncertain"
                    else "${item.place.name} on your ${item.side}"
                }
                announce("Passing $descriptions.", status)
            }
        }
        val goal = destination
        if (goal != null) {
            val distance = e.position()?.let { distanceMeters(it, goal.point) } ?: Double.MAX_VALUE
            if (distance <= 20.0) { destination = null; announce("Destination reached: ${goal.name}.", status); history("Destination reached: ${goal.name}") }
        }
    }

    private fun placeRecordKey(item: NearbyPlace): String =
        "${item.recordIndex}|${item.place.source}|${item.place.id}|${item.place.name}|${item.place.category}|${item.place.point.lat}|${item.place.point.lon}"

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
        announce("Walking. It pauses at each junction or road end.", status)
        val task = object : Runnable {
            override fun run() {
                if (!autoWalking) return
                val e = engine ?: return stopAutoWalk("Automatic walking stopped.")
                val chunk = (prefs.getInt("walk_speed", 14) / 10.0).coerceIn(0.2, 0.8)
                val before = e.progressMeters
                val moved = e.move(chunk)
                checkNearbyPlaces(status, before)
                if (e.atRoadEnd() || moved < chunk || moved == 0.0) {
                    val connections = e.connections()
                    if (!prefs.getBoolean("stop_junctions", true) && e.canContinueOnCurrentRoad() && e.continueOnCurrentRoad()) {
                        status?.text = "Continuing straight on ${e.road?.name}."
                        persistSession()
                        mainHandler.postDelayed(this, 800)
                        return
                    }
                    autoWalking = false
                    val verified = e.road?.nodeIdsVerified == true && connections.all { it.road.nodeIdsVerified }
                    val junctionMessage = if (verified) "Automatic walking paused at a junction. ${connections.joinToString { it.road.name }} connect here."
                    else "Automatic walking paused at a possible junction. ${connections.joinToString { it.road.name }} may connect here based on coordinates."
                    announce(if (connections.isEmpty()) "Paused at road end. No other road mapped." else junctionMessage, status)
                    persistSession(); return
                }
                status?.text = "${e.road?.name}. ${e.remainingToDecisionPoint().roundToInt()} metres to next junction or end."
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
            .putInt("session_place_record", e.placeNavigationRecordIndex() ?: -1)
            .putFloat("session_facing", e.facingBearing.toFloat()).putFloat("session_distance", e.travelledMeters.toFloat())
            .putInt("session_junctions", e.visitedJunctions).apply()
    }

    private fun resumeSession() {
        val filename = prefs.getString("session_map", null) ?: return showHome()
        val file = store.all().firstOrNull { it.name == filename } ?: return showHome()
        page("Resume Exploration", "Back to Main Menu", ::showHome) { addText("Restoring your saved island and walking position…", live = true) }
        val generation = screenGeneration
        Thread {
            val loaded = runCatching { store.load(file) }
            mainHandler.post {
                if (screenGeneration != generation) return@post
                loaded.onFailure { Toast.makeText(this, "Saved map could not be opened. ${it.message ?: ""}", Toast.LENGTH_LONG).show(); showHome() }
                    .onSuccess { map ->
                        loadedMap = map; loadedFile = file
                        val road = findRoadPathById(map.roads, prefs.getString("session_road", null).orEmpty())
                        if (road == null) { chooseRoad(); return@onSuccess }
                        engine = ExplorerEngine(map).apply {
                            restore(road, prefs.getFloat("session_progress", 0f).toDouble(), prefs.getInt("session_sign", 1),
                                prefs.getFloat("session_facing", 0f).toDouble(), prefs.getFloat("session_distance", 0f).toDouble(),
                                prefs.getInt("session_junctions", 0))
                            restorePlaceNavigation(prefs.getInt("session_place_record", -1).takeIf { it >= 0 })
                        }
                        startPosition = engine?.position(); showExplore()
                    }
            }
        }
        .start()
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
                        page("Opening Bookmark", "Back to Saved Bookmarks", ::showBookmarks) { addText("Loading the saved island map…", live = true) }
                        val generation = screenGeneration
                        Thread {
                            val loaded = runCatching { store.load(file) }
                            mainHandler.post {
                                if (screenGeneration != generation) return@post
                                loaded.onSuccess { map ->
                                    loadedMap = map; loadedFile = file
                                    val e = ExplorerEngine(map)
                                    val point = when {
                                        parts[0] == "position" -> parts.getOrNull(2)?.split(",")?.takeIf { it.size == 2 }?.let { xy -> GeoPoint(xy[0].toDoubleOrNull() ?: 0.0, xy[1].toDoubleOrNull() ?: 0.0) }
                                        parts[0] == "place" -> map.places.firstOrNull { it.id == parts.getOrNull(2) }?.point
                                        else -> null
                                    }
                                    if (point != null && e.startNear(point) != null) {
                                        engine = e; startPosition = e.position(); destination = null; returnJourney = false; persistSession(); showExplore()
                                    } else { Toast.makeText(this@MainActivity, "Could not find a road near this bookmark.", Toast.LENGTH_LONG).show(); showBookmarks() }
                                }.onFailure { Toast.makeText(this@MainActivity, "Map could not be opened.", Toast.LENGTH_LONG).show(); showBookmarks() }
                            }
                        }.start()
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
            addText("Google Places searches around your virtual position and needs internet access. It is off by default. Online results stay separate from saved map places. Your imported Google Places remain available offline. Restart the app after replacing a key already used in this session.")
            addSpinner("Google Places mode", listOf("Offline maps only", "Manual Google search", "Automatic Google search with offline fallback"), prefs.getInt("google_places_mode", 0).coerceIn(0, 2)) {
                prefs.edit().putInt("google_places_mode", it).apply()
            }
            addSwitch("Allow Google Places API requests", "google_places_enabled", false)
            val googleKeyStatus = addText(if (GooglePlacesKeyStore.load(this@MainActivity).isNullOrBlank()) "Google API key: not saved." else "Google API key: saved securely on this phone.")
            val googleKeyInput = addEdit("Google Places API key")
            googleKeyInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            addButton("Save Google API key") {
                val key = googleKeyInput.text.toString().trim()
                if (key.isBlank()) {
                    googleKeyStatus.text = "Enter a Google Places API key first."
                } else if (GooglePlacesKeyStore.save(this@MainActivity, key)) {
                    googleKeyInput.text.clear()
                    googleKeyStatus.text = "Google API key saved securely on this phone. It is not included in map backups."
                    announce(googleKeyStatus.text.toString(), googleKeyStatus)
                } else {
                    googleKeyStatus.text = "Could not securely save the key on this phone."
                    announce(googleKeyStatus.text.toString(), googleKeyStatus)
                }
            }
            addButton("Delete saved Google API key") {
                GooglePlacesKeyStore.delete(this@MainActivity)
                prefs.edit().putBoolean("google_places_enabled", false).putInt("google_places_mode", 0).apply()
                googleKeyStatus.text = "Google API key deleted. Offline maps remain available."
                announce(googleKeyStatus.text.toString(), googleKeyStatus)
            }
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
                listOf("movement_mode", "step_distance", "announce_places", "stop_junctions", "speech_enabled", "walk_speed", "radius", "speech_rate", "speech_pitch", "google_places_mode", "google_places_enabled").forEach { prefs.edit().remove(it).apply() }
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

