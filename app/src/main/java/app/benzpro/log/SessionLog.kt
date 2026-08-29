package app.benzpro.log

import java.util.concurrent.atomic.AtomicLong

class SessionLog(
    private val capacity: Int = 500,
    private val onChange: () -> Unit,
) {
    private val seq = AtomicLong(0)
    private val lines = ArrayDeque<LogLine>(capacity)

    @Synchronized
    fun add(kind: LogKind, text: String) {
        val line = LogLine(
            id = seq.incrementAndGet(),
            atMs = System.currentTimeMillis(),
            kind = kind,
            text = text.trimEnd(),
        )
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(line)
        onChange()
    }

    fun tx(text: String) = add(LogKind.Tx, text)
    fun rx(text: String) = add(LogKind.Rx, text)
    fun info(text: String) = add(LogKind.Info, text)
    fun warn(text: String) = add(LogKind.Warn, text)
    fun error(text: String) = add(LogKind.Error, text)
    fun success(text: String) = add(LogKind.Success, text)

    @Synchronized
    fun snapshot(): List<LogLine> = lines.toList()
}
