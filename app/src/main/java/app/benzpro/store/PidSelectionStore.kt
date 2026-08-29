package app.benzpro.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PidSelectionStore(context: Context) {
    private val file = File(context.filesDir, "pids.json")

    fun load(vehicleId: String): Set<Int>? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(vehicleId) ?: return null
            buildSet {
                for (i in 0 until array.length()) add(array.getInt(i))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(vehicleId: String, ids: Set<Int>) {
        val root = if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        val array = JSONArray()
        ids.sorted().forEach { array.put(it) }
        root.put(vehicleId, array)
        file.writeText(root.toString())
    }
}
