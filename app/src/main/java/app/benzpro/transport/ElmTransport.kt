package app.benzpro.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

interface ElmTransport {
    val isOpen: Boolean
    fun incoming(): SharedFlow<ByteArray>
    suspend fun open(scope: CoroutineScope)
    suspend fun close()
    suspend fun write(bytes: ByteArray)
}
