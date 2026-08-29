package app.benzpro.obd

object PidCatalog {
    fun a(bytes: List<Int>) = bytes.getOrElse(0) { 0 }
    fun b(bytes: List<Int>) = bytes.getOrElse(1) { 0 }
    fun c(bytes: List<Int>) = bytes.getOrElse(2) { 0 }
    fun d(bytes: List<Int>) = bytes.getOrElse(3) { 0 }
    fun ab(bytes: List<Int>) = (a(bytes) shl 8) + b(bytes)
    fun cd(bytes: List<Int>) = (c(bytes) shl 8) + d(bytes)

    val all: List<Pid> = listOf(
        pid(0x03, "Fuel status", "", 1, false) { fuelStatus(a(it)) },
        pid(0x04, "Engine load", "%", 1, true) { pct(a(it)) },
        pid(0x05, "Coolant", "°C", 1, true) { temp(a(it)) },
        pid(0x06, "STFT", "%", 1, false) { trim(a(it)) },
        pid(0x07, "LTFT", "%", 1, false) { trim(a(it)) },
        pid(0x0A, "Fuel pressure", "kPa", 1, false) { "${a(it) * 3}" },
        pid(0x0B, "MAP / boost", "kPa", 1, true) { "${a(it)}" },
        pid(0x0C, "RPM", "rpm", 2, true) { "${ab(it) / 4}" },
        pid(0x0D, "Speed", "km/h", 1, true) { "${a(it)}" },
        pid(0x0E, "Timing advance", "°", 1, false) { "${(a(it) - 128) / 2.0}" },
        pid(0x0F, "Intake air", "°C", 1, true) { temp(a(it)) },
        pid(0x10, "MAF", "g/s", 2, false) { "%.2f".format(ab(it) / 100.0) },
        pid(0x11, "Throttle", "%", 1, true) { pct(a(it)) },
        pid(0x1C, "OBD standard", "", 1, false) { obdStandard(a(it)) },
        pid(0x1F, "Run time", "s", 2, false) { "${ab(it)}" },
        pid(0x21, "Distance with MIL", "km", 2, false) { "${ab(it)}" },
        pid(0x22, "Rail vs manifold", "kPa", 2, false) { "%.1f".format(ab(it) * 0.079) },
        pid(0x23, "Rail pressure", "kPa", 2, true) { "${ab(it) * 10}" },
        pid(0x2C, "Commanded EGR", "%", 1, true) { pct(a(it)) },
        pid(0x2D, "EGR error", "%", 1, true) { trim(a(it)) },
        pid(0x2E, "Evap purge", "%", 1, false) { pct(a(it)) },
        pid(0x2F, "Fuel level", "%", 1, false) { pct(a(it)) },
        pid(0x30, "Warm-ups since clear", "", 1, false) { "${a(it)}" },
        pid(0x31, "Distance since clear", "km", 2, false) { "${ab(it)}" },
        pid(0x33, "Baro", "kPa", 1, true) { "${a(it)}" },
        pid(0x3C, "Cat temp B1S1", "°C", 2, false) { catTemp(ab(it)) },
        pid(0x3D, "Cat temp B2S1", "°C", 2, false) { catTemp(ab(it)) },
        pid(0x3E, "Cat temp B1S2", "°C", 2, false) { catTemp(ab(it)) },
        pid(0x3F, "Cat temp B2S2", "°C", 2, false) { catTemp(ab(it)) },
        pid(0x42, "Module voltage", "V", 2, true) { "%.2f".format(ab(it) / 1000.0) },
        pid(0x43, "Absolute load", "%", 2, false) { "${ab(it) * 100 / 255}" },
        pid(0x44, "Commanded AFR", "", 2, false) { "%.3f".format(ab(it) / 32768.0) },
        pid(0x45, "Relative TPS", "%", 1, false) { pct(a(it)) },
        pid(0x46, "Ambient air", "°C", 1, false) { temp(a(it)) },
        pid(0x47, "Abs throttle B", "%", 1, false) { pct(a(it)) },
        pid(0x49, "Pedal D", "%", 1, false) { pct(a(it)) },
        pid(0x4A, "Pedal E", "%", 1, false) { pct(a(it)) },
        pid(0x4C, "Commanded throttle", "%", 1, false) { pct(a(it)) },
        pid(0x51, "Fuel type", "", 1, false) { fuelType(a(it)) },
        pid(0x59, "Rail abs pressure", "kPa", 2, false) { "${ab(it) * 10}" },
        pid(0x5A, "Relative pedal", "%", 1, false) { pct(a(it)) },
        pid(0x5C, "Oil temp", "°C", 1, true) { temp(a(it)) },
        pid(0x5D, "Injection timing", "°", 2, false) { "%.1f".format(ab(it) / 128.0 - 210) },
        pid(0x5E, "Fuel rate", "L/h", 2, true) { "%.2f".format(ab(it) * 0.05) },
        pid(0x61, "Demand torque", "%", 1, false) { "${a(it) - 125}" },
        pid(0x62, "Actual torque", "%", 1, true) { "${a(it) - 125}" },
        pid(0x63, "Reference torque", "Nm", 2, false) { "${ab(it)}" },
        pid(0x64, "Torque / idle", "%", 1, false) { "${a(it) - 125}" },
        pid(0x67, "Coolant sensors", "°C", 2, false) { "${temp(a(it))} / ${temp(b(it))}" },
        pid(0x68, "IAT sensors", "°C", 2, false) { "${temp(a(it))} / ${temp(b(it))}" },
        pid(0x6B, "EGR temp", "°C", 1, false) { temp(a(it)) },
        pid(0x6F, "Turbo inlet P", "kPa", 2, true) { "${b(it)}" },
        pid(0x70, "Boost control", "", 4, true) { decodeBoost(it) },
        pid(0x71, "VGT / VNT", "%", 3, true) { pct(b(it)) },
        pid(0x72, "Wastegate", "%", 3, true) { pct(b(it)) },
        pid(0x73, "Exhaust pressure", "kPa", 4, true) { "${cd(it)}" },
        pid(0x74, "Turbo RPM", "rpm", 2, true) { "${ab(it) * 10}" },
        pid(0x75, "Turbo temp 1", "°C", 2, true) { catTemp(ab(it)) },
        pid(0x76, "Turbo temp 2", "°C", 2, true) { catTemp(ab(it)) },
        pid(0x77, "Charge-air cooler", "°C", 1, true) { temp(a(it)) },
        pid(0x7A, "DPF ΔP / temp", "", 4, true) { decodeDpf(it) },
        pid(0x7B, "DPF bank 2", "", 4, false) { decodeDpf(it) },
        pid(0x7C, "DPF temp", "°C", 2, true) { catTemp(ab(it)) },
        pid(0x83, "NOx", "ppm", 2, false) { "${ab(it)}" },
        pid(0x87, "Manifold abs P", "kPa", 2, false) { "${ab(it)}" },
        pid(0xA2, "Cylinder fuel rate", "mg/stroke", 2, false) { "%.1f".format(ab(it) / 32.0) },
        pid(0xA4, "Transmission gear", "", 2, false) { "${a(it)}" },
        pid(0xA6, "Odometer", "km", 4, false) { "%.1f".format(((a(it) shl 24) + (b(it) shl 16) + (c(it) shl 8) + d(it)) / 10.0) },
    )

