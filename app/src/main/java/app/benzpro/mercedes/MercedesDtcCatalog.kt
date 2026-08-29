package app.benzpro.mercedes

import app.benzpro.obd.DtcDecoder
import app.benzpro.obd.DtcEntry

/**
 * C207 E250 CDI / OM651.911 / 722.6 titles and clear-advice.
 * Lookup is by SAE base (P1127) then 6-hex (112712). Third UDS byte is J2012 FTB.
 */
object MercedesDtcCatalog {
    fun fromUds(a: Int, b: Int, c: Int, statusByte: Int, permanent: Boolean = false): DtcEntry {
        val base = DtcDecoder.format(a, b)
        val code = if (c == 0) base else "%s-%02X".format(base, c)
        val status = if (permanent) "permanent" else udsStatus(statusByte)
        return DtcEntry(
            code = code,
            title = title(base, c),
            status = status,
            advice = clearAdvice(base, status),
            ftb = Ftb[c],
        )
    }

    fun fromObd(code: String, status: String): DtcEntry {
        val key = code.uppercase()
        return DtcEntry(
            code = key,
            title = title(key, null),
            status = status,
            advice = clearAdvice(key, status),
        )
    }

    fun title(code: String, ftb: Int? = null): String {
        val key = code.uppercase().substringBefore("-")
        val hex6 = key.filter { it.isLetterOrDigit() }
        val named = titles[key]
            ?: titles[key.take(5)]
            ?: titles[hex6.takeLast(4)]
            ?: DtcDecoder.saeTitle(key.take(5))
        if (named != "Manufacturer / unknown") return named
        if (ftb != null && Ftb[ftb] != null) {
            return "OM651/C207 manufacturer DTC · ${Ftb[ftb]}"
        }
        return named
    }

    fun clearAdvice(code: String, status: String): String {
        val s = status.lowercase()
        val key = code.uppercase()
        when {
            key.startsWith("B") && s.contains("srs") ->
                return "SRS/airbag code — do not clear from here."
            s.contains("permanent") ->
                return "Permanent emissions DTC. Mode 04 / 14 often will not drop it until the CDI finishes its tests (drive cycles). Safe to try after a real repair; expect it back if the fault remains."
            s.contains("pending") ->
                return "Pending only — has not confirmed yet. Often goes away after a few good trips. Clearing is optional."
            key.startsWith("P001") || key.startsWith("P000A") || key.startsWith("P000B") ->
                return "Cam/crank / timing. After a timing-chain job this is the leftover you expect. Safe to clear once the chain work is done; if it returns, the correlation is still off."
            key.startsWith("P112") || key.startsWith("P1A2") || key.startsWith("P010") ->
                return "Air-mass / HFM / boost path. Common leftover after intake, chain, or turbo work. Safe to clear if the repair is finished; watch live MAP/MAF if it comes back."
            key.startsWith("P008") || key.startsWith("P019") || key.startsWith("P02") ->
                return "Rail / injectors. Only clear if you actually fixed the leak/pump/injector. It will come back immediately if pressure is still wrong."
            key.startsWith("P245") || key.startsWith("P246") || key.startsWith("P2002") || key.startsWith("P242") ->
                return "DPF / regen. Clearing turns the lamp off but does not empty the brick. Safe if you only want the memory gone; soot load stays."
            key.startsWith("P0700") || key.startsWith("P07") || key.startsWith("P08") || key.startsWith("P09") ->
                return "Transmission (722.6 / EGS52). Read EGS too. Clearing CDI will not fix EGS; it will request the MIL again."
            else ->
                return if (s.contains("confirmed") || s.contains("failed")) {
                    "Confirmed. If you already repaired it (chain, intake, battery, etc.) it is safe to clear and see if it returns. Freeze frame is lost on clear."
                } else {
                    "If the job is done, clearing is OK. If the code returns, the fault is still there — this app cannot force a DPF regen or SCN."
                }
        }
    }

    fun udsStatus(status: Int): String {
        val bits = buildList {
            if (status and 0x01 != 0) add("failed now")
            if (status and 0x02 != 0) add("failed this cycle")
            if (status and 0x04 != 0) add("pending")
            if (status and 0x08 != 0) add("confirmed")
            if (status and 0x10 != 0) add("not tested since clear")
            if (status and 0x20 != 0) add("failed since clear")
            if (status and 0x80 != 0) add("MIL")
        }
        return bits.joinToString(" · ").ifBlank { "stored" }
    }

