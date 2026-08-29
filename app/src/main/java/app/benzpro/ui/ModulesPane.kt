package app.benzpro.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.benzpro.BenzProViewModel
import app.benzpro.session.ActionState

@Composable
fun ModulesPane(viewModel: BenzProViewModel, modifier: Modifier = Modifier) {
    val selected = viewModel.selectedModule
    Column(modifier.fillMaxSize().padding(12.dp)) {
        ActionButton("Scan modules", viewModel.scanState) { viewModel.scanModules() }
        Spacer(Modifier.height(8.dp))
        if (selected != null) {
            Text(selected.module.name, fontWeight = FontWeight.SemiBold)
            if (!selected.identity.isNullOrBlank()) {
                Text(selected.identity, style = MaterialTheme.typography.bodySmall)
            }
            if (selected.present) {
                ActionButton("Clear module codes", ActionState.Idle, destructive = true) {
                    viewModel.clearSelectedModule()
                }
                selected.codes.forEach { dtc ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${dtc.code}  ${dtc.status}", fontWeight = FontWeight.Medium)
                        Text(dtc.title, style = MaterialTheme.typography.bodySmall)
                        if (dtc.ftb != null) {
                            Text(dtc.ftb, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (dtc.advice != null) {
                            Text(dtc.advice, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE3B341))
                        }
                    }
                }
                if (selected.codes.isEmpty()) {
                    Text("No DTCs on this module", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    "No response — ignition on? clone ISO-TP?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(viewModel.modules, key = { it.module.id }) { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.openModule(item) }
                        .padding(vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.module.name, fontWeight = FontWeight.Medium)
                        Text(
                            if (item.present) "present · ${item.dtcCount ?: 0} DTCs"
                            else if (item.module.expected) "expected, no response"
                            else "not present",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.present) Color(0xFF3FB950)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (item.module.expected) 1f else 0.6f,
                            ),
                        )
                    }
                }
            }
        }
    }
}
