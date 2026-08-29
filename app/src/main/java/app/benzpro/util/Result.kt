package app.benzpro.util

import kotlinx.coroutines.CancellationException

fun <T> Result<T>.rethrowCancel(): Result<T> {
    exceptionOrNull()?.let { if (it is CancellationException) throw it }
    return this
}
