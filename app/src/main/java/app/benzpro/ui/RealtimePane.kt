package app.benzpro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.benzpro.BenzProViewModel

@Composable
fun RealtimePane(viewModel: BenzProViewModel, modifier: Modifier = Modifier) {
    val pids = viewModel.livePidList()
    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedButton(onClick = { viewModel.showPidPicker = true }) {
            val n = viewModel.supportedPidIds().size
            Text(if (n == 0) "Select sensors" else "Select sensors · $n advertised")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(viewModel.selectedPids.toList(), key = { it }) { id ->
                val pid = pids.firstOrNull { it.id == id }
                val label = pid?.label ?: "PID %02X".format(id)
                val unit = pid?.unit.orEmpty()
                val value = viewModel.liveValues[id] ?: "—"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Text(
                        if (unit.isBlank()) value else "$value $unit",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}
