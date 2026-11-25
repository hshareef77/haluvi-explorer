package com.hussainshareef.haluviexplorer

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class MapDownloadProgress(val stage: String, val bytesRead: Long = 0L, val totalBytes: Long = -1L)

class MapDownloadHandle {
    @Volatile internal var cancelled = false
    @Volatile internal var connection: HttpURLConnection? = null
    fun cancel() { cancelled = true; connection?.disconnect() }
}

private class NoRoadGeometryException(message: String) : IllegalStateException(message)

data class IslandArea(
    val displayName: String,
    val shortName: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val approximate: Boolean = false,
    /** Expanded fallback bounds around the official Navigation island catalog point. */
    val fallbackBounds: List<Double> = emptyList()
)

class OpenStreetMapRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastGeocodeAt = 0L

    fun searchIsland(query: String, callback: (Result<List<IslandArea>>) -> Unit) = search(query, null, null, callback)

    /** Uses the Navigation catalog point to constrain lookup to the chosen island's settlement. */
    fun searchIsland(island: IslandEntry, atoll: AtollEntry, callback: (Result<List<IslandArea>>) -> Unit) =
        search("${island.name}, ${atoll.fullname}", island, atoll, callback)

    private fun search(query: String, selectedIsland: IslandEntry?, atoll: AtollEntry?, callback: (Result<List<IslandArea>>) -> Unit) = executor.execute {
        try {
            val waitMs = 1_100L - (System.currentTimeMillis() - lastGeocodeAt)
            if (waitMs > 0) Thread.sleep(waitMs)
            val center = selectedIsland
            val viewbox = center?.let { "&viewbox=${it.longitude - 0.08},${it.latitude + 0.08},${it.longitude + 0.08},${it.latitude - 0.08}&bounded=1" }.orEmpty()
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=mv&featureType=settlement&limit=8$viewbox&q=" + enc(query)
            val body = get(url)
            lastGeocodeAt = System.currentTimeMillis()
            val array = JSONArray(body)
            val results = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (!isIslandOrSettlementResult(item)) continue
                    val box = item.optJSONArray("boundingbox") ?: continue
                    if (box.length() < 4) continue
                    val south = box.optString(0).toDoubleOrNull() ?: continue
                    val north = box.optString(1).toDoubleOrNull() ?: continue
                    val west = box.optString(2).toDoubleOrNull() ?: continue
                    val east = box.optString(3).toDoubleOrNull() ?: continue
                    if (north - south > 0.20 || east - west > 0.20) continue // Avoid unexpectedly large area downloads.
                    if (center != null) {
                        val lat = item.optDouble("lat", Double.NaN)
                        val lon = item.optDouble("lon", Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite() || distanceMeters(GeoPoint(center.latitude, center.longitude), GeoPoint(lat, lon)) > 4_500.0) continue
                    }
                    val name = item.optString("name").ifBlank { item.optString("display_name").substringBefore(",") }
                    if (name.isNotBlank()) {
                        if (center != null && normalizedPlaceName(name) != normalizedPlaceName(center.name)) continue
                        val label = if (center != null && atoll != null) "${center.name}, ${atoll.fullname} atoll" else item.optString("display_name", name)
                        val fallback = center?.let { listOf(it.latitude - 0.034, it.longitude - 0.034, it.latitude + 0.034, it.longitude + 0.034) }.orEmpty()
                        add(IslandArea(label, name, south, west, north, east, fallbackBounds = fallback))
                    }
                }
            }.distinctBy { it.shortName.lowercase() to listOf(it.south, it.west, it.north, it.east) }.take(5)
            val finalResults = if (results.isNotEmpty()) results else if (center != null && atoll != null) {
                // Keep a selected catalog island usable even when OSM has no settlement polygon.
                // This is explicitly labeled approximate rather than substituting a nearby building.
                val latRadius = 0.03
                val lonRadius = 0.03 / kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(0.5)
                listOf(IslandArea("${center.name}, ${atoll.fullname} atoll (approximate area around island centre)", center.name,
                    center.latitude - latRadius, center.longitude - lonRadius,
                    center.latitude + latRadius, center.longitude + lonRadius, approximate = true,
                    fallbackBounds = listOf(center.latitude - 0.034, center.longitude - 0.034, center.latitude + 0.034, center.longitude + 0.034)))
            } else emptyList()
            if (finalResults.isEmpty()) throw IllegalStateException("No Maldives island or settlement result found. Try selecting the island from its atoll list.")
            main.post { callback(Result.success(finalResults)) }
        } catch (t: Throwable) { main.post { callback(Result.failure(t)) } }
    }

    fun download(area: IslandArea, onProgress: (MapDownloadProgress) -> Unit = {}, callback: (Result<MapData>) -> Unit): MapDownloadHandle {
        val handle = MapDownloadHandle()
        executor.execute {
        try {
            check(!handle.cancelled) { "Download cancelled." }
            val selectedAttempt = runCatching { downloadBox(area, area.south, area.west, area.north, area.east, handle, onProgress) }
            var data = selectedAttempt.getOrNull()
            if (data == null && selectedAttempt.exceptionOrNull() !is NoRoadGeometryException) throw selectedAttempt.exceptionOrNull() ?: IllegalStateException("Map download failed.")
            if (data == null && area.fallbackBounds.size == 4) {
                check(!handle.cancelled) { "Download cancelled." }
                main.post { onProgress(MapDownloadProgress("No roads in the selected boundary. Retrying with the expanded island area.")) }
                val b = area.fallbackBounds
                data = downloadBox(area, b[0], b[1], b[2], b[3], handle, onProgress)
            }
            if (data == null || data.roads.isEmpty()) throw IllegalStateException("No recognized road geometry was returned for ${area.shortName} after checking available map servers and island bounds.")
            val completed = data
            check(!handle.cancelled) { "Download cancelled." }
            main.post { onProgress(MapDownloadProgress("Map data processed: ${completed.roads.size} road sections and ${completed.places.size} places")) }
            main.post { callback(Result.success(completed)) }
        } catch (t: Throwable) { main.post { callback(Result.failure(if (handle.cancelled) IllegalStateException("Download cancelled.") else t)) } }
        }
        return handle
    }

    private fun downloadBox(area: IslandArea, south: Double, west: Double, north: Double, east: Double,
                            handle: MapDownloadHandle, onProgress: (MapDownloadProgress) -> Unit): MapData {
        val box = "$south,$west,$north,$east"
        val query = """
            [out:json][timeout:90];
            (
              way[highway]($box);
              nwr[amenity]($box);
              nwr[shop]($box);
              nwr[tourism]($box);
              nwr[leisure]($box);
              nwr[healthcare]($box);
              nwr[office]($box);
              nwr[education]($box);
            );
            out body geom center;
        """.trimIndent()
        var lastFailure: Throwable? = null
        var gotResponseWithoutRoads = false
        val servers = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter")
        servers.forEachIndexed { index, endpoint ->
            try {
                check(!handle.cancelled) { "Download cancelled." }
                main.post { onProgress(MapDownloadProgress("Connecting to map server ${index + 1} of ${servers.size} for ${area.shortName}")) }
                val response = postForm(endpoint, "data=" + enc(query), handle) { bytes, total ->
                    main.post { onProgress(MapDownloadProgress("Downloading map data for ${area.shortName}", bytes, total)) }
                }
                main.post { onProgress(MapDownloadProgress("Processing places and road geometry")) }
                val data = MapData.fromOverpass(area.shortName, response)
                if (data.roads.isNotEmpty()) return data
                gotResponseWithoutRoads = true
            } catch (t: Throwable) {
                lastFailure = t
                if (handle.cancelled) throw t
            }
        }
        if (gotResponseWithoutRoads) throw NoRoadGeometryException("Map servers returned data without recognized road geometry.")
        throw lastFailure ?: IllegalStateException("Map download failed.")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun get(url: String): String = request(url, null)
    private fun postForm(url: String, form: String, handle: MapDownloadHandle, progress: (Long, Long) -> Unit): String = request(url, form, handle, progress)

    private fun request(urlString: String, body: String?, handle: MapDownloadHandle? = null, progress: ((Long, Long) -> Unit)? = null): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        handle?.connection = connection
        connection.connectTimeout = 10_000
        connection.readTimeout = 45_000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("User-Agent", "HaluviExplorer/0.1.0 (Android offline virtual exploration)")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        if (handle?.cancelled == true) throw IllegalStateException("Download cancelled.")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var bytes = 0L
            var lastReported = 0L
            val total = connection.contentLengthLong
            while (true) {
                if (handle?.cancelled == true) throw IllegalStateException("Download cancelled.")
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count); bytes += count
                if (bytes - lastReported >= 256 * 1024 || (total > 0 && bytes == total)) { progress?.invoke(bytes, total); lastReported = bytes }
            }
            progress?.invoke(bytes, total)
            String(output.toByteArray(), StandardCharsets.UTF_8)
        }.orEmpty()
        connection.disconnect()
        if (handle?.connection === connection) handle.connection = null
        if (code !in 200..299) throw IllegalStateException("Map service returned HTTP $code${if (text.isBlank()) "" else ": ${text.take(160)}"}")
        return text
    }
}

internal fun isIslandOrSettlementResult(item: org.json.JSONObject): Boolean {
    val category = item.optString("category", item.optString("class")).lowercase()
    val type = item.optString("type").lowercase()
    return category == "place" && type in setOf("island", "city", "town", "village", "hamlet", "locality", "isolated_dwelling", "suburb", "neighbourhood", "quarter", "borough")
}

private fun normalizedPlaceName(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }

