package app.benzpro.store

import android.content.Context
import org.json.JSONObject
import java.io.File

data class SavedAdapter(
    val address: String,
    val name: String,
    val transportKind: String = "spp",
)

class AdapterStore(context: Context) {
    private val file = File(context.filesDir, "adapter.json")

    fun load(): SavedAdapter? {
        if (!file.exists()) return null
        return try {
            val root = JSONObject(file.readText())
            SavedAdapter(
                address = root.getString("address"),
                name = root.optString("name"),
                transportKind = root.optString("transportKind", "spp"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(adapter: SavedAdapter) {
        val root = JSONObject()
            .put("address", adapter.address)
            .put("name", adapter.name)
            .put("transportKind", adapter.transportKind)
        file.writeText(root.toString())
    }
}
