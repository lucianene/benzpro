package app.benzpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benzpro.BenzProViewModel
import app.benzpro.log.LogKind
import app.benzpro.session.ActionState
import app.benzpro.session.LinkState
import app.benzpro.ui.theme.ErrorRed
import app.benzpro.ui.theme.InfoGray
import app.benzpro.ui.theme.LogBg
import app.benzpro.ui.theme.RxGreen
import app.benzpro.ui.theme.SuccessGreen
import app.benzpro.ui.theme.TxCyan
import app.benzpro.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsPane(viewModel: BenzProViewModel, modifier: Modifier = Modifier) {
    val logState = rememberLazyListState()
    LaunchedEffect(viewModel.logLines.size) {
        if (viewModel.logLines.isNotEmpty()) {
            logState.scrollToItem(viewModel.logLines.lastIndex)
        }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        if (viewModel.vehicle.capabilities.healthStrip) {
            HealthStrip(viewModel)
            Spacer(Modifier.height(10.dp))
        } else if (viewModel.ecuState != LinkState.Up) {
            Text(
                viewModel.vehicle.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton("Read codes", viewModel.readState, Modifier.weight(1f)) { viewModel.readCodes() }
            ActionButton("Reset codes", viewModel.clearState, Modifier.weight(1f), destructive = true) {
                viewModel.confirmClearCodes()
            }
        }
        Text(
            "Read codes pulls SAE Mode 03/07/0A and CDI manufacturer memory. Mode 03 is often empty on this car.",
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (viewModel.readiness != null && !viewModel.vehicle.capabilities.healthStrip) {
            Text(
                "Readiness: ${viewModel.readiness}",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (viewModel.freezeFrame != null) {
            Text(
                "Freeze: ${viewModel.freezeFrame}",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = WarnAmber,
            )
        }
        if (viewModel.codes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                viewModel.codes.forEach { dtc ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.tapCode(dtc) }
                            .padding(vertical = 6.dp),
                    ) {
                        Text("${dtc.code}  ${dtc.status}", fontWeight = FontWeight.SemiBold)
                        Text(dtc.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (dtc.ftb != null) {
                            Text(dtc.ftb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (dtc.advice != null) {
                            Text(dtc.advice, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
                        }
                    }
                }
            }
        }
        if (viewModel.history.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("History", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            viewModel.history.take(8).forEach { item ->
                Text(
                    "${item.code} · ${item.title}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Log", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.copyLog() }) { Text("Copy") }
                TextButton(onClick = { viewModel.exportLog() }) { Text("Export") }
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(
            state = logState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LogBg)
                .padding(8.dp),
        ) {
            items(viewModel.logLines, key = { it.id }) { line ->
                Text(
                    "${timeFmt.format(Date(line.atMs))}  ${line.text}",
                    color = colorFor(line.kind),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun HealthStrip(viewModel: BenzProViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HealthCell("MIL", viewModel.milText)
        HealthCell("Volt", viewModel.voltageText)
        HealthCell("Coolant", viewModel.coolantText)
        HealthCell("DPF", viewModel.dpfText)
    }
}

@Composable
private fun HealthCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ActionButton(
    label: String,
    state: ActionState,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val text = when (state) {
        ActionState.Idle -> label
        ActionState.Loading -> "Working…"
        ActionState.Success -> "OK"
        ActionState.Error -> "Error"
    }
    val colors = when (state) {
        ActionState.Success -> ButtonDefaults.buttonColors(containerColor = SuccessGreen)
        ActionState.Error -> ButtonDefaults.buttonColors(containerColor = ErrorRed)
        else -> if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        else ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = state != ActionState.Loading,
        colors = colors,
    ) {
        if (state == ActionState.Loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text(text)
        }
    }
}

private fun colorFor(kind: LogKind): Color = when (kind) {
    LogKind.Tx -> TxCyan
    LogKind.Rx -> RxGreen
    LogKind.Info -> InfoGray
    LogKind.Warn -> WarnAmber
    LogKind.Error -> ErrorRed
    LogKind.Success -> SuccessGreen
}