    private val titles = mapOf(
        "P0016" to "OM651 cam/crank correlation (B6/1 vs B70) — timing chain, sensor, tone wheel",
        "P0017" to "OM651 cam/crank correlation bank/exhaust — chain or cam sensor",
        "P0087" to "OM651 rail pressure too low — leaks, high-pressure pump, quantity valve, sensor",
        "P0088" to "OM651 rail pressure too high — pressure control valve / rail sensor",
        "P0100" to "OM651 HFM / MAF B2/5 circuit",
        "P0101" to "OM651 HFM range/performance — dirty meter, leak after HFM, swirl flaps",
        "P0110" to "OM651 intake air temp circuit (HFM or B17)",
        "P0115" to "OM651 coolant temp B11/4 circuit",
        "P0128" to "OM651 thermostat — coolant below spec (vacuum/thermostat, OM651 bulletin)",
        "P0190" to "OM651 rail pressure sensor circuit",
        "P0201" to "OM651 injector cylinder 1 circuit",
        "P0202" to "OM651 injector cylinder 2 circuit",
        "P0203" to "OM651 injector cylinder 3 circuit",
        "P0204" to "OM651 injector cylinder 4 circuit",
        "P0234" to "OM651 overboost — VGT/HP turbo, boost control, MAP",
        "P0299" to "OM651 underboost — pipes, HP/LP turbo, MAP, swirl/EGR, vacuum",
        "P0380" to "OM651 glow plug circuit",
        "P0401" to "OM651 EGR insufficient flow — carbon, cooler, valve (very common)",
        "P0402" to "OM651 EGR excessive flow",
        "P0470" to "OM651 exhaust pressure sensor",
        "P0480" to "OM651 fan control",
        "P0500" to "Vehicle speed — CDI sees CAN speed from ESP/EGS",
        "P0579" to "Cruise control",
        "P0627" to "OM651 fuel pump control",
        "P0670" to "OM651 glow control module",
        "P0671" to "OM651 glow plug 1",
        "P0672" to "OM651 glow plug 2",
        "P0673" to "OM651 glow plug 3",
        "P0674" to "OM651 glow plug 4",
        "P0700" to "EGS52 requested MIL — read transmission module, not only CDI",
        "P0715" to "722.6 input speed sensor (EGS52, not 7G conductor plate)",
        "P0720" to "722.6 output speed sensor",
        "P0730" to "722.6 incorrect gear ratio",
        "P0740" to "722.6 TCC / lock-up",
        "P0750" to "722.6 shift solenoid",
        "P0753" to "722.6 shift solenoid electrical",
        "P0894" to "722.6 component slipping — fluid/filter first",
        "P1127" to "OM651 air-mass plausibility (HFM B2/5, boost leak, swirl, EGR) — leftover after intake/chain work is common",
        "P1128" to "OM651 air-mass / mixture adaptation",
        "P1187" to "OM651 fuel water / rail plausibility",
        "P1220" to "OM651 quantity control valve",
        "P1250" to "OM651 fuel pressure control",
        "P1351" to "OM651 glow time control",
        "P1470" to "OM651 swirl / intake port shutoff",
        "P1480" to "OM651 glow module",
        "P1672" to "OM651 coolant temp B11/4 malfunction (Mercedes TSB — can false-set air-path codes)",
        "P1A24" to "OM651 mass air flow sensor 1",
        "P1A26" to "OM651 intake air mass bank 1 implausible",
        "P1A27" to "OM651 intake air mass bank 1 implausible",
        "P2002" to "OM651 DPF efficiency below threshold",
        "P2261" to "OM651 turbo bypass / wastegate",
        "P2263" to "OM651 turbo boost performance (HP/LP sequential)",
        "P242F" to "OM651 DPF restriction — ash/soot, B28/8, regen history",
        "P2452" to "OM651 DPF differential pressure B28/8",
        "P2453" to "OM651 DPF pressure sensor range — hoses / B28/8",
        "P2458" to "OM651 DPF regen duration not OK",
        "P2459" to "OM651 DPF regen frequency — urban cycles, sensor, oil dilution",
        "P2463" to "OM651 DPF soot accumulation (often calculated, not a packed brick)",
        "P261F" to "OM651 coolant pump control",
        "C0041" to "Wheel-speed / CAN speed (often ESP B6/b7) — CDI copies it; scan ESP if it returns",
        "C1000" to "ESP control unit — scan ESP, not only CDI",
        "B1000" to "Control module internal — check which SAM / IC set it",
        "B3590" to "C207 body/CAN code stored in CDI — usually a bus timeout; scan SAM/IC if it returns",
        "U0001" to "CAN high-speed bus off",
        "U0100" to "Lost comms with CDI",
        "U0101" to "Lost comms with EGS52",
        "U0121" to "Lost comms with ESP",
        "U0140" to "Lost comms with SAM / CGW",
        "U0155" to "Lost comms with instrument cluster",
    )

    private val Ftb = mapOf(
        0x00 to "no extra failure type",
        0x01 to "general electrical fault",
        0x07 to "mechanical fault",
        0x0A to "fluid leak",
        0x11 to "circuit short to ground",
        0x12 to "circuit short to battery",
        0x13 to "circuit open",
        0x16 to "voltage too low",
        0x17 to "voltage too high",
        0x1C to "voltage out of range",
        0x23 to "signal stuck low",
        0x24 to "signal stuck high",
        0x29 to "signal invalid",
        0x2F to "signal erratic",
        0x2A to "signal stuck in range",
        0x62 to "signal compare failure",
        0x64 to "signal implausible",
        0x71 to "actuator stuck",
        0x72 to "actuator stuck open",
        0x73 to "actuator stuck closed",
        0x77 to "commanded position not reached",
        0x78 to "alignment / adjustment incorrect",
        0x92 to "performance / incorrect operation",
        0x93 to "no operation",
        0x94 to "unexpected operation",
        0x95 to "incorrect assembly",
        0x96 to "component internal fault",
        0x97 to "obstructed / jammed",
        0xA2 to "system voltage low",
        0xA3 to "system voltage high",
        0xD2 to "signal compare / CAN (often a copy of another module)",
    )
}
