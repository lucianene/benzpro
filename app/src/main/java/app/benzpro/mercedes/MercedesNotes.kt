package app.benzpro.mercedes

object MercedesNotes {
    fun forCode(code: String): String? {
        val key = code.uppercase().take(5)
        return notes[code.uppercase()] ?: notes[key]
    }

    fun title(code: String): String = MercedesDtcCatalog.title(code)

    private val notes = mapOf(
        "P0087" to "OM651 rail pressure low — leaks, pump, sensor, quantity control valve",
        "P0088" to "OM651 rail pressure high — pressure control / rail sensor",
        "P0190" to "OM651 rail pressure sensor circuit",
        "P0234" to "OM651 overboost — VGT / boost control / MAP",
        "P0299" to "OM651 underboost — pipes, turbo, MAP, swirl/EGR",
        "P0380" to "OM651 glow plug circuit — cold start / glow module",
        "P0401" to "OM651 EGR insufficient — carbon, cooler, valve (common on this engine)",
        "P0402" to "OM651 EGR excessive flow",
        "P0670" to "OM651 glow control module",
        "P2002" to "OM651 DPF efficiency — soot load, B28/8 pressure sensor, short trips",
        "P2263" to "OM651 turbo boost performance",
        "P242F" to "OM651 DPF restriction — ash/soot, pressure sensor, regen history",
        "P2452" to "OM651 DPF differential pressure sensor (B28/8)",
        "P2453" to "OM651 DPF pressure sensor range — hoses / B28/8",
        "P2458" to "OM651 DPF regen duration not OK",
        "P2459" to "OM651 DPF regen frequency — urban cycles, sensor, oil dilution",
        "P2463" to "OM651 DPF soot accumulation — may be calculated, not a clogged brick",
        "P0700" to "EGS52 requested MIL — read transmission module, not just CDI",
        "P0715" to "722.6 input speed sensor — EGS52, not 7G conductor plate",
        "P0720" to "722.6 output speed sensor",
        "P0730" to "722.6 incorrect gear ratio",
        "P0740" to "722.6 TCC / lock-up — shudder / solenoid / fluid",
        "P0750" to "722.6 shift solenoid",
        "P0753" to "722.6 shift solenoid electrical",
        "P0894" to "722.6 transmission component slipping — fluid/filter first",
        "C1000" to "ESP control unit — scan ESP, not only CDI",
        "B1000" to "Control module — check which SAM / IC set it",
    )
}
