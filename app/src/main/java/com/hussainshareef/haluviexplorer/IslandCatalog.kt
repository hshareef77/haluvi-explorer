package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONArray
import java.text.Normalizer

data class AtollEntry(val code: String, val fullname: String, val islands: List<IslandEntry>)
data class IslandEntry(val name: String, val latitude: Double, val longitude: Double)

object IslandCatalog {
    fun load(context: Context): List<AtollEntry> {
        val json = context.assets.open("maldives_islands.json").bufferedReader().use { it.readText() }
        val root = JSONArray(json)
        return buildList {
            for (i in 0 until root.length()) {
                val atoll = root.getJSONObject(i)
                val islands = atoll.getJSONArray("islands")
                add(AtollEntry(atoll.optString("code"), atoll.optString("fullname").ifBlank { atoll.optString("name") }, buildList {
                    for (j in 0 until islands.length()) {
                        val island = islands.getJSONObject(j)
                        add(IslandEntry(island.optString("n"), island.optDouble("lat"), island.optDouble("lon")))
                    }
                }))
            }
        }
    }
}

/** Matches saved maps by island identity, never by a partial substring (e.g. Male inside Hulhumale). */
internal fun storedMapMatchesIsland(summary: StoredMapSummary, atoll: AtollEntry, island: IslandEntry): Boolean {
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase().filter { it.isLetterOrDigit() }
    val wanted = normalize(island.name)
    val atollName = normalize(atoll.fullname)
    val atollCode = normalize(atoll.code)
    val keyParts = summary.sourceIslandKey.split(':').filter { it.isNotBlank() }
    if (keyParts.size >= 2 && normalize(keyParts[0]) in setOf(atollCode, atollName)) {
        // A navigation backup key such as K:Hulhumale is authoritative and must not fall through to name matching.
        return normalize(keyParts[1]) == wanted
    }
    val labels = buildList {
        add(summary.islandName.substringBefore('(').trim())
        addAll(summary.islandName.substringBefore('(').split(',', ';'))
        add(summary.file.nameWithoutExtension)
    }.map(::normalize)
    return labels.any { label ->
        label == wanted ||
            label.removePrefix(atollCode) == wanted ||
            label.removePrefix(atollName) == wanted ||
            label.removePrefix("${atollName}atoll") == wanted ||
            label.removeSuffix(atollCode) == wanted ||
            label.removeSuffix(atollName) == wanted ||
            label.removeSuffix("${atollName}atoll") == wanted
    }
}
