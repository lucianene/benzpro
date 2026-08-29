package app.benzpro

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.benzpro.log.LogLine
import app.benzpro.log.SessionLog
import app.benzpro.mercedes.ModuleScanItem
import app.benzpro.obd.DtcEntry
import app.benzpro.obd.Pid
import app.benzpro.obd.PidCatalog
import app.benzpro.session.ActionState
import app.benzpro.session.LinkState
import app.benzpro.session.NeedBluetoothException
import app.benzpro.session.ObdSession
import app.benzpro.session.Pane
import app.benzpro.session.PidRead
import app.benzpro.store.AdapterStore
import app.benzpro.store.DtcHistoryStore
import app.benzpro.store.HistoricDtc
import app.benzpro.store.ModuleMapStore
import app.benzpro.store.PidSelectionStore
import app.benzpro.store.VehicleStore
import app.benzpro.transport.BluetoothScanner
import app.benzpro.transport.BtDevice
import app.benzpro.transport.looksLikeObdAdapter
import app.benzpro.vehicle.VehicleProfile
import app.benzpro.vehicle.VehicleRegistry
import app.benzpro.util.rethrowCancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class BenzProViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    val logLines = mutableStateListOf<LogLine>()
    private val logPulse = Channel<Unit>(Channel.CONFLATED)
    private val log = SessionLog { logPulse.trySend(Unit) }
    private val adapterStore = AdapterStore(app)
    private val vehicleStore = VehicleStore(app)
    private val pidStore = PidSelectionStore(app)
    private val historyStore = DtcHistoryStore(app)
    private val session = ObdSession(
        context = app,
        log = log,
        adapterStore = adapterStore,
        moduleMapStore = ModuleMapStore(app),
        scope = viewModelScope,
    )

    var vehicle by mutableStateOf(VehicleRegistry.byId(vehicleStore.loadSelectedId(VehicleRegistry.E250_ID)))
        private set
    var pane by mutableStateOf(Pane.Diagnostics)
        private set
    var elmState by mutableStateOf(LinkState.Down)
        private set
    var ecuState by mutableStateOf(LinkState.Down)
        private set
    var snackbarMessage by mutableStateOf<String?>(null)
        private set
    var bluetoothEnableIntent by mutableStateOf<Intent?>(null)
        private set
    var shareLogIntent by mutableStateOf<Intent?>(null)
        private set
    var showDeviceSheet by mutableStateOf(false)
    var showGarage by mutableStateOf(false)
    var showClearConfirm by mutableStateOf(false)
    var showPidPicker by mutableStateOf(false)
    var scanningDevices by mutableStateOf(false)
        private set
    val devices = mutableStateListOf<BtDevice>()

    var readState by mutableStateOf(ActionState.Idle)
        private set
    var clearState by mutableStateOf(ActionState.Idle)
        private set
    var scanState by mutableStateOf(ActionState.Idle)
        private set
    val codes = mutableStateListOf<DtcEntry>()
    var freezeFrame by mutableStateOf<String?>(null)
    var readiness by mutableStateOf<String?>(null)
    val history = mutableStateListOf<HistoricDtc>()
    var milText by mutableStateOf("—")
    var voltageText by mutableStateOf("—")
    var coolantText by mutableStateOf("—")
    var dpfText by mutableStateOf("—")
    val modules = mutableStateListOf<ModuleScanItem>()
    var selectedModule by mutableStateOf<ModuleScanItem?>(null)
    val liveValues = mutableStateMapOf<Int, String>()
    val selectedPids = mutableStateListOf<Int>()

    private var scanner: BluetoothScanner? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var readFlashJob: Job? = null
    private var clearFlashJob: Job? = null
    private var scanFlashJob: Job? = null
    private val pollPaused = AtomicInteger(0)

    val vehicles: List<VehicleProfile> get() = VehicleRegistry.all()
    fun livePidList(): List<Pid> {
        val catalog = session.backend.livePids
        val advertised = session.supportedPids.filterNot { PidCatalog.isSupportPointer(it) }
        if (advertised.isEmpty()) return catalog
        val byId = catalog.associateBy { it.id }
        return advertised.sorted().map { id -> byId[id] ?: PidCatalog.raw(id) }
    }

    fun supportedPidIds() = session.supportedPids.filterNot { PidCatalog.isSupportPointer(it) }.toSet()

    fun selectAllVisiblePids() {
        selectedPids.clear()
        selectedPids.addAll(livePidList().map { it.id })
        pidStore.save(vehicle.id, selectedPids.toSet())
    }

    fun selectDefaultPids() {
        val advertised = supportedPidIds()
        val defaults = session.backend.defaultSelectedPidIds
        selectedPids.clear()
        selectedPids.addAll(if (advertised.isEmpty()) defaults else defaults.filter { it in advertised })
        pidStore.save(vehicle.id, selectedPids.toSet())
    }

    init {
        viewModelScope.launch {
            logPulse.consumeEach {
                val snapshot = log.snapshot()
                logLines.clear()
                logLines.addAll(snapshot)
            }
        }
        session.onLinkDropped = {
            syncLinks()
            stopPoll()
            snack("ELM link lost")
        }
        session.setProfile(vehicle)
        reloadPids()
        reloadHistory()
        log.info("BenzPro · ${vehicle.displayName}")
        log.info(vehicle.hint)
    }

    fun onAppStart() {
        val saved = session.savedAdapter()
        if (saved != null) {
            log.info("saved adapter ${saved.name} ${saved.address}")
            connect(saved.address, saved.name, fromAuto = true)
        } else {
            log.info("no saved adapter — tap Connect")
        }
    }

    fun connectPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH)
        }
    }

    fun scanPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH)
        }
    }

    fun consumeSnackbar() {
        snackbarMessage = null
    }

    fun consumeBluetoothEnable() {
        bluetoothEnableIntent = null
    }

    fun consumeShareLog() {
        shareLogIntent = null
    }

    fun copyLog() {
        val text = log.exportText()
        if (text.isBlank()) {
            snack("Log is empty")
            return
        }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("BenzPro log", text))
        snack("Log copied")
    }

    fun exportLog() {
        val text = log.exportText()
        if (text.isBlank()) {
            snack("Log is empty")
            return
        }
        shareLogIntent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BenzPro log")
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "Export log",
        )
    }

    fun selectPane(next: Pane) {
        pane = next
        if (next == Pane.Realtime) startPoll() else stopPoll()
    }

    fun selectVehicle(profile: VehicleProfile) {
        if (profile.id == vehicle.id) {
            showGarage = false
            return
        }
        showGarage = false
        viewModelScope.launch {
            stopPoll()
            connectJob?.cancel()
            if (elmState != LinkState.Down) {
                session.disconnect("vehicle switch")
                syncLinks()
            }
            vehicle = profile
            session.setProfile(profile)
            vehicleStore.saveSelectedId(profile.id)
            codes.clear()
            modules.clear()
            selectedModule = null
            freezeFrame = null
            readiness = null
            liveValues.clear()
            reloadPids()
            reloadHistory()
            snack("Switched to ${profile.displayName}")
            log.info("vehicle ${profile.displayName} · ${profile.vin}")
            if (pane == Pane.Modules && !profile.capabilities.moduleScan) pane = Pane.Diagnostics
            val saved = session.savedAdapter()
            if (saved != null) connect(saved.address, saved.name, fromAuto = true)
        }
    }

    fun onConnectClicked() {
        when (elmState) {
            LinkState.Working -> return
            LinkState.Up -> disconnectUser()
            LinkState.Down -> {
                val saved = session.savedAdapter()
                if (saved != null) connect(saved.address, saved.name, fromAuto = false)
                else {
                    refreshDevices()
                    showDeviceSheet = true
                }
            }
        }
    }

    fun onConnectLongPress() {
        refreshDevices()
        showDeviceSheet = true
    }

    fun pickDevice(device: BtDevice) {
        showDeviceSheet = false
        stopDeviceScan()
        connect(device.address, device.name, fromAuto = false)
    }

    fun refreshDevices() {
        val adapter = session.bluetoothAdapter() ?: return
        val scan = scanner ?: BluetoothScanner(app, adapter).also { scanner = it }
        val bonded = scan.bonded()
        devices.clear()
        devices.addAll(bonded)
    }

    fun startDeviceScan() {
        val adapter = session.bluetoothAdapter() ?: return
        val scan = scanner ?: BluetoothScanner(app, adapter).also { scanner = it }
        scanningDevices = true
        scan.startScan { found ->
            if (devices.none { it.address == found.address }) {
                val insert = if (looksLikeObdAdapter(found.name)) 0 else devices.size
                devices.add(insert, found)
            }
        }
    }

    fun stopDeviceScan() {
        scanningDevices = false
        scanner?.stopScan()
    }

    fun readCodes() {
        viewModelScope.launch {
            flashRead(ActionState.Loading)
            withPausedPoll { doReadCodes() }
        }
    }

    fun confirmClearCodes() {
        showClearConfirm = true
    }

    fun clearCodes() {
        showClearConfirm = false
        viewModelScope.launch {
            flashClear(ActionState.Loading)
            withPausedPoll {
                runCatching { session.clearCodes() }.rethrowCancel()
                    .onSuccess {
                        flashClear(ActionState.Success)
                        snack("Codes cleared")
                        delay(400)
                        flashRead(ActionState.Loading)
                        doReadCodes()
                    }
                    .onFailure {
                        flashClear(ActionState.Error)
                        snack(it.message ?: "Clear failed")
                    }
            }
        }
    }

    fun tapCode(entry: DtcEntry) {
        if (!vehicle.capabilities.freezeFrame) return
        viewModelScope.launch {
            freezeFrame = withPausedPoll {
                runCatching { session.freezeFrame(entry.code) }.rethrowCancel().getOrNull() ?: "No freeze frame"
            }
        }
    }

    fun scanModules() {
        viewModelScope.launch {
            flashScan(ActionState.Loading)
            withPausedPoll {
                runCatching { session.scanModules() }.rethrowCancel()
                    .onSuccess { list ->
                        modules.clear()
                        modules.addAll(list)
                        flashScan(ActionState.Success)
                        snack("Scan done · ${list.count { it.present }} present")
                    }
                    .onFailure {
                        flashScan(ActionState.Error)
                        snack(it.message ?: "Scan failed")
                    }
            }
        }
    }

    fun openModule(item: ModuleScanItem) {
        selectedModule = item
        if (!item.present) return
        viewModelScope.launch {
            withPausedPoll { doOpenModule(item) }
        }
    }

    fun clearSelectedModule() {
        val item = selectedModule ?: return
        viewModelScope.launch {
            withPausedPoll {
                runCatching { session.clearModule(item) }.rethrowCancel()
                    .onSuccess {
                        snack("Cleared ${item.module.name}")
                        doOpenModule(item)
                    }
                    .onFailure { snack(it.message ?: "Module clear failed") }
            }
        }
    }

    private suspend fun doOpenModule(item: ModuleScanItem) {
        runCatching { session.readModule(item) }.rethrowCancel()
            .onSuccess { updated ->
                selectedModule = updated
                val idx = modules.indexOfFirst { it.module.id == updated.module.id }
                if (idx >= 0) modules[idx] = updated
            }
    }

    fun togglePid(id: Int) {
        if (selectedPids.contains(id)) selectedPids.remove(id) else selectedPids.add(id)
        pidStore.save(vehicle.id, selectedPids.toSet())
    }

    private suspend fun doReadCodes() {
        runCatching { session.readCodes() }.rethrowCancel()
            .onSuccess { list ->
                codes.clear()
                codes.addAll(list)
                historyStore.merge(vehicle.id, list.map { it.code to it.title }).also {
                    history.clear()
                    history.addAll(it)
                }
                val mil = runCatching { session.readiness() }.rethrowCancel().getOrNull()
                readiness = mil
                refreshHealth(mil)
                flashRead(ActionState.Success)
                snack(if (list.isEmpty()) "No codes" else "${list.size} code(s)")
            }
            .onFailure {
                flashRead(ActionState.Error)
                snack(it.message ?: "Read failed")
            }
    }

    private fun disconnectUser() {
        connectJob?.cancel()
        viewModelScope.launch {
            stopPoll()
            session.disconnect("user disconnect")
            syncLinks()
            snack("Disconnected")
        }
    }

    private fun connect(address: String, name: String, fromAuto: Boolean) {
        val previous = connectJob
        connectJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            withPausedPoll {
                elmState = LinkState.Working
                try {
                    session.connect(address, name)
                    syncLinks()
                    if (ecuState == LinkState.Up) {
                        pruneSelectedToAdvertised()
                        snack("Connected · ECU up")
                        val mil = runCatching { session.readiness() }.rethrowCancel().getOrNull()
                        readiness = mil
                        refreshHealth(mil)
                        if (pane == Pane.Realtime) startPoll()
                    } else {
                        snack("ELM connected, no ECU (ignition on?)")
                        if (pane == Pane.Realtime) startPoll()
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        session.disconnect("connect cancelled")
                        syncLinks()
                    }
                    throw e
                } catch (e: Exception) {
                    syncLinks()
                    if (e is NeedBluetoothException && e.message?.contains("off") == true) {
                        bluetoothEnableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    }
                    snack(if (fromAuto) "Auto-connect failed: ${e.message}" else (e.message ?: "Connect failed"))
                }
            }
        }
    }

    private suspend fun refreshHealth(mil: String? = null) {
        milText = mil ?: "—"
        val advertised = session.supportedPids
        voltageText = if (advertised.isEmpty() || 0x42 in advertised) {
            session.healthValue(0x42) ?: session.adapterVoltage() ?: "—"
        } else {
            session.adapterVoltage() ?: "—"
        }
        coolantText = session.healthValue(0x05) ?: "—"
        dpfText = if (advertised.isEmpty() || 0x7A in advertised) {
            session.healthValue(0x7A) ?: "—"
        } else {
            "n/a"
        }
    }

    private fun startPoll() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var silentCycles = 0
            var toldSilent = false
            while (true) {
                if (pollPaused.get() > 0) {
                    delay(50)
                    continue
                }
                if (elmState == LinkState.Up) {
                    var anyLost = false
                    var anyValue = false
                    var anyEmpty = false
                    val advertised = session.supportedPids
                    for (id in selectedPids.toList()) {
                        if (pollPaused.get() > 0) break
                        if (advertised.isNotEmpty() && id !in advertised) continue
                        val pid = PidCatalog.forId(id)
                        when (val result = session.readLive(pid, 1500L)) {
                            is PidRead.Value -> {
                                liveValues[id] = result.text
                                anyValue = true
                            }
                            PidRead.Empty -> anyEmpty = true
                            PidRead.Timeout -> Unit
                            PidRead.LinkLost -> anyLost = true
                        }
                    }
                    if (anyLost) {
                        if (session.elmState != LinkState.Down) {
                            session.disconnect("ELM link lost")
                            snack("ELM link lost")
                        }
                        syncLinks()
                        break
                    }
                    if (anyValue) {
                        silentCycles = 0
                        toldSilent = false
                        if (ecuState != LinkState.Up) {
                            session.markEcuUp()
                            syncLinks()
                        }
                    } else if (anyEmpty && selectedPids.isNotEmpty()) {
                        silentCycles++
                        if (silentCycles >= 3 && ecuState == LinkState.Up) {
                            session.markEcuSilent()
                            syncLinks()
                            if (!toldSilent) {
                                snack("ECU silent (ignition on?)")
                                toldSilent = true
                            }
                        }
                    } else {
                        silentCycles = 0
                    }
                }
                delay(400)
            }
        }
    }

    private fun stopPoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun <T> withPausedPoll(block: suspend () -> T): T {
        pollPaused.incrementAndGet()
        try {
            return block()
        } finally {
            pollPaused.decrementAndGet()
        }
    }

    private fun pruneSelectedToAdvertised() {
        val advertised = supportedPidIds()
        if (advertised.isEmpty()) return
        val keep = selectedPids.filter { it in advertised }
        if (keep.isEmpty()) {
            selectDefaultPids()
            return
        }
        if (keep.size != selectedPids.size) {
            selectedPids.clear()
            selectedPids.addAll(keep)
            pidStore.save(vehicle.id, selectedPids.toSet())
        }
    }

    private fun reloadPids() {
        selectedPids.clear()
        val saved = pidStore.load(vehicle.id)
        selectedPids.addAll(saved ?: session.backend.defaultSelectedPidIds)
    }

    private fun reloadHistory() {
        history.clear()
        history.addAll(historyStore.load(vehicle.id))
    }

    private fun syncLinks() {
        elmState = session.elmState
        ecuState = session.ecuState
    }

    private fun snack(text: String) {
        snackbarMessage = text
    }

    private fun flashRead(state: ActionState) = flash(state, { readFlashJob }, { readFlashJob = it }, { readState = it })
    private fun flashClear(state: ActionState) = flash(state, { clearFlashJob }, { clearFlashJob = it }, { clearState = it })
    private fun flashScan(state: ActionState) = flash(state, { scanFlashJob }, { scanFlashJob = it }, { scanState = it })

    private fun flash(
        state: ActionState,
        getJob: () -> Job?,
        setJob: (Job?) -> Unit,
        setter: (ActionState) -> Unit,
    ) {
        getJob()?.cancel()
        setter(state)
        if (state == ActionState.Success || state == ActionState.Error) {
            setJob(
                viewModelScope.launch {
                    delay(1400)
                    setter(ActionState.Idle)
                },
            )
        }
    }

    override fun onCleared() {
        stopPoll()
        stopDeviceScan()
        connectJob?.cancel()
        runBlocking {
            withTimeoutOrNull(2_000) {
                withContext(NonCancellable + Dispatchers.IO) {
                    session.disconnect("app closed")
                }
            }
        }
        super.onCleared()
    }
}
