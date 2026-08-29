package app.benzpro.kawasaki

import app.benzpro.elm.ElmClient
import app.benzpro.log.SessionLog
import app.benzpro.obd.DtcDecoder
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.ObdService
import app.benzpro.obd.Pid
import app.benzpro.util.rethrowCancel
import app.benzpro.vehicle.CodeReadResult
import app.benzpro.vehicle.DiagnosticBackend
import app.benzpro.vehicle.EcuProbeResult

class KawasakiBackend(
    private val log: SessionLog,
) : DiagnosticBackend {
    override val livePids: List<Pid> = KawasakiPids.all
    override val defaultSelectedPidIds: Set<Int> = setOf(0x0C, 0x0D, 0x05, 0x42)

    override suspend fun probeEcu(elm: ElmClient): EcuProbeResult {
        log.info("Z1000 KWP init — needs 4-pin KDS → 16-pin cable; ABS is a separate plug")
        runCatching { elm.at("ATSP5", 2500) }.rethrowCancel()
        runCatching { elm.at("ATH1") }.rethrowCancel()
        runCatching { elm.at("ATSH8112F1") }.rethrowCancel()
        val fi = runCatching { elm.at("ATFI", 3000) }.rethrowCancel().getOrNull()
        if (fi != null) log.info("ATFI $fi")
        val start = runCatching { elm.command("81", 2000) }.rethrowCancel().getOrNull()
        if (start != null) log.info("81 $start")
        val session = runCatching { elm.command("1080", 2000) }.rethrowCancel().getOrNull()
        if (session != null) log.info("1080 $session")
        val pid = runCatching { elm.command("210B", 1500) }.rethrowCancel().getOrNull()
        val obd = runCatching { ObdService.readPidPayload(elm, 1, 0x0C, 1500) }.rethrowCancel().getOrNull()
        val up = (start != null && !ElmClient.isNoData(start)) ||
            (session != null && !ElmClient.isNoData(session)) ||
            (pid != null && !ElmClient.isNoData(pid)) ||
            obd != null
        return if (up) {
            log.success("Z1000 ECU responded on K-line")
            EcuProbeResult(true, KawasakiPids.all.map { it.id }.toSet(), null, "KDS ECU up")
        } else {
            log.warn("Z1000 K-line not up (4-pin adapter? ignition on?)")
            EcuProbeResult(
                false,
                emptySet(),
                null,
                "Z1000 — K-line not up (4-pin adapter? ignition on?)",
            )
        }
    }

    override suspend fun readCodes(elm: ElmClient): CodeReadResult {
        val stored = readVia(elm, listOf("03", "13", "18FF00"), "stored")
        return CodeReadResult(stored, emptyList(), emptyList())
    }

    override suspend fun clearCodes(elm: ElmClient): String {
        val raw = runCatching { elm.command("14", 3000) }.rethrowCancel().getOrNull()
            ?: runCatching { elm.command("04", 3000) }.rethrowCancel().getOrNull()
            ?: error("No clear response")
        if (ElmClient.isNoData(raw)) error("Clear failed: $raw")
        return raw
    }

    override suspend fun readPid(elm: ElmClient, pid: Pid, timeoutMs: Long): String? {
        if (pid.id >= 0x2100) {
            val cmd = "%04X".format(pid.id)
            val raw = elm.command(cmd, timeoutMs)
            if (ElmClient.isNoData(raw)) return null
            val bytes = ElmClient.hexBytes(raw)
            val payload = if (bytes.size >= 3) bytes.drop(bytes.size - pid.bytes) else bytes
            return pid.decode(payload)
        }
        val payload = ObdService.readPidPayload(elm, 1, pid.id, timeoutMs) ?: return null
        return pid.decode(payload)
    }

    override suspend fun readFreezeFrame(elm: ElmClient, code: String): String? = null

    override suspend fun readReadiness(elm: ElmClient): String? = null

    private suspend fun readVia(elm: ElmClient, cmds: List<String>, status: String): List<DtcEntry> {
        cmds.forEach { cmd ->
            val raw = runCatching { elm.command(cmd, 2500) }.rethrowCancel().getOrNull() ?: return@forEach
            if (ElmClient.isNoData(raw)) return@forEach
            val bytes = ElmClient.hexBytes(raw)
            val codes = DtcDecoder.fromBytes(bytes.drop(1))
            if (codes.isNotEmpty()) {
                return codes.map { DtcEntry(it, DtcDecoder.saeTitle(it), status) }
            }
            log.info("$cmd → $raw")
        }
        return emptyList()
    }
}

object KawasakiPids {
    val all: List<Pid> = listOf(
        Pid(0x0C, "RPM", "rpm", 2, true) { "${((it.getOrElse(0) { 0 } shl 8) + it.getOrElse(1) { 0 }) / 4}" },
        Pid(0x0D, "Speed", "km/h", 1, true) { "${it.getOrElse(0) { 0 }}" },
        Pid(0x05, "Coolant", "°C", 1, true) { "${it.getOrElse(0) { 0 } - 40}" },
        Pid(0x11, "Throttle", "%", 1, true) { "${it.getOrElse(0) { 0 } * 100 / 255}" },
        Pid(0x42, "Battery", "V", 2, true) { "%.2f".format(((it.getOrElse(0) { 0 } shl 8) + it.getOrElse(1) { 0 }) / 1000.0) },
        Pid(0x210B, "KDS RPM", "rpm", 2, true) { kdsRpm(it) },
        Pid(0x210C, "KDS speed", "km/h", 1, true) { "${it.getOrElse(0) { 0 }}" },
        Pid(0x2105, "KDS ECT", "°C", 1, true) { "${it.getOrElse(0) { 0 }}" },
        Pid(0x2104, "KDS TPS", "%", 1, true) { "${it.getOrElse(0) { 0 }}" },
        Pid(0x2109, "KDS battery", "V", 1, true) { "%.1f".format(it.getOrElse(0) { 0 } / 10.0) },
    )

    private fun kdsRpm(bytes: List<Int>): String {
        val v = (bytes.getOrElse(0) { 0 } shl 8) + bytes.getOrElse(1) { 0 }
        return "$v"
    }
}
