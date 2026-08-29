package app.benzpro.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

class SppTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
) : ElmTransport {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var socket: BluetoothSocket? = null
    private var readerJob: Job? = null
    @Volatile private var closing = false
    var onDropped: (() -> Unit)? = null

    override val isOpen: Boolean
        get() = socket?.isConnected == true

    override fun incoming(): SharedFlow<ByteArray> = _incoming

    @SuppressLint("MissingPermission")
    override suspend fun open(scope: CoroutineScope) = withContext(Dispatchers.IO) {
        close()
        closing = false
        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val created = connectWithFallback(device)
        try {
            socket = created
            readerJob = scope.launch(Dispatchers.IO) {
                val input = created.inputStream
                val buf = ByteArray(256)
                try {
                    while (isActive) {
                        val n = try {
                            input.read(buf)
                        } catch (_: Exception) {
                            break
                        }
                        if (n <= 0) break
                        _incoming.emit(buf.copyOf(n))
                    }
                } finally {
                    if (!closing) onDropped?.invoke()
                }
            }
        } catch (e: CancellationException) {
            runCatching { created.close() }
            socket = null
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectWithFallback(device: BluetoothDevice): BluetoothSocket {
        val first = device.createRfcommSocketToServiceRecord(sppUuid)
        try {
            bluetoothConnect(first)
            return first
        } catch (e: CancellationException) {
            runCatching { first.close() }
            throw e
        } catch (e: IOException) {
            runCatching { first.close() }
            val fallback = fallbackChannel1(device)
                ?: throw IOException("SPP connect failed: ${e.message}", e)
            try {
                bluetoothConnect(fallback)
                return fallback
            } catch (e2: CancellationException) {
                runCatching { fallback.close() }
                throw e2
            } catch (e2: IOException) {
                runCatching { fallback.close() }
                throw IOException("SPP connect failed: ${e.message}", e)
            }
        }
    }

    private suspend fun bluetoothConnect(socket: BluetoothSocket) {
        try {
            withTimeout(20_000) {
                withContext(Dispatchers.IO) {
                    socket.connect()
                }
            }
        } catch (e: TimeoutCancellationException) {
            runCatching { socket.close() }
            throw IOException("SPP connect timed out")
        } catch (e: CancellationException) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun fallbackChannel1(device: BluetoothDevice): BluetoothSocket? {
        return runCatching {
            device.javaClass
                .getMethod("createRfcommSocket", Integer.TYPE)
                .invoke(device, 1) as BluetoothSocket
        }.getOrNull()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching { adapter.cancelDiscovery() }
        val out = socket?.outputStream ?: throw IOException("Adapter socket closed")
        out.write(bytes)
        out.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        closing = true
        val job = readerJob
        readerJob = null
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
        job?.join()
        Unit
    }
}
