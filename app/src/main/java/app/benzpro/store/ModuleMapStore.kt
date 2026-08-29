package app.benzpro.store

import android.content.Context
import org.json.JSONObject
import java.io.File

data class SavedModuleAddress(
    val moduleId: String,
    val scheme: String,
    val requestHeader: String,
    val receiveAddress: String,
)

class ModuleMapStore(context: Context) {
    private val file = File(context.filesDir, "modules.json")

    fun load(vehicleId: String): Map<String, SavedModuleAddress> {
        if (!file.exists()) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val obj = root.optJSONObject(vehicleId) ?: return emptyMap()
            buildMap {
                obj.keys().forEach { key ->
                    val item = obj.getJSONObject(key)
                    put(
                        key,
                        SavedModuleAddress(
                            moduleId = key,
                            scheme = item.getString("scheme"),
                            requestHeader = item.getString("requestHeader"),
                            receiveAddress = item.optString("receiveAddress"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(vehicleId: String, map: Map<String, SavedModuleAddress>) {
        val root = if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        val obj = JSONObject()
        map.forEach { (id, addr) ->
            obj.put(
                id,
                JSONObject()
                    .put("scheme", addr.scheme)
                    .put("requestHeader", addr.requestHeader)
                    .put("receiveAddress", addr.receiveAddress),
            )
        }
        root.put(vehicleId, obj)
        file.writeText(root.toString())
    }
}
