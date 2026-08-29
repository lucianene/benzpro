package app.benzpro.log

enum class LogKind {
    Tx,
    Rx,
    Info,
    Warn,
    Error,
    Success,
}

data class LogLine(
    val id: Long,
    val atMs: Long,
    val kind: LogKind,
    val text: String,
)
