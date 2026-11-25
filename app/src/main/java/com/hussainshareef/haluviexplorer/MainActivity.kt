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
            val generation = screenGeneration
            addText("Map downloads need an internet connection. Once saved, the road network and places work offline.")
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
            addText("OpenStreetMap data is shown with source attribution. Map coverage varies by island.", 15f)
            addButton("Choose Atoll and Island") { chooseAtoll() }
            addButton("Search Island by Name") { searchIsland() }
            addButton("Import Haluvi Navigation Backup or Shared Map") { chooseBackupFile() }
            addText("Backup import keeps valid saved roads and places on this phone, including Google Places entries. Place source labels are preserved.", 15f)
            val roads = summaries.sumOf { it.roadCount }
            val places = summaries.sumOf { it.placeCount }
            addText("${summaries.size} islands have an offline map. $roads road sections and $places places saved.", 19f, live = true)
            if (summaries.isEmpty()) addText("No island maps downloaded yet.")
            summaries.forEach { summary ->
                val file = summary.file
                addText("${summary.islandName}. ${summary.roadCount} road sections, ${summary.placeCount} mapped places, ${summary.length / 1024} kilobytes. Saved ${java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(summary.downloadedAt))}.")
                addButton("Explore ${summary.islandName}") { openSavedMap(summary, false, "Back to Map Management", ::showMapManager) }
                addButton("Explore places on ${summary.islandName}") { openSavedMap(summary, true, "Back to Map Management", ::showMapManager) }
                addButton("Update ${summary.islandName}") { searchIsland(summary.islandName.substringBefore(",")) }
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
                        status.text = "Import complete. ${report.mapsProcessed} island maps checked, ${report.roadsAdded} roads and ${report.placesAdded} places added, including saved Google Places entries. " +
                            (if (report.unsupportedPlacesSkipped > 0) "${report.unsupportedPlacesSkipped} entries without usable names or coordinates could not be added." else "All place entries with valid names and coordinates were imported.")
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
                val named = map.copy(islandName = area.displayName)
                Thread {
                    val saved = runCatching { store.save(named) }
                    mainHandler.post {
                        if (saved.isSuccess) {
                            loadedMap = named; loadedFile = saved.getOrThrow()
                            dialog.dismiss(); activeDownloadDialog = null
                            val sourceNote = if (named.sourceIslandKey.startsWith("builtin:"))
                                " Online service was unavailable, so the bundled atoll OpenStreetMap snapshot dated ${named.sourceIslandKey.substringAfterLast(":")} was used."
                            else ""
                            announce("Map ready. ${area.shortName} is saved offline with ${named.roads.size} road sections and ${named.places.size} places.$sourceNote", status)
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
                val islandKey = "${atoll.code}:${island.name}"
                return summaries.orEmpty().firstOrNull { summary ->
                    val file = summary.file
                    normalizedIsland(summary.sourceIslandKey) == normalizedIsland(islandKey) ||
                        normalizedIsland(summary.islandName).let { it.startsWith(normalizedIsland(island.name)) || it.endsWith(normalizedIsland(island.name)) } ||
                        file.nameWithoutExtension.equals(island.name.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-'), true)
                }
            }
            fun populate() {
                results.removeAllViews()
                val matches = atoll.islands.filter { it.name.contains(search.text.toString(), true) }
                if (summaries == null) { status.text = "Reading downloaded island maps…"; return }
                matches.forEach { island ->
                    val saved = findSavedMap(island)
                    val label = "${island.name}${if (saved == null) ". Map not downloaded" else ". Map downloaded, ${saved.roadCount} road sections, ${saved.placeCount} places"}"
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
                    val b = Button(this@MainActivity).apply { text = label; contentDescription = label; isAllCaps = false; minHeight = dp(58); setOnClickListener { showRoadSections(name, sections) } }
                    results.addView(b, LinearLayout.LayoutParams(-1, -2))
                }
                statusView?.text = if (matches.isEmpty()) "No matching roads." else "${matches.size} named roads shown. Their individual geometry sections stay available for accurate junction movement."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addStatus("${map.roads.map { it.name.trim().lowercase() }.distinct().size} named roads are available offline across ${map.roads.size} geometry sections.")
            populate()
        }
    }

    private fun showRoadSections(name: String, sections: List<Road>) {
        val ordered = sections.sortedBy { it.id }
        if (ordered.size == 1) return showRoadOverview(ordered.single())
        val map = loadedMap ?: return
        page("$name Road Sections", "Back to Roads", { chooseRoad(name) }) {
            addText("$name is stored as ${ordered.size} geometry sections to preserve junction movement. Choose a section to inspect or start walking it.")
            ordered.forEachIndexed { index, road ->
                val label = "Section ${index + 1}, ${road.lengthMeters.roundToInt()} metres. ${endpointDescription(road, map)}"
                addButton(label) { showRoadOverview(road) }
            }
        }
    }

    private fun showRoadOverview(road: Road) {
        val map = loadedMap ?: return
        val e = ExplorerEngine(map)
        e.start(road, 0.0)
        val endpoints = endpointDescription(road, map)
        val places = e.nearbyPlaces(100_000.0).filter { it.roadDistance <= 40.0 }.sortedBy { it.alongDistance }
        val orderedPlaces = places.take(40)
        page("Road Overview", "Back to Roads", { chooseRoad() }) {
            addText("Road: ${road.name}")
            addText("Mapped length: ${road.lengthMeters.roundToInt()} metres. Road type: ${road.kind}.")
            addText(endpoints)
            addText("Road sections follow the stored OpenStreetMap geometry. Crossings are treated as connections only when the data contains a shared road node.")
            val connections = connectionsAtEitherEnd(road, map)
            addText("Connected roads at the ends: ${if (connections.isEmpty()) "No mapped road connections found." else connections.joinToString("; ")}")
            addText("Mapped places ordered from the first endpoint:")
            if (orderedPlaces.isEmpty()) addText("No mapped places were found along this road.")
            orderedPlaces.forEach { item -> addText("${item.place.name}, ${item.place.category}, about ${item.alongDistance.roundToInt()} metres from the first endpoint, approximately ${item.roadDistance.roundToInt()} metres from the road geometry. ${if (item.place.entranceVerified) "An entrance is mapped." else "Nearby position; an entrance on this road is not verified."}") }
            addButton("Start from first endpoint") { launchOnRoad(road, 0.0) }
            addButton("Start from other endpoint") { launchOnRoad(road, road.lengthMeters) }
            addButton("Choose a position along this road") { chooseRoadPosition(road) }
        }
    }

    private fun endpointDescription(road: Road, map: MapData): String {
        val firstBearing = bearingDegrees(road.points.first(), road.points.last())
        val otherBearing = (firstBearing + 180) % 360.0
        fun details(at: GeoPoint, id: Long?): String {
            val connected = if (id == null) emptyList() else map.roads.filter { it.id != road.id && id in listOf(it.startNode, it.endNode) }.map { it.name }.distinct()
            val landmark = map.places.minByOrNull { distanceMeters(it.point, at) }?.takeIf { distanceMeters(it.point, at) < 100 }?.let { ", near ${it.name}" }.orEmpty()
            val junction = if (connected.isNotEmpty()) ", junction with ${connected.take(3).joinToString()}" else ", no mapped connecting road"
            return "$junction$landmark"
        }
        return "First endpoint is approximately ${compassName(firstBearing)} from the other endpoint${details(road.points.first(), road.startNode)}. Other endpoint is approximately ${compassName(otherBearing)}${details(road.points.last(), road.endNode)}. The road geometry may curve or branch."
    }

    private fun connectionsAtEitherEnd(road: Road, map: MapData): List<String> = listOfNotNull(road.startNode, road.endNode)
        .flatMap { id -> map.roads.filter { it.id != road.id && id in listOf(it.startNode, it.endNode) }.map { it.name } }.distinct()

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
            val search = addEdit("Search places", filterText)
            val results = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(results)
            fun populate() {
                results.removeAllViews()
                val matches = map.places.filter { (it.name + " " + it.category).contains(search.text.toString(), true) }.take(100)
                matches.forEach { place ->
                    val b = Button(this@MainActivity).apply { text = "${place.name}, ${place.category}"; contentDescription = "Place ${place.name}, ${place.category}"; isAllCaps = false; minHeight = dp(58); setOnClickListener { showPlaceDetail(place) } }
                    results.addView(b, LinearLayout.LayoutParams(-1, -2))
                }
                statusView?.text = "${map.places.size} mapped places available offline. ${matches.size} results shown."
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = populate()
                override fun afterTextChanged(s: Editable?) = Unit
            })
            addStatus("${map.places.size} mapped places available offline.")
            populate()
        }
    }

    private fun showPlaceDetail(place: Place) {
        val map = loadedMap ?: return
        val nearest = ExplorerEngine(map).nearestRoad(place.point)
        page("Place Details", "Back to Places", { showPlaceList() }) {
            addText("${place.name}. Category: ${place.category}.")
            addText(if (place.entranceVerified) "OpenStreetMap includes an entrance tag at this position." else "This is a mapped place position. Its building entrance and which road it faces are not verified.")
            addText("Position: ${place.point.lat}, ${place.point.lon}.")
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

    private fun showExplore() {
        val e = engine ?: return showHome()
        val map = loadedMap ?: return showHome()
        page("Exploration", "Back to ${map.islandName} Roads", { chooseRoad() }) {
            val current = e.road
            addText("Island: ${map.islandName}. Road: ${current?.name ?: "none"}.")
            if (!map.connectivityVerified) addText("This imported backup does not include OSM node IDs. Possible road connections are estimated from identical coordinates.")
            val live = addStatus(e.whereAmI())
            val pos = e.position()
            addText(pos?.let { "Current virtual position: ${"%.6f".format(Locale.US, it.lat)}, ${"%.6f".format(Locale.US, it.lon)}." }.orEmpty())
            addText("Virtual distance travelled: ${e.travelledMeters.roundToInt()} metres. Junctions visited: ${e.visitedJunctions}. Places passed: ${e.visitedPlaceCount()}.")
            addText("Step size: ${stepMetres()} metres. Move backward follows the road behind you and keeps your facing direction unchanged.")
            addButton("Move Forward") { moveForward(live) }
            addButton("Move Backward") { moveBackward(live) }
            addButton("Next Place") { moveToNextPlace(live) }
            addButton("Previous Place") { moveToPreviousPlace(live) }
            addButton("Next Junction") { moveToJunction(live) }
            addButton("Previous Junction") { e.turnAround(); moveToJunction(live) }
            addButton("Look Around") { showLookAround() }
            addButton("Where Am I?") { announce(e.whereAmI(), live) }
            addButton("Turn Around") { e.turnAround(); changed("You turned around. You are facing ${e.facing()}.", live) }
            val atEnd = e.atRoadEnd()
            val branches = e.connections()
            if (atEnd) {
                val junctionLabel = if (map.connectivityVerified) "Junction" else "Possible junction (estimated from shared coordinates)"
                addText(if (branches.isEmpty()) "Road endpoint. No connected road is mapped here." else "$junctionLabel. ${branches.size} connected roads are listed.")
                if (branches.isNotEmpty()) {
                    addButton("Turn Left") { chooseBranch("left", live) }
                    addButton("Continue Straight") { chooseBranch("straight", live) }
                    addButton("Turn Right") { chooseBranch("right", live) }
                    addButton("Choose Connected Road") { chooseConnection(branches) }
                    branches.forEach { c -> addText("Connected road: ${c.road.name}, ${compassName(c.bearingOut)}, relative direction ${c.turn.roundToInt()} degrees.") }
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
        val amount = e.move(stepMetres())
        if (amount == 0.0) { announce("You are already at the road endpoint.", status); return }
        val ending = if (e.atRoadEnd()) {
            val branches = e.connections()
            if (branches.isEmpty()) "You reached the end of the road." else if (loadedMap?.connectivityVerified == true)
                "You reached a junction. ${branches.size} connected roads. ${branches.joinToString { it.road.name }}. Choose a road to continue."
            else "Possible junction based on shared map coordinates. ${branches.joinToString { it.road.name }} may connect here. Choose only if this imported connection seems correct."
        } else "Moved forward ${amount.roundToInt()} metres."
        checkNearbyPlaces(status)
        changed(ending, status)
    }

    private fun moveBackward(status: TextView?) {
        val e = engine ?: return
        val amount = e.moveBackward(stepMetres())
        checkNearbyPlaces(status)
        changed(if (amount == 0.0) "No road geometry behind you." else "Moved backward ${amount.roundToInt()} metres. You are still facing ${e.facing()}.", status)
    }

    private fun moveToJunction(status: TextView?) {
        val e = engine ?: return
        val dist = e.remainingToRoadEnd()
        if (dist < 0.5) {
            val options = e.connections()
            announce(if (options.isEmpty()) "Road endpoint. No connecting road is mapped." else if (loadedMap?.connectivityVerified == true)
                "Junction reached. Connected roads: ${options.joinToString { it.road.name }}."
            else "Possible junction reached. These roads may connect here based on shared coordinates: ${options.joinToString { it.road.name }}.", status)
            return
        }
        val moved = e.move(dist)
        val options = e.connections()
        changed(if (options.isEmpty()) "Reached the end of ${e.road?.name}. No connected road is mapped." else if (loadedMap?.connectivityVerified == true)
            "Reached a junction on ${e.road?.name}. ${options.size} connected roads: ${options.joinToString { it.road.name }}."
        else "Possible junction on ${e.road?.name}, inferred from shared coordinates. ${options.joinToString { it.road.name }} may connect here.", status)
    }

    private fun chooseBranch(turn: String, status: TextView?) {
        val e = engine ?: return
        val selected = e.chooseTurn(turn)
        if (selected == null) { announce("No mapped ${turn} turn is available here. Choose one of the connected roads.", status); return }
        changed(if (loadedMap?.connectivityVerified == true) "Turned $turn onto ${selected.road.name}. Facing ${e.facing()}."
            else "Followed the estimated $turn connection onto ${selected.road.name}. The backup does not verify this junction. Facing ${e.facing()}.", status)
    }

    private fun chooseConnection(options: List<RoadConnection>) {
        val map = loadedMap ?: return
        page("Choose Connected Road", "Back to Exploration", ::showExplore) {
            addText(if (map.connectivityVerified) "Choose one of the roads connected at this junction."
                else "These possible connections are estimated from shared coordinates in the imported backup.")
            options.forEach { option -> addButton("${option.road.name}, ${compassName(option.bearingOut)}") {
                engine?.takeConnection(option)
                showExplore()
            } }
        }
    }

    private fun moveToNextPlace(status: TextView?) {
        val e = engine ?: return
        val next = e.nextPlace()
        if (next == null) { announce("No mapped place ahead on this road within 150 metres.", status); return }
        val moved = e.move(abs(next.alongDistance - e.progressMeters))
        e.markVisited(next.place)
        changed("Reached ${next.place.name}, ${next.place.category}, about ${next.distance.roundToInt()} metres from the mapped point. ${sideAnnouncement(next)}", status)
    }

    private fun moveToPreviousPlace(status: TextView?) {
        val e = engine ?: return
        val direction = -e.travelSign
        val place = e.nearbyPlaces(150.0).filter { (it.alongDistance - e.progressMeters) * direction >= 1.0 }
            .minByOrNull { abs(it.alongDistance - e.progressMeters) }
        if (place == null) { announce("No mapped place behind you on this road within 150 metres.", status); return }
        e.moveBackward(abs(place.alongDistance - e.progressMeters))
        e.markVisited(place.place)
        changed("Reached ${place.place.name}. ${sideAnnouncement(place)}", status)
    }

    private fun showLookAround() {
        val e = engine ?: return
        val radius = prefs.getInt("radius", 100).toDouble()
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
        page("Resume Exploration", "Back to Main Menu", ::showHome) { addText("Restoring your saved island and walking position…", live = true) }
        val generation = screenGeneration
        Thread {
            val loaded = runCatching { store.load(file) }
            mainHandler.post {
                if (screenGeneration != generation) return@post
                loaded.onFailure { Toast.makeText(this, "Saved map could not be opened. ${it.message ?: ""}", Toast.LENGTH_LONG).show(); showHome() }
                    .onSuccess { map ->
                        loadedMap = map; loadedFile = file
                        val road = map.roads.firstOrNull { it.id == prefs.getString("session_road", null) }
                        if (road == null) { chooseRoad(); return@onSuccess }
                        engine = ExplorerEngine(map).apply {
                            restore(road, prefs.getFloat("session_progress", 0f).toDouble(), prefs.getInt("session_sign", 1),
                                prefs.getFloat("session_facing", 0f).toDouble(), prefs.getFloat("session_distance", 0f).toDouble(),
                                prefs.getInt("session_junctions", 0))
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

