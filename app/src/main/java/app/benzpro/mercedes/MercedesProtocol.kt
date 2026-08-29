package app.benzpro.mercedes

import app.benzpro.elm.ElmClient
import app.benzpro.log.SessionLog
import app.benzpro.obd.DtcDecoder
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.UdsMessage
import app.benzpro.store.SavedModuleAddress
import app.benzpro.util.rethrowCancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ModuleScanItem(
    val module: MbModule,
    val present: Boolean,
    val address: SavedModuleAddress?,
    val dtcCount: Int?,
    val identity: String?,
    val codes: List<DtcEntry>,
)

class MercedesProtocol(
    private val log: SessionLog,
) {
    suspend fun apply(elm: ElmClient, address: SavedModuleAddress) {
        when (address.scheme) {
            AddressScheme.Iso11.name, "Iso11" -> elm.setHeader11(address.requestHeader, address.receiveAddress)
            else -> elm.setHeader29(address.requestHeader, address.receiveAddress)
        }
    }

    suspend fun ping(elm: ElmClient, timeoutMs: Long = 400): Boolean {
        val raw = runCatching { elm.command("1001", timeoutMs) }.rethrowCancel().getOrNull() ?: return false
        if (ElmClient.isNoData(raw)) {
            val tp = runCatching { elm.command("3E00", timeoutMs) }.rethrowCancel().getOrNull() ?: return false
            return !ElmClient.isNoData(tp)
        }
        return true
    }

    suspend fun applyAndPing(elm: ElmClient, address: SavedModuleAddress, timeoutMs: Long = 400): Boolean {
        return elm.exclusive {
            when (address.scheme) {
                AddressScheme.Iso11.name, "Iso11" -> {
                    send("ATSP6")
                    send("ATSH ${address.requestHeader}")
                    runCatching { send("ATCRA") }.rethrowCancel()
                    if (address.receiveAddress.isNotBlank()) send("ATCRA ${address.receiveAddress}")
                }
                else -> {
                    send("ATSP7")
                    runCatching { send("ATCP 18") }.rethrowCancel()
                    send("ATSH ${address.requestHeader}")
                    runCatching { send("ATCRA") }.rethrowCancel()
                    if (address.receiveAddress.isNotBlank()) {
                        runCatching { send("ATCRA ${address.receiveAddress}") }.rethrowCancel()
                    }
                }
            }
            val raw = runCatching { send("1001", timeoutMs) }.rethrowCancel().getOrNull() ?: return@exclusive false
            if (!ElmClient.isNoData(raw)) return@exclusive true
            val tp = runCatching { send("3E00", timeoutMs) }.rethrowCancel().getOrNull() ?: return@exclusive false
            !ElmClient.isNoData(tp)
        }
    }

    suspend fun identity(elm: ElmClient): String? {
        return elm.exclusive {
            runCatching { send("ATST FF") }.rethrowCancel()
            try {
                val vin = readDidLocked(this, "22F190")?.let { vinOnly(it) }
                val part = (readDidLocked(this, "22F187") ?: readDidLocked(this, "22F191"))?.let { partOnly(it) }
                listOfNotNull(vin, part).joinToString(" · ").ifBlank { null }
            } finally {
                withContext(NonCancellable) {
                    runCatching { send("ATST 32") }
                }
            }
        }
    }

    suspend fun readDtcs(elm: ElmClient): List<DtcEntry> {
        return elm.exclusive {
            val session = this
            val stn = armPendingListen()
            try {
                readDtcsUdsLocked(session, "1902AF", permanent = false)
                    ?: readDtcsUdsLocked(session, "1902FF", permanent = false)
                    ?: readDtcsKwpLocked(session)
                    ?: emptyList()
            } finally {
                withContext(NonCancellable) {
                    restorePendingListen(session, stn)
                }
            }
        }
    }

    suspend fun readPermanentDtcs(elm: ElmClient): List<DtcEntry> {
        return elm.exclusive {
            val session = this
            val stn = armPendingListen()
            try {
                readDtcsUdsLocked(session, "190A", permanent = true) ?: emptyList()
            } finally {
                withContext(NonCancellable) {
                    restorePendingListen(session, stn)
                }
            }
        }
    }

    private suspend fun restorePendingListen(session: ElmClient.Session, stn: Boolean) {
        if (stn) runCatching { session.send("STPTO 150") }
        runCatching { session.send("ATST 32") }
    }

    private suspend fun readDtcsUdsLocked(
        session: ElmClient.Session,
        request: String,
        permanent: Boolean,
    ): List<DtcEntry>? {
        var raw = runCatching { session.sendUds(request) }.rethrowCancel().getOrNull() ?: return null
        if (ElmClient.isNoData(raw)) return null
        var bytes = ElmClient.hexBytes(raw)
        var payload = UdsMessage.payload(bytes)
        var nrc = UdsMessage.nrcAtStart(payload)
        if (nrc?.needsSession == true) {
            log.info("UDS $request needs extended session — 1003")
            runCatching { session.sendUds("1003", 8_000) }.rethrowCancel()
            raw = runCatching { session.sendUds(request) }.rethrowCancel().getOrNull() ?: return null
            if (ElmClient.isNoData(raw)) return null
            bytes = ElmClient.hexBytes(raw)
            payload = UdsMessage.payload(bytes)
            nrc = UdsMessage.nrcAtStart(payload)
        }
        if (nrc != null) {
            log.warn("UDS $request NRC ${nrc.name} — not a 0x59 DTC list")
            return null
        }
        val records = UdsMessage.parseReadDtc(bytes) ?: return null
        log.success("UDS $request 59 · ${records.size} DTC(s)")
        return records.map { rec ->
            MercedesDtcCatalog.fromUds(rec.high, rec.mid, rec.ftb, rec.status, permanent)
        }.distinctBy { it.code }
    }

    private suspend fun readDtcsKwpLocked(session: ElmClient.Session): List<DtcEntry>? {
        val raw = runCatching { session.sendUds("18FF00", 8_000) }.rethrowCancel().getOrNull()
            ?: return null
        if (ElmClient.isNoData(raw)) return null
        val bytes = ElmClient.hexBytes(raw)
        val pairs = UdsMessage.parseKwpDtc(bytes) ?: return null
        log.success("KWP 18 · ${pairs.size} DTC(s)")
        return pairs.map { (a, b) ->
            MercedesDtcCatalog.fromObd(DtcDecoder.format(a, b), "module")
        }.distinctBy { it.code }
    }

    suspend fun clearDtcs(elm: ElmClient): String {
        return elm.exclusive {
            runCatching { send("ATST FF") }.rethrowCancel()
            try {
                sendUds("14FFFFFF", 8_000)
            } finally {
                withContext(NonCancellable) {
                    runCatching { send("ATST 32") }
                }
            }
        }
    }

    private suspend fun readDidLocked(session: ElmClient.Session, cmd: String): String? {
        val raw = runCatching { session.send(cmd, 2500) }.rethrowCancel().getOrNull() ?: return null
        if (ElmClient.isNoData(raw)) return null
        val bytes = UdsMessage.payload(ElmClient.hexBytes(raw))
        if (UdsMessage.nrcAtStart(bytes) != null) return null
        var i = 0
        if (bytes.getOrNull(0) == 0x62) {
            i = 1
            if (bytes.size >= 3) i = 3
        }
        val ascii = bytes.drop(i).mapNotNull { b ->
            val ch = b.toChar()
            if (ch.isLetterOrDigit() || ch == '-') ch else null
        }.joinToString("").trim()
        return ascii.takeIf { it.length >= 4 }
    }

    private fun vinOnly(raw: String): String? =
        Regex("[A-HJ-NPR-Z0-9]{11,17}").find(raw)?.value

    private fun partOnly(raw: String): String? {
        val cleaned = raw.filter { it.isLetterOrDigit() }
        return cleaned.takeIf { it.length >= 10 && it.startsWith("A") && it.any { ch -> ch.isDigit() } }
    }

    suspend fun tryTarget(elm: ElmClient, module: MbModule, target: CanTarget): SavedModuleAddress? {
        log.info("probe ${module.name} ${target.scheme} ${target.requestHeader}")
        val ok = runCatching {
            elm.exclusive {
                when (target.scheme) {
                    AddressScheme.Iso11 -> {
                        send("ATSP6")
                        send("ATSH ${target.requestHeader}")
                        runCatching { send("ATCRA") }.rethrowCancel()
                        if (target.receiveAddress.isNotBlank()) send("ATCRA ${target.receiveAddress}")
                    }
                    AddressScheme.Iso29 -> {
                        send("ATSP7")
                        runCatching { send("ATCP 18") }.rethrowCancel()
                        send("ATSH ${target.requestHeader}")
                        runCatching { send("ATCRA") }.rethrowCancel()
                        if (target.receiveAddress.isNotBlank()) {
                            runCatching { send("ATCRA ${target.receiveAddress}") }.rethrowCancel()
                        }
                    }
                }
                val raw = runCatching { send("1001", 400) }.rethrowCancel().getOrNull()
                if (raw != null && !ElmClient.isNoData(raw)) return@exclusive true
                val tp = runCatching { send("3E00", 400) }.rethrowCancel().getOrNull()
                tp != null && !ElmClient.isNoData(tp)
            }
        }.rethrowCancel().getOrElse {
            log.warn("header failed: ${it.message}")
            false
        }
        if (!ok) return null
        return SavedModuleAddress(
            moduleId = module.id,
            scheme = target.scheme.name,
            requestHeader = target.requestHeader,
            receiveAddress = target.receiveAddress,
        )
    }
}
