package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONArray

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
