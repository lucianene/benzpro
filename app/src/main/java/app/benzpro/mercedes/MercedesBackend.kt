package app.benzpro.mercedes

import app.benzpro.elm.ElmClient
import app.benzpro.log.SessionLog
import app.benzpro.obd.ObdService
import app.benzpro.obd.Pid
import app.benzpro.obd.PidCatalog
import app.benzpro.vehicle.CodeReadResult
import app.benzpro.vehicle.DiagnosticBackend
import app.benzpro.vehicle.EcuProbeResult
import app.benzpro.vehicle.VehicleRegistry
import app.benzpro.util.rethrowCancel

class MercedesBackend(
    private val log: SessionLog,
) : DiagnosticBackend {
    override val livePids: List<Pid> = PidCatalog.all
    override val defaultSelectedPidIds: Set<Int> = PidCatalog.defaultSelected()

    override suspend fun probeEcu(elm: ElmClient): EcuProbeResult {
        elm.restoreObdBroadcast()
        val pid000 = runCatching { ObdService.readPidPayload(elm, 1, 0x00, 4000) }.rethrowCancel().getOrNull()
        if (pid000 == null) {
            log.warn("0100 no response — ignition on?")
            return EcuProbeResult(false, emptySet(), null, "ELM up, no ECU (ignition on?)")
        }
        runCatching { elm.restoreObdCan11() }.rethrowCancel()
        val supported = ObdService.readSupported(elm)
        val vin = ObdService.readVin(elm)
        if (vin != null) {
            log.info("VIN $vin")
            if (vin != VehicleRegistry.e250.vin) {
                log.warn("VIN mismatch: expected ${VehicleRegistry.e250.vin}")
            } else {
                log.success("VIN matches WDD2073031F010216")
            }
        }
        log.success("ECU up · ${supported.size} Mode 01 PIDs advertised")
        return EcuProbeResult(true, supported, vin, "CDI OBD up")
    }

    override suspend fun readCodes(elm: ElmClient): CodeReadResult {
        elm.restoreObdCan11()
        val stored = ObdService.readDtcs(elm, 0x03).map { MercedesDtcCatalog.fromObd(it, "stored") }
        val pending = ObdService.readDtcs(elm, 0x07).map { MercedesDtcCatalog.fromObd(it, "pending") }
        val permanent = ObdService.readDtcs(elm, 0x0A).map { MercedesDtcCatalog.fromObd(it, "permanent") }
        return CodeReadResult(stored, pending, permanent)
    }

    override suspend fun clearCodes(elm: ElmClient): String {
        elm.restoreObdCan11()
        val raw = ObdService.clearDtcs(elm)
        if (ElmClient.isNoData(raw) && !ElmClient.hasPositiveSid(raw, 0x44)) {
            error("Clear failed: $raw")
        }
        return raw
    }

    override suspend fun readPid(elm: ElmClient, pid: Pid, timeoutMs: Long): String? {
        val payload = ObdService.readPidPayload(elm, 1, pid.id, timeoutMs) ?: return null
        return ObdService.decodeLive(pid, payload)
    }

    override suspend fun readFreezeFrame(elm: ElmClient, code: String): String? {
        elm.restoreObdCan11()
        val frameCode = runCatching { ObdService.freezeFrame(elm) }.rethrowCancel().getOrNull() ?: return null
        val rpm = runCatching { ObdService.readPidPayload(elm, 2, 0x0C) }.rethrowCancel().getOrNull()
        val speed = runCatching { ObdService.readPidPayload(elm, 2, 0x0D) }.rethrowCancel().getOrNull()
        val coolant = runCatching { ObdService.readPidPayload(elm, 2, 0x05) }.rethrowCancel().getOrNull()
        val bits = buildList {
            add("DTC $frameCode")
            rpm?.let { bytes -> add("RPM ${PidCatalog.byId[0x0C]?.let { it.decode(bytes) }}") }
            speed?.let { bytes -> add("spd ${PidCatalog.byId[0x0D]?.let { it.decode(bytes) }}") }
            coolant?.let { bytes -> add("ECT ${PidCatalog.byId[0x05]?.let { it.decode(bytes) }}") }
        }
        return bits.joinToString(" · ")
    }

    override suspend fun readReadiness(elm: ElmClient): String? {
        elm.restoreObdCan11()
        return runCatching { ObdService.readiness(elm) }.rethrowCancel().getOrNull()
    }
}
