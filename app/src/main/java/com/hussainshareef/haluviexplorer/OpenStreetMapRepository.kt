package com.hussainshareef.haluviexplorer

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

data class IslandArea(val displayName: String, val shortName: String, val south: Double, val west: Double, val north: Double, val east: Double)

class OpenStreetMapRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastGeocodeAt = 0L

    fun searchIsland(query: String, callback: (Result<List<IslandArea>>) -> Unit) = executor.execute {
        try {
            val waitMs = 1_100L - (System.currentTimeMillis() - lastGeocodeAt)
            if (waitMs > 0) Thread.sleep(waitMs)
            val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&countrycodes=mv&limit=8&q=" + enc(query)
            val body = get(url)
            lastGeocodeAt = System.currentTimeMillis()
            val array = JSONArray(body)
            val results = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val box = item.optJSONArray("boundingbox") ?: continue
                    if (box.length() < 4) continue
                    val south = box.optString(0).toDoubleOrNull() ?: continue
                    val north = box.optString(1).toDoubleOrNull() ?: continue
                    val west = box.optString(2).toDoubleOrNull() ?: continue
                    val east = box.optString(3).toDoubleOrNull() ?: continue
                    if (north - south > 0.20 || east - west > 0.20) continue // Avoid unexpectedly large area downloads.
                    val name = item.optString("name").ifBlank { item.optString("display_name").substringBefore(",") }
                    if (name.isNotBlank()) add(IslandArea(item.optString("display_name", name), name, south, west, north, east))
                }
            }.distinctBy { it.shortName.lowercase() to listOf(it.south, it.west, it.north, it.east) }.take(5)
            if (results.isEmpty()) throw IllegalStateException("No small Maldives island result found. Try a more specific island name.")
            main.post { callback(Result.success(results)) }
        } catch (t: Throwable) { main.post { callback(Result.failure(t)) } }
    }

    fun download(area: IslandArea, callback: (Result<MapData>) -> Unit) = executor.execute {
        try {
            val box = "${area.south},${area.west},${area.north},${area.east}"
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
            var response: String? = null
            var failure: Throwable? = null
            for (endpoint in listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")) {
                try {
                    response = postForm(endpoint, "data=" + enc(query))
                    break
                } catch (t: Throwable) { failure = t }
            }
            val data = response?.let { MapData.fromOverpass(area.shortName, it) } ?: throw (failure ?: IllegalStateException("Map download failed"))
            if (data.roads.isEmpty()) throw IllegalStateException("The map source returned no road geometry for ${area.shortName}.")
            main.post { callback(Result.success(data)) }
        } catch (t: Throwable) { main.post { callback(Result.failure(t)) } }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun get(url: String): String = request(url, null)
    private fun postForm(url: String, form: String): String = request(url, form)

    private fun request(urlString: String, body: String?): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 25_000
        connection.readTimeout = 120_000
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("User-Agent", "HaluviExplorer/0.1.0 (Android offline virtual exploration)")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.use { input -> BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Map service returned HTTP $code${if (text.isBlank()) "" else ": ${text.take(160)}"}")
        return text
    }
}

