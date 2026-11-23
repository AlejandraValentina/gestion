package uy.caja.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uy.caja.app.data.Habitacion
import uy.caja.app.ui.CajaViewModel

@Composable
fun HabitacionesScreen(habitaciones: List<Habitacion>, vm: CajaViewModel, onScan: () -> Unit) {
    var editar by remember { mutableStateOf<Habitacion?>(null) }
    var nueva by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf<Habitacion?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Habitaciones", style = MaterialTheme.typography.titleLarge)
                Text("${habitaciones.size} ingresos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Escanear cuaderno", maxLines = 1, style = MaterialTheme.typography.labelLarge)
            }
            Button(onClick = { nueva = true }) { Text("Agregar") }
        }
        if (habitaciones.isEmpty()) MensajeVacio("Agregá el primer ingreso de esta jornada.")
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(habitaciones, key = { it.id }) { item ->
                OutlinedCard(onClick = { editar = item }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Habitación ${item.numero}", style = MaterialTheme.typography.titleMedium)
                            if (item.persona.isNotBlank()) Text(item.persona, style = MaterialTheme.typography.bodyMedium)
                            Text(item.pago.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(money(item.importe), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { editar = item }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "Editar") }
                        IconButton(onClick = { borrar = item }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.DeleteOutline, "Eliminar") }
                    }
                }
            }
        }
    }
    if (nueva || editar != null) {
        HabitacionEditor(editar, onDismiss = { nueva = false; editar = null }) { numero, persona, importe, pago ->
            vm.guardarHabitacion(editar?.id, numero, persona, importe, pago)
            nueva = false; editar = null
        }
    }
    borrar?.let { item ->
        AlertDialog(
            onDismissRequest = { borrar = null },
            title = { Text("Eliminar ingreso") },
            text = { Text("¿Eliminar el ingreso de la habitación ${item.numero}?") },
            confirmButton = { TextButton(onClick = { vm.eliminarHabitacion(item); borrar = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { borrar = null }) { Text("Cancelar") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitacionEditor(item: Habitacion?, onDismiss: () -> Unit, onSave: (String, String, Long, String) -> Unit) {
    var numero by remember(item?.id) { mutableStateOf(item?.numero.orEmpty()) }
    var persona by remember(item?.id) { mutableStateOf(item?.persona.orEmpty()) }
    var importe by remember(item?.id) { mutableStateOf(item?.importe?.toString().orEmpty()) }
    var pago by remember(item?.id) { mutableStateOf(item?.pago ?: "SIN_CLASIFICAR") }
    var pagoMenu by remember { mutableStateOf(false) }
    val amount = importe.toLongOrNull()
    val error = when {
        numero.isBlank() -> "Ingresá el número de habitación."
        amount == null -> "Ingresá un importe entero."
        amount < 0 -> "El importe no puede ser negativo."
        else -> null
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Nuevo ingreso" else "Editar ingreso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(numero, { numero = it }, label = { Text("Habitación") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(persona, { persona = it }, label = { Text("Persona · opcional") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    importe, { importe = it.filter(Char::isDigit) }, label = { Text("Importe en pesos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(expanded = pagoMenu, onExpandedChange = { pagoMenu = it }) {
                    OutlinedTextField(
                        pagoLabel(pago), {}, readOnly = true, label = { Text("Medio de pago") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pagoMenu) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = pagoMenu, onDismissRequest = { pagoMenu = false }) {
                        listOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR").forEach { valor ->
                            DropdownMenuItem(text = { Text(pagoLabel(valor)) }, onClick = { pago = valor; pagoMenu = false })
                        }
                    }
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { keyboard?.hide(); onSave(numero, persona, amount!!, pago) }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

