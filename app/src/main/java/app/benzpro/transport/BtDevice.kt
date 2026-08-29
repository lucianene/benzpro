package app.benzpro.transport

data class BtDevice(
    val address: String,
    val name: String,
    val bonded: Boolean,
)

fun looksLikeObdAdapter(name: String): Boolean {
    val n = name.uppercase()
    return listOf("V-GATE", "VGATE", "ICAR", "OBD", "ELM", "V-LINK", "VLINK", "OBDII", "OBD2")
        .any { n.contains(it) }
}
