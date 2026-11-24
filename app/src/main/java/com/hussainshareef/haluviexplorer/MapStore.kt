package com.hussainshareef.haluviexplorer

import android.content.Context
import org.json.JSONObject
import java.io.File

class MapStore(context: Context) {
    private val directory = File(context.filesDir, "island-maps").apply { mkdirs() }
    fun save(map: MapData) { File(directory, "${map.saveName()}.json").writeText(map.toJson().toString()) }
    fun load(file: File): MapData = MapData.fromJson(JSONObject(file.readText()))
    fun all(): List<File> = directory.listFiles { file -> file.isFile && file.extension == "json" }?.sortedBy { it.nameWithoutExtension } ?: emptyList()
    fun delete(file: File): Boolean = file.delete()
}

