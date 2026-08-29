package app.benzpro.vehicle

import app.benzpro.elm.ElmClient
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.Pid

data class EcuProbeResult(
    val up: Boolean,
    val supportedPids: Set<Int>,
    val vin: String?,
    val message: String,
)

data class CodeReadResult(
    val stored: List<DtcEntry>,
    val pending: List<DtcEntry>,
    val permanent: List<DtcEntry>,
) {
    val all: List<DtcEntry>
        get() = stored + pending + permanent
}

interface DiagnosticBackend {
    val livePids: List<Pid>
    val defaultSelectedPidIds: Set<Int>

    suspend fun probeEcu(elm: ElmClient): EcuProbeResult
    suspend fun readCodes(elm: ElmClient): CodeReadResult
    suspend fun clearCodes(elm: ElmClient): String
    suspend fun readPid(elm: ElmClient, pid: Pid, timeoutMs: Long = 800L): String?
    suspend fun readFreezeFrame(elm: ElmClient, code: String): String?
    suspend fun readReadiness(elm: ElmClient): String?
}
