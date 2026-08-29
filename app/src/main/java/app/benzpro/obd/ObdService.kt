package app.benzpro.obd

import app.benzpro.elm.ElmClient
import app.benzpro.util.rethrowCancel
import java.util.Locale

object ObdService {
    suspend fun readPidPayload(elm: ElmClient, mode: Int, pid: Int, timeoutMs: Long = 2000L): List<Int>? {
        val cmd = "%02X%02X".format(mode, pid)
        val raw = elm.command(cmd, timeoutMs)
        if (ElmClient.isNoData(raw)) return null
        val bytes = ElmClient.hexBytes(raw)
        return pidPayload(bytes, mode, pid)
    }

    /** First 41/pid block only — 7DF broadcast can concatenate 7E8 and 7E9 in one ELM line. */
    fun pidPayload(bytes: List<Int>, mode: Int, pid: Int): List<Int>? {
        if (bytes.size < 2) return null
        val echo = mode + 0x40
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] == echo && bytes[i + 1] == pid) {
                val rest = bytes.subList(i + 2, bytes.size)
                val cut = rest.indexOfFirst { it == echo }.let { if (it < 0) rest.size else it }
                return rest.subList(0, cut).toList()
            }
            i++
        }
        return null
    }

    suspend fun readSupported(elm: ElmClient): Set<Int> {
        val maps = mutableMapOf<Int, List<Int>>()
        var base = 0x00
        while (base <= 0xE0) {
            val payload = runCatching { readPidPayload(elm, 1, base, 2500) }.rethrowCancel().getOrNull()
                ?: break
            if (payload.size < 4) break
            maps[base] = payload.take(4)
            val next = base + 0x20
            val bits = PidCatalog.supportedFromBitmasks(mapOf(base to payload.take(4)))
            if (next !in bits) break
            base = next
        }
        return PidCatalog.supportedFromBitmasks(maps)
    }

    suspend fun readVin(elm: ElmClient): String? {
        val raw = runCatching { elm.command("0902", 3000) }.rethrowCancel().getOrNull() ?: return null
        if (ElmClient.isNoData(raw)) return null
        val bytes = ElmClient.hexBytes(raw)
        var i = 0
        val sid = bytes.indexOfFirst { it == 0x49 }
        if (sid >= 0 && bytes.getOrNull(sid + 1) == 0x02) {
            i = sid + 2
            if (i < bytes.size && bytes[i] <= 0x11) i++
        }
        val ascii = bytes.drop(i).mapNotNull { b ->
            val c = b.toChar()
            if (c.isLetterOrDigit()) c else null
        }.joinToString("")
        return VIN_BODY.find(ascii)?.value ?: ascii.takeIf { it.length >= 11 }
    }

    suspend fun readDtcs(elm: ElmClient, mode: Int): List<String> {
        val cmd = "%02X".format(mode)
        val raw = runCatching { elm.command(cmd, 4000) }.rethrowCancel().getOrNull() ?: return emptyList()
        if (ElmClient.isNoData(raw)) return emptyList()
        val bytes = ElmClient.hexBytes(raw)
        val assembled = UdsMessage.payload(bytes)
        if (UdsMessage.nrcAtStart(assembled) != null) return emptyList()
        val echo = mode + 0x40
        val start = assembled.indexOfFirst { it == echo }
        val payload = if (start >= 0) {
            val rest = assembled.drop(start + 1)
            val cut = rest.indexOfFirst { it == echo }.let { if (it < 0) rest.size else it }
            rest.take(cut)
        } else {
            assembled
        }
        val body = if (payload.isNotEmpty() && payload[0] <= 0x7F && payload.size % 2 == 1) {
            payload.drop(1)
        } else {
            payload
        }
        return DtcDecoder.fromBytes(body)
    }

    suspend fun clearDtcs(elm: ElmClient): String {
        val raw = elm.command("04", 4000)
        return raw
    }

    suspend fun freezeFrame(elm: ElmClient, pid: Int = 0x02): String? {
        val payload = readPidPayload(elm, 2, pid) ?: return null
        if (payload.size < 2) return payload.joinToString(" ") { "%02X".format(it) }
        return DtcDecoder.format(payload[0], payload[1])
    }

    suspend fun readiness(elm: ElmClient): String? {
        val payload = readPidPayload(elm, 1, 0x01) ?: return null
        if (payload.isEmpty()) return null
        val mil = payload[0] and 0x80 != 0
        val count = payload[0] and 0x7F
        return if (mil) "MIL ON · $count stored" else "MIL off · $count stored"
    }

    fun decodeLive(pid: Pid, payload: List<Int>): String {
        if (pid.bytes > 0 && payload.size < pid.bytes) {
            return payload.joinToString(" ") { "%02X".format(it) }
        }
        return pid.decode(payload)
    }

    fun normalizeCode(code: String): String = code.uppercase(Locale.US)

    private val VIN_BODY = Regex("[A-HJ-NPR-Z0-9]{17}")
}
