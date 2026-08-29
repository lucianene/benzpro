package app.benzpro.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.benzpro.BenzProViewModel
import app.benzpro.session.LinkState
import app.benzpro.session.Pane

@Composable
fun BenzProScreen(viewModel: BenzProViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val view = LocalView.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.onConnectClicked()
    }
    val startPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.onAppStart()
    }
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.onConnectLongPress()
    }
    val btEnableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onConnectClicked()
    }

    LaunchedEffect(viewModel.snackbarMessage) {
        val msg = viewModel.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeSnackbar()
    }
    LaunchedEffect(viewModel.bluetoothEnableIntent) {
        val intent = viewModel.bluetoothEnableIntent ?: return@LaunchedEffect
        viewModel.consumeBluetoothEnable()
        btEnableLauncher.launch(intent)
    }
    LaunchedEffect(Unit) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        startPermissionLauncher.launch(viewModel.connectPermissions())
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BenzTopBar(
                viewModel = viewModel,
                onConnect = {
                    when (viewModel.elmState) {
                        LinkState.Up -> viewModel.onConnectClicked()
                        LinkState.Working -> Unit
                        LinkState.Down -> permissionLauncher.launch(viewModel.connectPermissions())
                    }
                },
                onConnectLongPress = {
                    scanPermissionLauncher.launch(viewModel.scanPermissions())
                },
            )
        },
        bottomBar = { BenzBottomBar(viewModel) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
        when (viewModel.pane) {
            Pane.Diagnostics -> DiagnosticsPane(viewModel, modifier)
            Pane.Modules -> ModulesPane(viewModel, modifier)
            Pane.Realtime -> RealtimePane(viewModel, modifier)
        }
    }

    if (viewModel.showDeviceSheet) DeviceSheet(viewModel)
    if (viewModel.showGarage) GarageSheet(viewModel)
    if (viewModel.showClearConfirm) ClearConfirmDialog(viewModel)
    if (viewModel.showPidPicker) PidPickerDialog(viewModel)
}
