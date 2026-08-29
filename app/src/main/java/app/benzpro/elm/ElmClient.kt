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

    suspend fun command(cmd: String, timeoutMs: Long = 2500L): String =
        mutex.withLock { transact(cmd, timeoutMs, waitPending = false, logHex = false) }

    /**
     * UDS request: long ST timeout, wait through NRC 78 without resending,
     * retry NRC 21. Logs raw ELM ASCII plus parsed hex.
     */
    suspend fun commandUds(cmd: String, timeoutMs: Long = 12_000L): String = mutex.withLock {
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
        last
    }

    suspend fun at(cmd: String, timeoutMs: Long = 2000L): String = command(cmd, timeoutMs)

    suspend fun prepareUdsTiming() {
        runCatching { at("ATAL") }.rethrowCancel()
        runCatching { at("ATAT1") }.rethrowCancel()
        runCatching { at("ATST FF") }.rethrowCancel()
    }

    suspend fun restoreUdsTiming() {
        runCatching { at("ATST 32") }.rethrowCancel()
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

    suspend fun setHeader11(request: String, receive: String?) {
        at("ATSP6")
        at("ATSH $request")
        if (!receive.isNullOrBlank()) at("ATCRA $receive")
    }

    suspend fun setHeader29(header3: String, receive: String?) {
        at("ATSP7")
        runCatching { at("ATCP 18") }.rethrowCancel()
        at("ATSH $header3")
        if (!receive.isNullOrBlank()) {
            runCatching { at("ATCRA $receive") }.rethrowCancel()
        }
    }

    suspend fun restoreObdBroadcast() {
        at("ATSP0", 2500)
        runCatching { at("ATCRA") }.rethrowCancel()
        runCatching { at("ATSH 7DF") }.rethrowCancel()
    }

    /** Pin ISO 15765 11-bit / 500k. Clear ATCRA then ATAR — V-LINK drops Mode 01 if the filter is wrong. */
    suspend fun restoreObdCan11() {
        at("ATSP6")
        runCatching { at("ATCRA") }.rethrowCancel()
        runCatching { at("ATAR") }.rethrowCancel()
        at("ATSH 7E0")
    }

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
                log.warn("timeout after ${timeoutMs}ms: ${escapeElm(partial).ifBlank { "(empty)" }}")
                recoverPrompt()
                throw ElmTimeoutException(cmd)
            }
        logRx(first, logHex)
        if (!waitPending || !UdsMessage.isPendingOnly(hexBytes(first))) return first

        val deadline = System.currentTimeMillis() + timeoutMs
        val acc = StringBuilder(first)
        while (System.currentTimeMillis() < deadline) {
            log.warn("UDS 7F xx 78 responsePending — waiting (do not resend)")
            val left = deadline - System.currentTimeMillis()
            val extra = awaitPrompt(left, logHex) ?: break
            logRx(extra, logHex)
            acc.append(' ').append(extra)
            if (!UdsMessage.isPendingOnly(hexBytes(extra))) {
                return clean(acc.toString())
            }
        }
        log.warn("UDS still responsePending after ${timeoutMs}ms")
        return clean(acc.toString())
    }

    private suspend fun writeAndAwait(cmd: String, timeoutMs: Long, logRaw: Boolean): String? {
        val deferred = CompletableDeferred<String>()
        synchronized(buffer) {
            buffer.clear()
            waiter = deferred
        }
        try {
            transport.write("$cmd\r".toByteArray(ascii))
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: return null
            if (logRaw) log.info("ELM raw ${escapeElm(raw)}")
            return clean(raw)
        } catch (e: CancellationException) {
            recoverPrompt()
            throw e
        } finally {
            synchronized(buffer) {
                if (waiter === deferred) waiter = null
            }
        }
    }

    /** Wait for the next `>` without sending — used after NRC 78. */
    private suspend fun awaitPrompt(timeoutMs: Long, logRaw: Boolean): String? {
        if (timeoutMs <= 0L) return null
        val deferred = CompletableDeferred<String>()
        synchronized(buffer) {
            waiter = deferred
            maybeCompleteLocked()
        }
        return try {
            val raw = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: return null
            if (logRaw) log.info("ELM raw ${escapeElm(raw)}")
            clean(raw)
        } finally {
            synchronized(buffer) {
                if (waiter === deferred) waiter = null
            }
        }
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