    val byId: Map<Int, Pid> = all.associateBy { it.id }

    fun defaultSelected(): Set<Int> = all.filter { it.defaultSelected }.map { it.id }.toSet()

    fun isSupportPointer(id: Int): Boolean = id and 0x1F == 0 && id <= 0xE0

    fun raw(id: Int): Pid = Pid(
        id = id,
        label = "PID %02X".format(id),
        unit = "",
        bytes = 0,
        defaultSelected = false,
        decode = { bytes -> rawDecode(bytes) },
    )

    fun forId(id: Int): Pid = byId[id] ?: raw(id)

    fun supportedFromBitmasks(maps: Map<Int, List<Int>>): Set<Int> {
        val out = mutableSetOf<Int>()
        maps.forEach { (base, bytes) ->
            if (bytes.size < 4) return@forEach
            for (byteIndex in 0 until 4) {
                val value = bytes[byteIndex]
                for (bit in 0 until 8) {
                    if (value and (0x80 shr bit) != 0) {
                        out.add(base + byteIndex * 8 + bit + 1)
                    }
                }
            }
        }
        return out
    }

    private fun pid(
        id: Int,
        label: String,
        unit: String,
        bytes: Int,
        selected: Boolean,
        decode: (List<Int>) -> String,
    ) = Pid(id, label, unit, bytes, selected, decode)

    private fun pct(v: Int) = "${v * 100 / 255}"
    private fun temp(v: Int) = "${v - 40}"
    private fun trim(v: Int) = "${(v - 128) * 100 / 128}"
    private fun catTemp(raw: Int) = "${raw / 10 - 40}"

    private fun fuelStatus(v: Int): String {
        val bits = buildList {
            if (v and 0x01 != 0) add("OL")
            if (v and 0x02 != 0) add("CL")
            if (v and 0x04 != 0) add("OL-drive")
            if (v and 0x08 != 0) add("OL-fault")
            if (v and 0x10 != 0) add("CL-fault")
        }
        return bits.joinToString("+").ifBlank { v.toString() }
    }

    private fun fuelType(v: Int): String = when (v) {
        1 -> "Petrol"
        4 -> "Diesel"
        else -> "Type $v"
    }

    private fun obdStandard(v: Int): String = when (v) {
        1 -> "OBD-II"
        6 -> "EOBD"
        10, 11 -> "EOBD+JOBD"
        else -> "Std $v"
    }

    private fun decodeBoost(bytes: List<Int>): String {
        if (bytes.size < 4) return rawDecode(bytes)
        return "ctrl ${pct(a(bytes))}% · ${cd(bytes)} kPa"
    }

    private fun decodeDpf(bytes: List<Int>): String {
        if (bytes.size < 4) return rawDecode(bytes)
        val dp = ((bytes[0] shl 8) + bytes[1]) / 100.0
        val temp = ((bytes[2] shl 8) + bytes[3]) / 10.0 - 40
        return "ΔP %.1f kPa / %.0f °C".format(dp, temp)
    }

    private fun rawDecode(bytes: List<Int>): String {
        if (bytes.isEmpty()) return "—"
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val hints = buildList {
            if (bytes.size == 1) {
                add("${bytes[0]}")
                add("${bytes[0] - 40}°C")
                add("${bytes[0] * 100 / 255}%")
            }
            if (bytes.size >= 2) add("${(bytes[0] shl 8) + bytes[1]}")
        }
        return if (hints.isEmpty()) hex else "$hex (${hints.joinToString(" · ")})"
    }
}
