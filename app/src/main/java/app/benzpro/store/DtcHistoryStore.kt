package app.benzpro.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoricDtc(
    val code: String,
    val title: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val timesSeen: Int,
)

class DtcHistoryStore(context: Context) {
    private val file = File(context.filesDir, "dtc_history.json")

    fun load(vehicleId: String): List<HistoricDtc> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray(vehicleId) ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        HistoricDtc(
                            code = obj.getString("code"),
                            title = obj.optString("title"),
                            firstSeenMs = obj.optLong("firstSeenMs"),
                            lastSeenMs = obj.optLong("lastSeenMs"),
                            timesSeen = obj.optInt("timesSeen", 1),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun merge(vehicleId: String, seen: List<Pair<String, String>>): List<HistoricDtc> {
        if (seen.isEmpty()) return load(vehicleId)
        val now = System.currentTimeMillis()
        val existing = load(vehicleId).associateBy { it.code }.toMutableMap()
        seen.forEach { (code, title) ->
            val prev = existing[code]
            existing[code] = if (prev == null) {
                HistoricDtc(code, title, now, now, 1)
            } else {
                prev.copy(
                    title = title.ifBlank { prev.title },
                    lastSeenMs = now,
                    timesSeen = prev.timesSeen + 1,
                )
            }
        }
        val list = existing.values.sortedByDescending { it.lastSeenMs }
        save(vehicleId, list)
        return list
    }

    private fun save(vehicleId: String, list: List<HistoricDtc>) {
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
        list.forEach { item ->
            array.put(
                JSONObject()
                    .put("code", item.code)
                    .put("title", item.title)
                    .put("firstSeenMs", item.firstSeenMs)
                    .put("lastSeenMs", item.lastSeenMs)
                    .put("timesSeen", item.timesSeen),
            )
        }
        root.put(vehicleId, array)
        file.writeText(root.toString())
    }
}
