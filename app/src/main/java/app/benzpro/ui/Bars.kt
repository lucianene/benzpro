package app.benzpro.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benzpro.BenzProViewModel
import app.benzpro.session.LinkState
import app.benzpro.ui.theme.ConnectGreen
import app.benzpro.ui.theme.DisconnectRed

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BenzTopBar(viewModel: BenzProViewModel, onConnect: () -> Unit, onConnectLongPress: () -> Unit) {
    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { viewModel.showGarage = true }),
                ) {
                    Text(viewModel.vehicle.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        viewModel.vehicle.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusChip("ELM", viewModel.elmState)
                Spacer(Modifier.width(6.dp))
                StatusChip("ECU", viewModel.ecuState)
                Spacer(Modifier.width(8.dp))
                ConnectButton(
                    state = viewModel.elmState,
                    onClick = onConnect,
                    onLongClick = onConnectLongPress,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, state: LinkState) {
    val color = when (state) {
        LinkState.Down -> Color(0xFF6E7681)
        LinkState.Working -> Color(0xFFE3B341)
        LinkState.Up -> Color(0xFF3FB950)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Spacer(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectButton(
    state: LinkState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val (label, color, enabled) = when (state) {
        LinkState.Down -> Triple("Connect", ConnectGreen, true)
        LinkState.Working -> Triple("Connecting…", Color(0xFFE3B341), false)
        LinkState.Up -> Triple("Disconnect", DisconnectRed, true)
    }
    Surface(
        color = color,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.combinedClickable(
            enabled = enabled || state == LinkState.Working,
            onClick = { if (enabled) onClick() },
            onLongClick = onLongClick,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (state == LinkState.Working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.Black,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun BenzBottomBar(viewModel: BenzProViewModel) {
    val tabs = buildList {
        add(PaneTab("Diagnostics", app.benzpro.session.Pane.Diagnostics))
        if (viewModel.vehicle.capabilities.moduleScan) {
            add(PaneTab("Modules", app.benzpro.session.Pane.Modules))
        }
        add(PaneTab("Realtime", app.benzpro.session.Pane.Realtime))
    }
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 4.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { tab ->
                val selected = viewModel.pane == tab.pane
                TextButton(onClick = { viewModel.selectPane(tab.pane) }) {
                    Text(
                        tab.label,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private data class PaneTab(val label: String, val pane: app.benzpro.session.Pane)
