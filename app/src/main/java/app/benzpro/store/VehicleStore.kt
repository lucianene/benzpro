package app.benzpro.store

import android.content.Context
import org.json.JSONObject
import java.io.File

class VehicleStore(context: Context) {
    private val file = File(context.filesDir, "vehicles.json")

    fun loadSelectedId(defaultId: String): String {
        if (!file.exists()) return defaultId
        return try {
            JSONObject(file.readText()).optString("selectedId", defaultId).ifBlank { defaultId }
        } catch (_: Exception) {
            defaultId
        }
    }

    fun saveSelectedId(id: String) {
        file.writeText(JSONObject().put("selectedId", id).toString())
    }
}
