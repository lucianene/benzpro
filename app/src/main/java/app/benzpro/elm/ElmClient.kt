package app.benzpro.elm

import app.benzpro.log.SessionLog
import app.benzpro.obd.UdsMessage
import app.benzpro.transport.ElmTransport
import app.benzpro.util.rethrowCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale

class ElmTimeoutException(val cmd: String) : IOException("ELM timeout: $cmd")

class ElmClient(
    private val transport: ElmTransport,
    private val log: SessionLog,
) {
    private val mutex = Mutex()
    private val buffer = StringBuilder()
    private var waiter: CompletableDeferred<String>? = null
    private var collector: Job? = null
    private val ascii: Charset = Charsets.US_ASCII

    inner class Session {
        suspend fun send(
            cmd: String,
            timeoutMs: Long = 2500L,
            waitPending: Boolean = false,
            logHex: Boolean = false,
        ): String = transact(cmd, timeoutMs, waitPending, logHex)

        suspend fun sendUds(cmd: String, timeoutMs: Long = 12_000L): String {
            var last = ""
            repeat(3) { attempt ->
                last = transact(cmd, timeoutMs, waitPending = true, logHex = true)
                val nrc = UdsMessage.nrcAtStart(UdsMessage.payload(hexBytes(last)))
                if (nrc?.busy == true && attempt < 2) {
                    log.warn("UDS NRC 21 busyRepeatRequest — retry")
                    delay(300)
                } else {
                    return last
                }
            }
            return last
        }

        /**
         * STN pending timeout + max classic ATST. Call inside exclusive before 0x19.
         * @return true if STPTO was accepted (restore it in finally).
         */
        suspend fun armPendingListen(): Boolean {
            val pto = runCatching { send("STPTO 20000", logHex = true) }.rethrowCancel().getOrNull()
            log.info("STPTO 20000 → ${pto ?: "(no reply)"}")
            val stn = pto != null &&
                !pto.contains("?") &&
                !ElmClient.isNoData(pto) &&
                !pto.contains("ERROR", ignoreCase = true)
            if (stn) {
                log.success("STPTO accepted — adapter should keep ISO-TP open through NRC 78")
            } else {
                log.warn(
                    "STPTO not supported. Classic ELM ATST max is 1020ms. " +
                        "If 7F 19 78 is followed by ELM >, the adapter has left receive and a 12s app wait cannot catch 0x59.",
                )
            }
            runCatching { send("ATST FF", logHex = true) }.rethrowCancel()
            runCatching { send("ATAL", logHex = true) }.rethrowCancel()
            runCatching { send("ATAT1", logHex = true) }.rethrowCancel()
            return stn
        }
    }

    fun start(scope: CoroutineScope) {
        collector?.cancel()
        collector = scope.launch {
            transport.incoming().collect { bytes ->
                val chunk = bytes.toString(ascii)
                synchronized(buffer) {
                    buffer.append(chunk)
                    maybeCompleteLocked()
                }
            }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
        waiter?.completeExceptionally(IOException("ELM stopped"))
        waiter = null
        synchronized(buffer) { buffer.clear() }
    }

    /** Hold the ELM bus for a whole header / UDS sequence so live poll cannot interleave AT commands. */
    suspend fun <T> exclusive(block: suspend Session.() -> T): T =
        mutex.withLock { Session().block() }

    suspend fun command(cmd: String, timeoutMs: Long = 2500L): String =
        mutex.withLock { transact(cmd, timeoutMs, waitPending = false, logHex = false) }

    suspend fun commandUds(cmd: String, timeoutMs: Long = 12_000L): String =
        exclusive { sendUds(cmd, timeoutMs) }

    suspend fun at(cmd: String, timeoutMs: Long = 2000L): String = command(cmd, timeoutMs)

    suspend fun prepareUdsTiming() = exclusive {
        runCatching { send("ATAL") }.rethrowCancel()
        runCatching { send("ATAT1") }.rethrowCancel()
        runCatching { send("ATST FF") }.rethrowCancel()
    }

    suspend fun restoreUdsTiming() = exclusive {
        runCatching { send("ATST 32") }.rethrowCancel()
    }

    suspend fun resetAndBaseInit() {
        delay(200)
        runCatching { command("ATZ", 3500) }.rethrowCancel()
        delay(800)
        at("ATE0")
        at("ATL0")
        at("ATS0")
        at("ATH0")
        runCatching { at("ATCAF1") }.rethrowCancel()
        runCatching { at("ATCFC1") }.rethrowCancel()
        runCatching { at("ATAL") }.rethrowCancel()
        runCatching { at("ATAT1") }.rethrowCancel()
    }

    suspend fun setHeader11(request: String, receive: String?) = exclusive {
        send("ATSP6")
        send("ATSH $request")
        runCatching { send("ATCRA") }.rethrowCancel()
        if (!receive.isNullOrBlank()) send("ATCRA $receive")
        else runCatching { send("ATAR") }.rethrowCancel()
    }

    suspend fun setHeader29(header3: String, receive: String?) = exclusive {
        send("ATSP7")
        runCatching { send("ATCP 18") }.rethrowCancel()
        send("ATSH $header3")
        runCatching { send("ATCRA") }.rethrowCancel()
        if (!receive.isNullOrBlank()) {
            runCatching { send("ATCRA $receive") }.rethrowCancel()
        } else {
            runCatching { send("ATAR") }.rethrowCancel()
        }
    }

    suspend fun restoreObdBroadcast() = exclusive {
        send("ATSP0", 2500)
        runCatching { send("ATCRA") }.rethrowCancel()
        runCatching { send("ATSH 7DF") }.rethrowCancel()
    }

    /** Pin ISO 15765 11-bit / 500k. Clear ATCRA then ATAR — V-LINK drops Mode 01 if the filter is wrong. */
    suspend fun restoreObdCan11() = exclusive {
        send("ATSP6")
        runCatching { send("ATCRA") }.rethrowCancel()
        runCatching { send("ATAR") }.rethrowCancel()
        send("ATSH 7E0")
    }

    private data class ElmChunk(val body: String, val sawPrompt: Boolean)

    private suspend fun transact(
        cmd: String,
        timeoutMs: Long,
        waitPending: Boolean,
        logHex: Boolean,
    ): String {
        log.tx(cmd)
        val first = writeAndAwait(cmd, timeoutMs, logHex)
            ?: run {
                val partial = synchronized(buffer) { buffer.toString() }
                log.warn("timeout after ${timeoutMs}ms (no ELM >): ${escapeElm(partial).ifBlank { "(empty)" }}")
                recoverPrompt()
                throw ElmTimeoutException(cmd)
            }
        logChunk(first, logHex)
        if (!waitPending) return first.body
        val firstBytes = hexBytes(first.body)
        if (!UdsMessage.isPendingOnly(firstBytes)) {
            val assembled = UdsMessage.assembleIsoTp(firstBytes)
            val hadPending = assembled.size >= 3 && assembled[0] == 0x7F && assembled.getOrNull(2) == 0x78
            if (hadPending && UdsMessage.hasPositiveReadDtc(firstBytes)) {
                log.success("0x59 in the same ELM window as NRC 78 — adapter stayed in receive until >")
            }
            return first.body
        }

        if (first.sawPrompt) {
            log.warn(
                "NRC 78 arrived with ELM > — adapter closed this request. " +
                    "Classic ATST≤1020ms. Not resending; listening for unsolicited 0x59 only.",
            )
        } else {
            log.info("NRC 78 without ELM > — still in the adapter receive window")
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        val acc = StringBuilder(first.body)
        while (System.currentTimeMillis() < deadline) {
            val left = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val extra = awaitFollowUp(left.coerceAtMost(800L), logHex) ?: continue
            logChunk(extra, logHex)
            acc.append(' ').append(extra.body)
            if (!UdsMessage.isPendingOnly(hexBytes(extra.body))) {
                if (!extra.sawPrompt) {
                    log.success("unsolicited 0x59 after NRC 78 — adapter was still listening")
                } else {
                    log.success("0x59 arrived with a later ELM >")
                }
                return clean(acc.toString())
            }
            if (extra.sawPrompt) {
                log.warn("ELM > again while still pending — ISO-TP still closed")
            }
        }
        drainBuffer()?.let { leftover ->
            log.rx(leftover)
            acc.append(' ').append(leftover)
            if (!UdsMessage.isPendingOnly(hexBytes(leftover))) {
                log.success("late 0x59 drained after NRC 78")
                return clean(acc.toString())
            }
        }
        if (first.sawPrompt) {
            log.warn("no 0x59 after ELM > / NRC 78 — Vgate stopped CAN RX; app-side wait cannot recover it")
        } else {
            log.warn("UDS still responsePending after ${timeoutMs}ms")
        }
        return clean(acc.toString())
    }

    private suspend fun writeAndAwait(cmd: String, timeoutMs: Long, logRaw: Boolean): ElmChunk? {
        val deferred = CompletableDeferred<String>()
        synchronized(buffer) {
            buffer.clear()
            waiter = deferred
        }
        try {
            transport.write("$cmd\r".toByteArray(ascii))
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: return null
            if (logRaw) log.info("ELM raw ${escapeElm(raw)}")
            return ElmChunk(clean(raw), sawPrompt = true)
        } catch (e: CancellationException) {
            recoverPrompt()
            throw e
        } finally {
            synchronized(buffer) {
                if (waiter === deferred) waiter = null
            }
        }
    }

    /**
     * After NRC 78 the clone may send `>` immediately, then dump 0x59 without a second prompt.
     * Complete on `>` or on a non-pending UDS payload sitting in the buffer (no TX).
     */
    private suspend fun awaitFollowUp(timeoutMs: Long, logRaw: Boolean): ElmChunk? {
        if (timeoutMs <= 0L) return drainReady()
        drainReady()?.let { return it }
        val deferred = CompletableDeferred<String>()
        synchronized(buffer) {
            waiter = deferred
            maybeCompleteLocked()
        }
        return try {
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (raw != null) {
                if (logRaw) log.info("ELM raw ${escapeElm(raw)}")
                ElmChunk(clean(raw), sawPrompt = true)
            } else {
                drainReady()
            }
        } finally {
            synchronized(buffer) {
                if (waiter === deferred) waiter = null
            }
        }
    }

    private fun drainReady(): ElmChunk? {
        val leftover = synchronized(buffer) { buffer.toString() }
        val cleaned = clean(leftover)
        if (cleaned.isBlank() || UdsMessage.isPendingOnly(hexBytes(cleaned))) return null
        if (!isCompleteFollowUp(cleaned)) return null
        synchronized(buffer) { buffer.clear() }
        return ElmChunk(cleaned, sawPrompt = false)
    }

    private fun drainBuffer(): String? {
        val leftover = synchronized(buffer) {
            val text = buffer.toString()
            buffer.clear()
            text
        }
        return clean(leftover).takeIf { it.isNotBlank() }
    }

    private fun isCompleteFollowUp(cleaned: String): Boolean {
        val bytes = hexBytes(cleaned)
        if (UdsMessage.hasPositiveReadDtc(bytes)) return true
        val nrc = UdsMessage.nrcAtStart(UdsMessage.assembleIsoTp(bytes))
        return nrc != null && !nrc.pending
    }

    private fun logChunk(chunk: ElmChunk, logHex: Boolean) {
        logRx(chunk.body, logHex)
        if (!logHex) return
        if (chunk.sawPrompt) log.info("ELM >")
        else log.info("ELM unsolicited (no >) — adapter was still listening after the prompt")
    }

    private fun logRx(cleaned: String, logHex: Boolean) {
        log.rx(cleaned.ifBlank { "(empty)" })
        if (logHex) {
            val bytes = hexBytes(cleaned)
            val assembled = UdsMessage.assembleIsoTp(bytes)
            val payload = UdsMessage.payload(bytes)
            log.info("ELM hex ${UdsMessage.dump(bytes)}")
            if (payload != assembled) log.info("ISO-TP ${UdsMessage.dump(payload)}")
            else if (assembled != bytes) log.info("ISO-TP ${UdsMessage.dump(assembled)}")
            UdsMessage.nrcAtStart(assembled)?.let { nrc ->
                log.warn("UDS NRC ${"%02X".format(nrc.code)} ${nrc.name} (SID ${"%02X".format(nrc.sid)})")
            }
        }
    }

    private suspend fun recoverPrompt() {
        synchronized(buffer) {
            waiter = null
            buffer.clear()
        }
        try {
            val drain = CompletableDeferred<String>()
            synchronized(buffer) { waiter = drain }
            transport.write("\r".toByteArray(ascii))
            withTimeoutOrNull(400) { drain.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            synchronized(buffer) {
                waiter = null
                buffer.clear()
            }
        }
    }

    /**
     * Do not consume `>` unless a waiter is set. Otherwise a late 0x59 after NRC 78 is dropped.
     */
    private fun maybeCompleteLocked() {
        if (waiter == null) return
        val text = buffer.toString()
        val idx = text.indexOf('>')
        if (idx < 0) return
        val result = text.substring(0, idx)
        val after = text.substring(idx + 1)
        buffer.setLength(0)
        if (after.isNotEmpty()) buffer.append(after)
        val w = waiter ?: return
        waiter = null
        w.complete(result)
    }

    companion object {
        fun clean(raw: String): String {
            return raw
                .replace("\u0000", "")
                .replace("SEARCHING...", "", ignoreCase = true)
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("AT", ignoreCase = true) }
                .joinToString(" ")
                .trim()
        }

        fun isNoData(response: String): Boolean {
            val u = response.uppercase(Locale.US).trim()
            if (u.isBlank() || u == "?") return true
            return NO_DATA_TOKENS.any { u.contains(it) }
        }

        fun hexBytes(response: String): List<Int> {
            val stripped = FRAME_PREFIX.replace(response, " ")
                .replace("SEARCHING...", "", ignoreCase = true)
                .uppercase(Locale.US)
            val tokens = stripped.split(Regex("[^0-9A-F]+")).filter { it.isNotEmpty() }
            val out = mutableListOf<Int>()
            for (tok in tokens) {
                if (isCanHeader(tok)) continue
                val hex = if (tok.length % 2 == 1 && tok.startsWith("7E")) tok.drop(3) else tok
                if (hex.length < 2) continue
                val even = if (hex.length % 2 == 1) hex.drop(1) else hex
                even.chunked(2).forEach { pair ->
                    pair.toIntOrNull(16)?.let { out.add(it) }
                }
            }
            return out
        }

        fun hasPositiveSid(response: String, sid: Int): Boolean =
            hexBytes(response).contains(sid)

        fun escapeElm(raw: String): String =
            raw.replace("\r", "\\r").replace("\n", "\\n").replace("\u0000", "\\0")

        private fun isCanHeader(tok: String): Boolean {
            if (tok.length == 3 && tok.matches(Regex("7[DE][0-9A-F]"))) return true
            if (tok.length == 8 && tok.startsWith("18")) return true
            return false
        }

        private val NO_DATA_TOKENS = listOf(
            "NO DATA",
            "UNABLE TO CONNECT",
            "STOPPED",
            "CAN ERROR",
            "BUS ERROR",
            "BUS INIT",
            "FB ERROR",
            "DATA ERROR",
            "BUFFER FULL",
            "LV RESET",
        )
        private val FRAME_PREFIX = Regex("(?i)\\b[0-9A-F]+:")
    }
}
