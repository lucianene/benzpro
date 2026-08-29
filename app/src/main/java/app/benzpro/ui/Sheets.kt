package app.benzpro.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.benzpro.BenzProViewModel
import app.benzpro.session.LinkState
import app.benzpro.transport.looksLikeObdAdapter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(viewModel: BenzProViewModel) {
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) viewModel.startDeviceScan()
    }
    val canScan = viewModel.elmState == LinkState.Down
    ModalBottomSheet(
        onDismissRequest = {
            viewModel.stopDeviceScan()
            viewModel.showDeviceSheet = false
        },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("OBD adapter", style = MaterialTheme.typography.titleMedium)
            Text(
                "Long-press Connect anytime to change adapter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { scanLauncher.launch(viewModel.scanPermissions()) },
                enabled = canScan && !viewModel.scanningDevices,
            ) {
                Text(
                    when {
                        !canScan -> "Disconnect to scan"
                        viewModel.scanningDevices -> "Scanning…"
                        else -> "Scan"
                    },
                )
            }
            LazyColumn {
                items(viewModel.devices, key = { it.address }) { device ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.pickDevice(device) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            device.name,
                            fontWeight = if (looksLikeObdAdapter(device.name)) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            device.address + if (device.bonded) " · paired" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageSheet(viewModel: BenzProViewModel) {
    ModalBottomSheet(onDismissRequest = { viewModel.showGarage = false }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Garage", style = MaterialTheme.typography.titleMedium)
            viewModel.vehicles.forEach { profile ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.selectVehicle(profile) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        profile.displayName,
                        fontWeight = if (profile.id == viewModel.vehicle.id) FontWeight.Bold else FontWeight.Medium,
                    )
                    Text(profile.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(profile.vin, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ClearConfirmDialog(viewModel: BenzProViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.showClearConfirm = false },
        title = { Text("Clear codes?") },
        text = {
            Text("This clears SAE Mode 04 memory (lamp / freeze frame). Permanent emissions DTCs often stay until the CDI finishes its tests. Manufacturer codes in CDI can also be leftover after a repair (timing chain, intake). Clearing is OK if the job is done — if they return, the fault is still there. Do not use this as a DPF regen or SRS reset.")
        },
        confirmButton = {
            TextButton(onClick = { viewModel.clearCodes() }) { Text("Clear") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.showClearConfirm = false }) { Text("Cancel") }
        },
    )
}

@Composable
fun PidPickerDialog(viewModel: BenzProViewModel) {
    val pids = viewModel.livePidList()
    val advertised = viewModel.supportedPidIds()
    AlertDialog(
        onDismissRequest = { viewModel.showPidPicker = false },
        title = { Text(if (advertised.isEmpty()) "Sensors" else "Sensors · ${advertised.size} advertised") },
        text = {
            Column {
                Row {
                    TextButton(onClick = { viewModel.selectAllVisiblePids() }) {
                        Text(if (advertised.isEmpty()) "Select all" else "All advertised")
                    }
                    TextButton(onClick = { viewModel.selectDefaultPids() }) { Text("Defaults") }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(pids, key = { it.id }) { pid ->
                        val checked = viewModel.selectedPids.contains(pid.id)
                        val known = advertised.isNotEmpty() && pid.id <= 0xFF
                        val enabled = !known || pid.id in advertised
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = enabled) { viewModel.togglePid(pid.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = enabled,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    pid.label,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                                )
                                Text(
                                    "%02X".format(pid.id),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.showPidPicker = false }) { Text("Done") }
        },
    )
}
