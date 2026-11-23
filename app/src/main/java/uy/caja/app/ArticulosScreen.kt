package uy.caja.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import uy.caja.app.data.Articulo
import uy.caja.app.data.VentaDb
import uy.caja.app.ui.CajaViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticulosScreen(articulos: List<Articulo>, ventas: List<VentaDb>, vm: CajaViewModel, onScanSales: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Articulo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf<Articulo?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Artículos", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (selectedTab == 0) Button(onClick = { editing = null; creating = true }) { Text("Nuevo") }
        }
        if (selectedTab == 1) {
            OutlinedButton(onClick = onScanSales, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) { Text("Escanear hoja de ventas") }
        }
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text("Catálogo (${articulos.size})") })
            Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text("Ventas (${ventas.size})") })
        }
        if (selectedTab == 0) {
            if (articulos.isEmpty()) MensajeVacio("Agregá productos y sus precios para registrar ventas.")
            else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(articulos, key = { it.id }) { item ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.nombre, style = MaterialTheme.typography.titleMedium)
                                Text(money(item.precio), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = item }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Edit, "Editar precio") }
                            IconButton(onClick = { archiving = item }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Archive, "Desactivar producto") }
                        }
                    }
                }
            }
        } else {
            VentasScreen(articulos, ventas, vm)
        }
    }
    if (creating || editing != null) {
        ArticuloEditor(editing, onDismiss = { creating = false; editing = null }) { nombre, precio ->
            vm.guardarArticulo(editing?.id, nombre, precio)
            creating = false; editing = null
        }
    }
    archiving?.let { item ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text("Quitar del catálogo") },
            text = { Text("${item.nombre} dejará de aparecer para nuevas ventas. Las ventas anteriores conservarán su precio.") },
            confirmButton = { TextButton(onClick = { vm.eliminarArticulo(item.id); archiving = null }) { Text("Quitar") } },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("Cancelar") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VentasScreen(articulos: List<Articulo>, ventas: List<VentaDb>, vm: CajaViewModel) {
    var articuloId by remember(articulos) { mutableStateOf(articulos.firstOrNull()?.id) }
    var cantidad by remember { mutableStateOf("1") }
    var pago by remember { mutableStateOf("EFECTIVO") }
    var productoMenu by remember { mutableStateOf(false) }
    var pagoMenu by remember { mutableStateOf(false) }
    val selected = articulos.firstOrNull { it.id == articuloId }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        if (articulos.isEmpty()) MensajeVacio("Primero agregá productos en la pestaña Catálogo.")
        else {
            Text("Registrar venta", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = productoMenu, onExpandedChange = { productoMenu = it }) {
                OutlinedTextField(
                    selected?.let { "${it.nombre} · ${money(it.precio)}" }.orEmpty(), {}, readOnly = true,
                    label = { Text("Producto") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(productoMenu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = productoMenu, onDismissRequest = { productoMenu = false }) {
                    articulos.forEach { item ->
                        DropdownMenuItem(text = { Text("${item.nombre} · ${money(item.precio)}") }, onClick = { articuloId = item.id; productoMenu = false })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    cantidad, { cantidad = it.filter(Char::isDigit) }, label = { Text("Cantidad") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() })
                )
                ExposedDropdownMenuBox(expanded = pagoMenu, onExpandedChange = { pagoMenu = it }, modifier = Modifier.weight(1.5f)) {
                    OutlinedTextField(
                        pagoLabel(pago), {}, readOnly = true, label = { Text("Pago") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(pagoMenu) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true
                    )
                    ExposedDropdownMenu(expanded = pagoMenu, onDismissRequest = { pagoMenu = false }) {
                        listOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR").forEach { value ->
                            DropdownMenuItem(text = { Text(pagoLabel(value)) }, onClick = { pago = value; pagoMenu = false })
                        }
                    }
                }
            }
            val qty = cantidad.toIntOrNull()?.takeIf { it > 0 }
            val subtotal = if (qty != null && selected != null) runCatching { Math.multiplyExact(qty.toLong(), selected.precio) }.getOrNull() else null
            Text("Subtotal: ${subtotal?.let(::money) ?: "—"}", Modifier.align(Alignment.End).padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = { if (selected != null && qty != null) { vm.registrarVenta(selected, qty, pago); cantidad = "1" } },
                enabled = selected != null && qty != null && subtotal != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Registrar venta") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
        }
        Text("Ventas de la jornada", style = MaterialTheme.typography.titleMedium)
        if (ventas.isEmpty()) MensajeVacio("Todavía no hay ventas en esta jornada.", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ventas, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text("${item.cantidad} × ${item.articuloNombre}") },
                    supportingContent = { Text("${pagoLabel(item.pago)} · ${money(item.precioAplicado)} c/u") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(money(Math.multiplyExact(item.cantidad.toLong(), item.precioAplicado)), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            IconButton(onClick = { vm.eliminarVenta(item) }) { Icon(Icons.Outlined.DeleteOutline, "Eliminar venta") }
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ArticuloEditor(item: Articulo?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit) {
    var nombre by remember(item?.id) { mutableStateOf(item?.nombre.orEmpty()) }
    var precio by remember(item?.id) { mutableStateOf(item?.precio?.toString().orEmpty()) }
    val parsed = precio.toLongOrNull()
    val error = when {
        nombre.isBlank() -> "Ingresá el nombre del producto."
        parsed == null -> "Ingresá un precio entero."
        parsed < 0 -> "El precio no puede ser negativo."
        else -> null
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Nuevo artículo" else "Editar artículo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nombre, { nombre = it }, label = { Text("Nombre") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    precio, { precio = it.filter(Char::isDigit) }, label = { Text("Precio en pesos") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }), modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = error == null, onClick = { onSave(nombre, parsed!!); keyboard?.hide() }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

