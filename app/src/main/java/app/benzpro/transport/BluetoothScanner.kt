package app.benzpro.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

class BluetoothScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    private var receiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    fun bonded(): List<BtDevice> {
        return adapter.bondedDevices.orEmpty().map {
            BtDevice(it.address, it.name ?: it.address, bonded = true)
        }.sortedWith(compareByDescending<BtDevice> { looksLikeObdAdapter(it.name) }.thenBy { it.name })
    }

    @SuppressLint("MissingPermission")
    fun startScan(onDevice: (BtDevice) -> Unit) {
        stopScan()
        val rec = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return
                val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val name = device.name ?: device.address
                onDevice(BtDevice(device.address, name, device.bondState == BluetoothDevice.BOND_BONDED))
            }
        }
        receiver = rec
        ContextCompat.registerReceiver(
            context,
            rec,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { adapter.cancelDiscovery() }
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun bond(address: String) {
        adapter.getRemoteDevice(address).createBond()
    }
}
