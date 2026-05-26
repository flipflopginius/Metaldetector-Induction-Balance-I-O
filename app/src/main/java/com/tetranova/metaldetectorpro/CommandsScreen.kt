package com.tetranova.metaldetectorpro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast

enum class CommandType { ACTION, INT_RANGE, FLOAT_RANGE }

data class CommandDef(
    val id: String,
    val name: String,
    val description: String,
    val command: String,
    val type: CommandType,
    val range: IntRange? = null,
    val rangeFloat: ClosedFloatingPointRange<Float>? = null,
    val unit: String = "",
    val isLocal: Boolean = false,
    val currentValueProvider: () -> Any = { 0 },
    val onValueChanged: ((Any) -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(vm: DetectorViewModelV2) {
    val connectionStatus by vm.connectionStatus.collectAsState()
    val isConnected = connectionStatus is ConnectionStatus.Connected
    val scope = rememberCoroutineScope()

    var showDialog by remember { mutableStateOf(false) }
    var selectedCommand by remember { mutableStateOf<CommandDef?>(null) }
    var inputValue by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }

    val commands = remember {
        listOf(
            CommandDef(id = "freq", name = "Frequenza TX", description = "Frequenza di lavoro della bobina",
                command = "SETFREQ", type = CommandType.INT_RANGE, range = 1000..50000, unit = "Hz",
                isLocal = false, currentValueProvider = { vm.params.frequency.toInt() }),
            CommandDef(id = "duty", name = "Duty Cycle", description = "Percentuale duty cycle TX (10-90%)",
                command = "SETDUTY", type = CommandType.INT_RANGE, range = 10..90, unit = "%",
                isLocal = false, currentValueProvider = { 40 }),
            CommandDef(id = "calibra", name = "Calibrazione", description = "Avvia calibrazione vettoriale I/Q",
                command = "CALIBRATE", type = CommandType.ACTION, isLocal = false),
            CommandDef(id = "reboot", name = "Riavvia ESP32", description = "Riavvia il microcontrollore",
                command = "REBOOT", type = CommandType.ACTION, isLocal = false),
            CommandDef(id = "sens", name = "Sensibilità", description = "Regola la sensibilità vettoriale (1-10)",
                command = "set sens", type = CommandType.INT_RANGE, range = 1..10, isLocal = true,
                currentValueProvider = { (10f - (vm.vectorProcessor.K_VECT - 1.2f) * (9f / 4.8f)).toInt().coerceIn(1, 10) },
                onValueChanged = { newVal -> vm.updateSensAmpiezza((newVal as Int).toFloat()) }),
            CommandDef(id = "disc", name = "Discriminazione", description = "Soglia angolare per metalli ferrosi (0.5°–5.0°)",
                command = "set disc", type = CommandType.FLOAT_RANGE, rangeFloat = 0.5f..5.0f, isLocal = true,
                currentValueProvider = { vm.sensFase },
                onValueChanged = { newVal -> vm.updateSensFase(newVal as Float) }
            )
        )
    }

    if (showDialog && selectedCommand != null) {
        val cmd = selectedCommand!!
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(cmd.name) },
            text = {
                Column {
                    Text(cmd.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (cmd.type == CommandType.ACTION) {
                        val confirmMsg = if (cmd.command == "REBOOT") "Confermare il riavvio dell'ESP32?" else "Avviare la procedura?"
                        Text(confirmMsg, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        OutlinedTextField(
                            value = inputValue,
                            onValueChange = { inputValue = it; inputError = validateInput(it, cmd) },
                            label = {
                                Text(when (cmd.type) {
                                    CommandType.INT_RANGE -> "Valore (${cmd.range!!.first}-${cmd.range.last})"
                                    CommandType.FLOAT_RANGE -> "Valore (${cmd.rangeFloat!!.start}-${cmd.rangeFloat!!.endInclusive})"
                                    else -> ""
                                })
                            },
                            isError = inputError != null,
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        if (inputError != null) Text(text = inputError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (cmd.type == CommandType.ACTION || (inputError == null && inputValue.isNotBlank())) {
                            executeCommand(cmd, if (cmd.type != CommandType.ACTION) inputValue else null, vm, isConnected, scope)
                            showDialog = false
                        }
                    },
                    enabled = cmd.type == CommandType.ACTION || (inputError == null && inputValue.isNotBlank())
                ) { Text("Conferma") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Annulla") } }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Comandi Metal Detector", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            text = "🔌 USB",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isConnected) Color.Green else Color.Red,
                            modifier = Modifier.width(80.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Firmware: ${vm.params.transportMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "🔋 ${vm.params.battery}V | 📡 ${vm.params.frequency.toInt()}Hz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(if (isConnected) "✅" else "❌", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }

        items(commands) { cmd ->
            CommandCard(command = cmd, isConnected = isConnected, onAction = {
                selectedCommand = cmd
                inputValue = when (val c = cmd.currentValueProvider()) {
                    is Float -> c.toString()
                    is Int -> c.toString()
                    else -> ""
                }
                inputError = validateInput(inputValue, cmd)
                showDialog = true
            })
        }
    }
}

private fun validateInput(value: String, cmd: CommandDef): String? {
    if (value.isBlank()) return "Inserire un valore"
    return when (cmd.type) {
        CommandType.INT_RANGE -> {
            val intVal = value.toIntOrNull()
            if (intVal == null) "Deve essere un numero intero"
            else if (intVal !in cmd.range!!) "Valore fuori range (${cmd.range.first}-${cmd.range.last})"
            else null
        }
        CommandType.FLOAT_RANGE -> {
            val floatVal = value.toFloatOrNull()
            if (floatVal == null) "Deve essere un numero"
            else if (floatVal !in cmd.rangeFloat!!) "Valore fuori range"
            else null
        }
        else -> null
    }
}

private fun executeCommand(cmd: CommandDef, inputValue: String?, vm: DetectorViewModelV2, isConnected: Boolean, scope: CoroutineScope) {
    when (cmd.type) {
        CommandType.ACTION -> {
            if (cmd.isLocal) { cmd.onValueChanged?.invoke(true); vm.console.add(0, "Azione locale: ${cmd.name}") }
            else if (isConnected) { scope.launch { vm.sendCommand(cmd.command) }; vm.console.add(0, "➡️ Inviato: ${cmd.command}") }
            else vm.console.add(0, "❌ Non connesso")
        }
        CommandType.INT_RANGE -> {
            val value = inputValue?.toIntOrNull() ?: return
            if (cmd.isLocal) cmd.onValueChanged?.invoke(value)
            else if (isConnected) { scope.launch { vm.sendCommand("${cmd.command} $value") }; vm.console.add(0, "➡️ Inviato: ${cmd.command} $value") }
            else vm.console.add(0, "❌ Non connesso")
        }
        CommandType.FLOAT_RANGE -> {
            val value = inputValue?.toFloatOrNull() ?: return
            if (cmd.isLocal) cmd.onValueChanged?.invoke(value)
            else if (isConnected) { scope.launch { vm.sendCommand("${cmd.command} $value") }; vm.console.add(0, "➡️ Inviato: ${cmd.command} $value") }
            else vm.console.add(0, "❌ Non connesso")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandCard(command: CommandDef, isConnected: Boolean, onAction: () -> Unit) {
    val isEspCommand = !command.isLocal
    val isEnabled = command.isLocal || isConnected
    Card(
        modifier = Modifier.fillMaxWidth(), onClick = { if (isEnabled) onAction() }, enabled = isEnabled,
        colors = CardDefaults.cardColors(containerColor = if (isEspCommand) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = command.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    if (isEspCommand) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(onClick = {}, label = { Text("ESP") }, leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
                Text(text = command.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (command.type != CommandType.ACTION) {
                    Text(text = "Corrente: ${command.currentValueProvider()} ${command.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.Default.Settings, null, tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
        }
    }
}