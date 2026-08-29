package app.benzpro.mercedes

import app.benzpro.elm.ElmClient
import app.benzpro.log.SessionLog
import app.benzpro.obd.DtcDecoder
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.UdsMessage
import app.benzpro.store.SavedModuleAddress
import app.benzpro.util.rethrowCancel

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

    suspend fun identity(elm: ElmClient): String? {
        val vin = readDid(elm, "22F190")?.let { vinOnly(it) }
        val part = (readDid(elm, "22F187") ?: readDid(elm, "22F191"))?.let { partOnly(it) }
        return listOfNotNull(vin, part).joinToString(" · ").ifBlank { null }
    }

    suspend fun readDtcs(elm: ElmClient): List<DtcEntry> {
        elm.prepareUdsTiming()
        return try {
            readDtcsUds(elm, "1902AF", permanent = false)
                ?: readDtcsUds(elm, "1902FF", permanent = false)
                ?: readDtcsKwp(elm)
                ?: emptyList()
        } finally {
            elm.restoreUdsTiming()
        }
    }

    suspend fun readPermanentDtcs(elm: ElmClient): List<DtcEntry> {
        elm.prepareUdsTiming()
        return try {
            readDtcsUds(elm, "190A", permanent = true) ?: emptyList()
        } finally {
            elm.restoreUdsTiming()
        }
    }

    private suspend fun readDtcsUds(
        elm: ElmClient,
        request: String,
        permanent: Boolean,
    ): List<DtcEntry>? {
        var raw = runCatching { elm.commandUds(request, 12_000) }.rethrowCancel().getOrNull()
            ?: return null
        if (ElmClient.isNoData(raw)) return null
        var bytes = ElmClient.hexBytes(raw)
        var payload = UdsMessage.payload(bytes)
        var nrc = UdsMessage.nrcAtStart(payload)
        if (nrc?.needsSession == true) {
            log.info("UDS $request needs extended session — 1003")
            runCatching { elm.commandUds("1003", 8_000) }.rethrowCancel()
            raw = runCatching { elm.commandUds(request, 12_000) }.rethrowCancel().getOrNull()
                ?: return null
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

    private suspend fun readDtcsKwp(elm: ElmClient): List<DtcEntry>? {
        val raw = runCatching { elm.commandUds("18FF00", 8_000) }.rethrowCancel().getOrNull()
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
        elm.prepareUdsTiming()
        return try {
            elm.commandUds("14FFFFFF", 8_000)
        } finally {
            elm.restoreUdsTiming()
        }
    }

    private suspend fun readDid(elm: ElmClient, cmd: String): String? {
        val raw = runCatching { elm.command(cmd, 800) }.rethrowCancel().getOrNull() ?: return null
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
        runCatching {
            when (target.scheme) {
                AddressScheme.Iso11 -> elm.setHeader11(target.requestHeader, target.receiveAddress)
                AddressScheme.Iso29 -> elm.setHeader29(target.requestHeader, target.receiveAddress)
            }
        }.rethrowCancel().onFailure {
            log.warn("header failed: ${it.message}")
            return null
        }
        if (!ping(elm)) return null
        return SavedModuleAddress(
            moduleId = module.id,
            scheme = target.scheme.name,
            requestHeader = target.requestHeader,
            receiveAddress = target.receiveAddress,
        )
    }
}
