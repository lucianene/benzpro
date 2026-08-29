package app.benzpro.obd

object DtcDecoder {
    fun fromBytes(payload: List<Int>): List<String> {
        if (payload.size < 2) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < payload.size) {
            val a = payload[i]
            val b = payload[i + 1]
            i += 2
            if (a == 0 && b == 0) continue
            out.add(format(a, b))
        }
        return out
    }

    fun format(a: Int, b: Int): String {
        val type = (a shr 6) and 0x03
        val letter = when (type) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val d1 = (a shr 4) and 0x03
        val d2 = a and 0x0F
        val d3 = (b shr 4) and 0x0F
        val d4 = b and 0x0F
        return "$letter$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
    }

    fun saeTitle(code: String): String = SaeTitles[code] ?: "Manufacturer / unknown"

    private val SaeTitles = mapOf(
        "P0000" to "No DTC",
        "P0087" to "Fuel rail pressure too low",
        "P0088" to "Fuel rail pressure too high",
        "P0100" to "MAF circuit",
        "P0101" to "MAF range/performance",
        "P0110" to "IAT circuit",
        "P0115" to "ECT circuit",
        "P0120" to "TPS circuit",
        "P0190" to "Rail pressure sensor circuit",
        "P0200" to "Injector circuit",
        "P0234" to "Overboost",
        "P0299" to "Underboost",
        "P0380" to "Glow plug circuit",
        "P0401" to "EGR insufficient flow",
        "P0402" to "EGR excessive flow",
        "P0470" to "Exhaust pressure sensor",
        "P0670" to "Glow control module",
        "P2002" to "DPF efficiency below threshold",
        "P2263" to "Turbo/supercharger boost",
        "P242F" to "DPF restriction",
        "P2452" to "DPF pressure sensor",
        "P2453" to "DPF pressure sensor range",
        "P2458" to "DPF regeneration duration",
        "P2459" to "DPF regeneration frequency",
        "P2463" to "DPF soot accumulation",
        "P2A00" to "O2 sensor circuit range",
    )
}
