package app.benzpro.obd

data class Pid(
    val id: Int,
    val label: String,
    val unit: String,
    val bytes: Int,
    val defaultSelected: Boolean = false,
    val decode: (List<Int>) -> String,
)

data class DtcEntry(
    val code: String,
    val title: String,
    val status: String,
    val freezeFrame: String? = null,
    val advice: String? = null,
    val ftb: String? = null,
)
