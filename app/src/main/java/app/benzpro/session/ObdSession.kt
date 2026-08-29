package app.benzpro.session

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import app.benzpro.elm.ElmClient
import app.benzpro.elm.ElmTimeoutException
import app.benzpro.kawasaki.KawasakiBackend
import app.benzpro.log.SessionLog
import app.benzpro.mercedes.MercedesBackend
import app.benzpro.mercedes.MercedesModules
import app.benzpro.mercedes.MercedesProtocol
import app.benzpro.mercedes.ModuleScanItem
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.Pid
import app.benzpro.obd.PidCatalog
import app.benzpro.store.AdapterStore
import app.benzpro.store.ModuleMapStore
import app.benzpro.store.SavedAdapter
import app.benzpro.store.SavedModuleAddress
import app.benzpro.transport.ElmTransport
import app.benzpro.transport.SppTransport
import app.benzpro.vehicle.DiagnosticBackend
import app.benzpro.vehicle.ElmInitKind
import app.benzpro.vehicle.VehicleProfile
import app.benzpro.vehicle.VehicleRegistry
import app.benzpro.util.rethrowCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class NeedBluetoothException(message: String) : IOException(message)

class ObdSession(
    private val context: Context,
    private val log: SessionLog,
    private val adapterStore: AdapterStore,
    private val moduleMapStore: ModuleMapStore,
    private val scope: CoroutineScope,
) {
    private val mercedes = MercedesBackend(log)
    private val kawasaki = KawasakiBackend(log)
    private val mercedesProto = MercedesProtocol(log)

    var profile: VehicleProfile = VehicleRegistry.e250
        private set

    val backend: DiagnosticBackend
        get() = when (profile.elmInit) {
            ElmInitKind.MercedesCan -> mercedes
            ElmInitKind.KawasakiKwp -> kawasaki
        }

    var elmState: LinkState = LinkState.Down
        private set
    var ecuState: LinkState = LinkState.Down
        private set
    var supportedPids: Set<Int> = emptySet()
        private set
    var reportedVin: String? = null
        private set

    var onLinkDropped: (() -> Unit)? = null

    private var transport: ElmTransport? = null
    private var elm: ElmClient? = null

    val client: ElmClient?
        get() = elm

    fun setProfile(next: VehicleProfile) {
        profile = next
    }

    fun bluetoothAdapter() =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun savedAdapter(): SavedAdapter? = adapterStore.load()

    suspend fun disconnect(message: String = "disconnected") = withContext(NonCancellable) {
        (transport as? SppTransport)?.onDropped = null
        ecuState = LinkState.Down
        elm?.stop()
        runCatching { transport?.close() }
        elm = null
        transport = null
        elmState = LinkState.Down
        log.info(message)
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, name: String) {
        val adapter = bluetoothAdapter() ?: throw NeedBluetoothException("No Bluetooth adapter")
        if (!adapter.isEnabled) throw NeedBluetoothException("Bluetooth is off")
        disconnect("reconnecting")
        elmState = LinkState.Working
        ecuState = LinkState.Down
        log.info("SPP connect $name ($address)")
        val spp = SppTransport(adapter, address)
        spp.onDropped = {
            scope.launch {
                if (elmState == LinkState.Down) return@launch
                disconnect("ELM link lost")
                onLinkDropped?.invoke()
            }
        }
        val client = ElmClient(spp, log)
        transport = spp
        elm = client
        try {
            spp.open(scope)
            client.start(scope)
            log.success("RFCOMM up")
            client.resetAndBaseInit()
            elmState = LinkState.Up
            adapterStore.save(SavedAdapter(address, name))
            log.success("ELM init done")
            ecuState = LinkState.Working
            val probe = backend.probeEcu(client)
            supportedPids = probe.supportedPids
            reportedVin = probe.vin
            ecuState = if (probe.up) LinkState.Up else LinkState.Down
            if (!probe.up) log.warn(probe.message)
        } catch (e: CancellationException) {
            disconnect("connect cancelled")
            throw e
        } catch (e: Exception) {
            log.error(e.message ?: "connect failed")
            disconnect("connect failed")
            throw e
        }
    }

    suspend fun readCodes(): List<DtcEntry> {
        val client = requireElm()
        requireEcu()
        try {
            val result = backend.readCodes(client)
            val extra = if (profile.elmInit == ElmInitKind.MercedesCan) {
                val uds = runCatching { mercedesProto.readDtcs(client) }.rethrowCancel().getOrDefault(emptyList())
                val perm = runCatching { mercedesProto.readPermanentDtcs(client) }.rethrowCancel().getOrDefault(emptyList())
                mergeDtcs(uds, perm)
            } else {
                emptyList()
            }
            val merged = mergeDtcs(result.all, extra)
            log.success(
                "codes OBD stored=${result.stored.size} pending=${result.pending.size} perm=${result.permanent.size}" +
                    " · CDI UDS=${extra.size} · shown=${merged.size}",
            )
            return merged
        } finally {
            restoreCan11()
        }
    }

    private fun mergeDtcs(first: List<DtcEntry>, extra: List<DtcEntry>): List<DtcEntry> {
        val map = LinkedHashMap<String, DtcEntry>()
        fun put(entry: DtcEntry) {
            val key = entry.code.substringBefore("-")
            val prev = map[key]
            map[key] = if (prev == null) {
                entry
            } else {
                prev.copy(
                    code = if (entry.code.contains("-")) entry.code else prev.code,
                    title = sequenceOf(entry.title, prev.title)
                        .firstOrNull { it != "Manufacturer / unknown" } ?: entry.title,
                    status = listOf(prev.status, entry.status).distinct().joinToString(" · "),
                    advice = entry.advice ?: prev.advice,
                    ftb = entry.ftb ?: prev.ftb,
                )
            }
        }
        first.forEach(::put)
        extra.forEach(::put)
        return map.values.toList()
    }

    suspend fun clearCodes(): String {
        val client = requireElm()
        requireEcu()
        try {
            val raw = backend.clearCodes(client)
            val uds = if (profile.elmInit == ElmInitKind.MercedesCan) {
                runCatching { mercedesProto.clearDtcs(client) }.rethrowCancel().getOrNull()
            } else {
                null
            }
            log.success("clear sent: $raw" + if (uds != null) " · UDS $uds" else "")
            return raw
        } finally {
            restoreCan11()
        }
    }

    suspend fun freezeFrame(code: String): String? {
        val client = requireElm()
        requireEcu()
        return backend.readFreezeFrame(client, code)
    }

    suspend fun readiness(): String? {
        val client = elm ?: return null
        if (ecuState != LinkState.Up) return null
        return backend.readReadiness(client)
    }

    suspend fun readLive(pid: Pid, timeoutMs: Long = 800L): PidRead {
        val client = elm ?: return PidRead.LinkLost
        if (elmState != LinkState.Up) return PidRead.LinkLost
        return try {
            val value = backend.readPid(client, pid, timeoutMs)
            if (value == null) PidRead.Empty else PidRead.Value(value)
        } catch (_: ElmTimeoutException) {
            PidRead.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            PidRead.LinkLost
        }
    }

    fun markEcuSilent() {
        ecuState = LinkState.Down
    }

    fun markEcuUp() {
        if (elmState == LinkState.Up) ecuState = LinkState.Up
    }

    suspend fun healthValue(pidId: Int): String? {
        val pid = PidCatalog.byId[pidId] ?: return null
        return when (val result = readLive(pid, 2000L)) {
            is PidRead.Value -> result.text
            else -> null
        }
    }

    /** ELM ATRV — adapter supply, used when the CDI does not advertise PID 42. */
    suspend fun adapterVoltage(): String? {
        val client = elm ?: return null
        val raw = runCatching { client.command("ATRV", 800) }.rethrowCancel().getOrNull() ?: return null
        val match = Regex("""(\d+\.\d+)""").find(raw) ?: return null
        return "${match.groupValues[1]} V"
    }

    suspend fun scanModules(): List<ModuleScanItem> {
        val client = requireElm()
        requireEcu()
        val saved = moduleMapStore.load(profile.id)
        val items = mutableListOf<ModuleScanItem>()
        try {
            MercedesModules.catalog.forEach { module ->
                val hit = saved[module.id]?.let { addr ->
                    runCatching {
                        if (mercedesProto.applyAndPing(client, addr)) addr else null
                    }.rethrowCancel().getOrNull()
                } ?: module.targets.firstNotNullOfOrNull { target ->
                    mercedesProto.tryTarget(client, module, target)
                }
                if (hit == null) {
                    log.warn("${module.name}: no response")
                    items.add(ModuleScanItem(module, false, null, null, null, emptyList()))
                } else {
                    val codes = runCatching { mercedesProto.readDtcs(client) }.rethrowCancel().getOrDefault(emptyList())
                    val identity = runCatching { mercedesProto.identity(client) }.rethrowCancel().getOrNull()
                    log.success("${module.name}: present · ${codes.size} DTCs")
                    items.add(ModuleScanItem(module, true, hit, codes.size, identity, codes))
                }
            }
            val merged = saved.toMutableMap()
            items.forEach { item ->
                val addr = item.address
                if (item.present && addr != null) merged[item.module.id] = addr
            }
            moduleMapStore.save(profile.id, merged)
            return items
        } finally {
            restoreCan11()
        }
    }

    suspend fun readModule(item: ModuleScanItem): ModuleScanItem {
        val client = requireElm()
        val addr = item.address ?: return item
        try {
            mercedesProto.apply(client, addr)
            val codes = mercedesProto.readDtcs(client)
            val identity = runCatching { mercedesProto.identity(client) }.rethrowCancel().getOrNull()
            return item.copy(codes = codes, dtcCount = codes.size, identity = identity)
        } finally {
            restoreCan11()
        }
    }

    suspend fun clearModule(item: ModuleScanItem): String {
        val client = requireElm()
        val addr = item.address ?: error("Module not present")
        try {
            mercedesProto.apply(client, addr)
            return mercedesProto.clearDtcs(client)
        } finally {
            restoreCan11()
        }
    }

    private suspend fun restoreCan11() {
        val client = elm ?: return
        withContext(NonCancellable) {
            runCatching { client.restoreObdCan11() }
        }
    }

    private fun requireElm(): ElmClient {
        if (elmState != LinkState.Up) error("ELM not connected")
        return elm ?: error("ELM not connected")
    }

    private fun requireEcu() {
        if (ecuState != LinkState.Up) error("ECU not connected (ignition on?)")
    }
}
