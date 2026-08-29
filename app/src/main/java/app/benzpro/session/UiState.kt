package app.benzpro.session

enum class LinkState { Down, Working, Up }

enum class ActionState { Idle, Loading, Success, Error }

enum class Pane { Diagnostics, Modules, Realtime }

sealed class PidRead {
    data class Value(val text: String) : PidRead()
    data object Empty : PidRead()
    data object Timeout : PidRead()
    data object LinkLost : PidRead()
}
