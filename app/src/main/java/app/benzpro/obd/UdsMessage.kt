package app.benzpro.obd

/**
 * ISO-TP + ISO 14229 helpers for ELM/Vgate ASCII dumps.
 *
 * Negative response 7F &lt;SID&gt; 78 is ResponsePending, not a DTC.
 * 7F 19 78 SAE-decodes to C3F19-78 if you treat it as a 3-byte fault — never do that.
 */
object UdsMessage {
    data class Nrc(val sid: Int, val code: Int) {
        val name: String get() = nrcName(code)
        val pending: Boolean get() = code == NRC_PENDING
        val busy: Boolean get() = code == NRC_BUSY
        val needsSession: Boolean get() = code == NRC_NOT_IN_SESSION || code == NRC_SUB_NOT_IN_SESSION
    }

    data class DtcRecord(
        val high: Int,
        val mid: Int,
        val ftb: Int,
        val status: Int,
    )

    const val NRC_SID = 0x7F
    const val POSITIVE_READ_DTC = 0x59
    const val POSITIVE_KWP_READ_DTC = 0x58
    const val NRC_PENDING = 0x78
    const val NRC_BUSY = 0x21
    const val NRC_NOT_IN_SESSION = 0x7F
    const val NRC_SUB_NOT_IN_SESSION = 0x7E

    fun dump(bytes: List<Int>): String =
        bytes.joinToString(" ") { "%02X".format(it) }.ifBlank { "(none)" }

    fun payload(rawBytes: List<Int>): List<Int> {
        if (rawBytes.isEmpty()) return emptyList()
        val assembled = assembleIsoTp(rawBytes)
        return dropLeadingPending(assembled)
    }

    fun nrcAtStart(payload: List<Int>): Nrc? {
        if (payload.size < 3 || payload[0] != NRC_SID) return null
        return Nrc(payload[1] and 0xFF, payload[2] and 0xFF)
    }

    /** True when the frame is only 7F &lt;SID&gt; 78 (and padding), so the tester must keep waiting. */
    fun isPendingOnly(rawBytes: List<Int>): Boolean {
        val assembled = assembleIsoTp(rawBytes)
        if (assembled.size < 3) return false
        var i = 0
        var saw = false
        while (i + 2 < assembled.size &&
            assembled[i] == NRC_SID &&
            assembled[i + 2] == NRC_PENDING
        ) {
            saw = true
            i += 3
        }
        if (!saw) return false
        val rest = assembled.drop(i)
        return rest.isEmpty() || rest.all { it == 0 }
    }

    fun hasPositiveReadDtc(rawBytes: List<Int>): Boolean {
        val p = payload(rawBytes)
        return p.firstOrNull() == POSITIVE_READ_DTC || p.firstOrNull() == POSITIVE_KWP_READ_DTC
    }

    /**
     * Parse 0x59 by subfunction. 0x02 (reportDTCByStatusMask) is
     * 59 02 DTCStatusAvailabilityMask { DTCHigh, DTCMid, DTCLow/FTB, status }*
     * — never infer the mask from payload length.
     */
    fun parseReadDtc(rawBytes: List<Int>): List<DtcRecord>? {
        val p = payload(rawBytes)
        if (p.firstOrNull() != POSITIVE_READ_DTC) return null
        if (p.size < 2) return emptyList()
        return when (p[1]) {
            0x01 -> emptyList()
            0x02, 0x0A, 0x13, 0x15, 0x17 -> recordsAfterAvailabilityMask(p)
            else -> null
        }
    }

    /** 59 &lt;sub&gt; &lt;mask&gt; then repeating 4-byte DTC+status. */
    private fun recordsAfterAvailabilityMask(p: List<Int>): List<DtcRecord> {
        if (p.size < 3) return emptyList()
        var i = 3
        val out = mutableListOf<DtcRecord>()
        while (i + 3 < p.size) {
            val high = p[i]
            val mid = p[i + 1]
            val ftb = p[i + 2]
            val status = p[i + 3]
            i += 4
            if (high == 0 && mid == 0 && ftb == 0) continue
            out.add(DtcRecord(high, mid, ftb, status))
        }
        return out
    }

    fun parseKwpDtc(rawBytes: List<Int>): List<Pair<Int, Int>>? {
        val p = payload(rawBytes)
        if (p.firstOrNull() != POSITIVE_KWP_READ_DTC) return null
        var i = 1
        val rest = p.size - i
        if (rest % 3 == 1) i++
        val out = mutableListOf<Pair<Int, Int>>()
        while (i + 1 < p.size) {
            val a = p[i]
            val b = p[i + 1]
            i += if (i + 2 < p.size) 3 else 2
            if (a == 0 && b == 0) continue
            out.add(a to b)
        }
        return out
    }

    fun nrcName(code: Int): String = when (code) {
        0x10 -> "generalReject"
        0x11 -> "serviceNotSupported"
        0x12 -> "subFunctionNotSupported"
        0x13 -> "incorrectMessageLength"
        0x21 -> "busyRepeatRequest"
        0x22 -> "conditionsNotCorrect"
        0x24 -> "requestSequenceError"
        0x31 -> "requestOutOfRange"
        0x33 -> "securityAccessDenied"
        0x35 -> "invalidKey"
        0x36 -> "exceededNumberOfAttempts"
        0x37 -> "requiredTimeDelayNotExpired"
        0x70 -> "uploadDownloadNotAccepted"
        0x72 -> "generalProgrammingFailure"
        0x78 -> "responsePending"
        0x7E -> "subFunctionNotSupportedInActiveSession"
        0x7F -> "serviceNotSupportedInActiveSession"
        else -> "NRC %02X".format(code)
    }

    fun assembleIsoTp(bytes: List<Int>): List<Int> {
        if (bytes.isEmpty()) return emptyList()
        val pci = bytes[0]
        val type = pci shr 4
        if (type == 0 && pci in 1..7) {
            val len = pci and 0x0F
            if (bytes.size >= 1 + len) return bytes.subList(1, 1 + len)
        }
        if (type == 1 && bytes.size >= 2) {
            val len = ((pci and 0x0F) shl 8) + bytes[1]
            if (len <= 0) return bytes
            val out = ArrayList<Int>(len)
            var i = 2
            val firstData = minOf(6, len, bytes.size - i)
            if (firstData <= 0) return bytes
            out.addAll(bytes.subList(i, i + firstData))
            i += firstData
            while (out.size < len && i < bytes.size) {
                val cf = bytes[i]
                if (cf shr 4 == 2) {
                    i++
                    val take = minOf(7, len - out.size, bytes.size - i)
                    if (take <= 0) break
                    out.addAll(bytes.subList(i, i + take))
                    i += take
                } else {
                    val take = minOf(len - out.size, bytes.size - i)
                    out.addAll(bytes.subList(i, i + take))
                    break
                }
            }
            return if (out.isEmpty()) bytes else out.take(len)
        }
        return bytes
    }

    private fun dropLeadingPending(bytes: List<Int>): List<Int> {
        var i = 0
        while (i + 2 < bytes.size &&
            bytes[i] == NRC_SID &&
            bytes[i + 2] == NRC_PENDING
        ) {
            i += 3
        }
        return if (i == 0) bytes else bytes.drop(i)
    }
}
