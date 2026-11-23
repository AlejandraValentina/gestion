package uy.caja.app.ui.scan

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uy.caja.app.data.Articulo
import uy.caja.app.data.CajaDao
import uy.caja.app.money
import uy.caja.app.pagoLabel

@Composable
internal fun VentasScanFlow(jornadaId: Long, fecha: String, catalog: List<Articulo>, dao: CajaDao, onClose: () -> Unit) {
    val context = LocalContext.current
    val scanVm: VentasScanViewModel = viewModel(
        key = "ventas-scan",
        factory = remember(dao, context.applicationContext) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VentasScanViewModel(context.applicationContext as Application, dao) as T
            }
        }
    )
    val state by scanVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (state.jornadaId == 0L) scanVm.start(jornadaId, fecha) }
    BackHandler { scanVm.cancel(); onClose() }
    val sameJornada = state.jornadaId == jornadaId
    Column(Modifier.fillMaxSize()) {
        if (!sameJornada) Text("Cambió la jornada seleccionada. Cancelá y comenzá un escaneo nuevo.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        when (state.step) {
            ScanStep.CAMERA -> CameraCaptureScreen(
                title = "Escanear hoja de ventas", canCapture = sameJornada,
                onPrepareCapture = scanVm::prepareCaptureFile,
                onCaptureResult = scanVm::captureCompleted,
                onCancel = { scanVm.cancel(); onClose() }
            )
            ScanStep.PHOTO -> PhotoRegionScreen(
                photoPath = state.photoPath, crop = state.crop, processing = state.processing, error = state.error,
                title = "Seleccioná la zona de ventas",
                instructions = "Ajustá el marco para incluir las filas con artículos y cantidades.",
                onCropChanged = scanVm::updateCrop, onRetake = scanVm::retake,
                onCancel = { scanVm.cancel(); onClose() },
                onProcess = { scanVm.process(state.crop, catalog) }
            )
            ScanStep.REVIEW -> SalesReviewScreen(
                state, catalog, sameJornada,
                onAdd = scanVm::addManualDraft,
                onUpdate = scanVm::updateDraft,
                onSelectProduct = scanVm::selectProduct,
                onCreateProduct = scanVm::createProductForDraft,
                onRemove = scanVm::removeDraft,
                onPaymentAll = scanVm::applyPaymentToAll,
                onRetake = scanVm::retake,
                onCancel = { scanVm.cancel(); onClose() },
                onConfirm = { scanVm.confirm(jornadaId) }
            )
            ScanStep.COMPLETE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Ventas guardadas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Se incorporaron ${state.savedCount} ventas en la jornada ${state.jornadaFecha}.", modifier = Modifier.padding(vertical = 12.dp))
                Button(onClick = { scanVm.closeSession(); onClose() }) { Text("Volver a Artículos") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalesReviewScreen(
    state: SalesScanState,
    catalog: List<Articulo>,
    sameJornada: Boolean,
    onAdd: () -> Unit,
    onUpdate: (String, (SaleDraft) -> SaleDraft) -> Unit,
    onSelectProduct: (String, Articulo?) -> Unit,
    onCreateProduct: (String, String, Long) -> Unit,
    onRemove: (String) -> Unit,
    onPaymentAll: (String) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var paymentMenu by remember { mutableStateOf(false) }
    var batchPayment by rememberSaveable { mutableStateOf("SIN_CLASIFICAR") }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var createFor by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var newPrice by remember { mutableStateOf("") }
    val selected = state.rows.filter(SaleDraft::included)
    val total = selected.fold(0L) { sum, draft -> draft.subtotal?.let { runCatching { Math.addExact(sum, it) }.getOrNull() } ?: return@fold Long.MIN_VALUE }
    val validTotal = total.takeIf { it >= 0 }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Revisar ventas · ${state.jornadaFecha}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Verificá cada producto y cantidad. Las ventas no se guardan hasta confirmar.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(expanded = paymentMenu, onExpandedChange = { paymentMenu = it }, modifier = Modifier.weight(1f)) {
                OutlinedTextField(value = pagoLabel(batchPayment), onValueChange = {}, readOnly = true, label = { Text("Pago para todos") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(paymentMenu) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true)
                ExposedDropdownMenu(expanded = paymentMenu, onDismissRequest = { paymentMenu = false }) {
                    SaleDraft.PAYMENTS.forEach { payment -> DropdownMenuItem(text = { Text(pagoLabel(payment)) }, onClick = { batchPayment = payment; onPaymentAll(payment); paymentMenu = false }) }
                }
            }
            OutlinedButton(onClick = onAdd) { Text("Agregar") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(state.rows, key = SaleDraft::id) { draft ->
                SaleDraftCard(draft, catalog,
                    onUpdate = { transform -> onUpdate(draft.id, transform) },
                    onSelect = { onSelectProduct(draft.id, it) },
                    onCreate = { createFor = draft.id; newName = draft.productQuery; newPrice = "" },
                    onRemove = { onRemove(draft.id) })
            }
        }
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Filas incluidas: ${selected.size} · Válidas: ${selected.count(SaleDraft::valid)}")
                Text("Total: ${validTotal?.let(::money) ?: "Revisá cantidades y precios"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Repetir foto") }
                    Button(enabled = sameJornada && !state.saving && selected.isNotEmpty() && selected.all(SaleDraft::valid) && validTotal != null,
                        onClick = { showConfirm = true }, modifier = Modifier.weight(1.3f)) {
                        if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Confirmar")
                    }
                }
            }
        }
    }
    if (showConfirm) AlertDialog(
        onDismissRequest = { showConfirm = false }, title = { Text("Guardar ventas") },
        text = { Text("Se guardarán ${selected.size} ventas por ${validTotal?.let(::money)} en la jornada ${state.jornadaFecha}.") },
        confirmButton = { TextButton(onClick = { showConfirm = false; onConfirm() }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Revisar") } }
    )
    if (createFor != null) {
        val price = newPrice.toLongOrNull()
        AlertDialog(
            onDismissRequest = { createFor = null }, title = { Text("Crear artículo") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(newPrice, { newPrice = it.filter(Char::isDigit) }, label = { Text("Precio vigente ($)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            } },
            confirmButton = { TextButton(enabled = newName.isNotBlank() && price != null, onClick = { onCreateProduct(createFor!!, newName, price!!); createFor = null }) { Text("Crear y asociar") } },
            dismissButton = { TextButton(onClick = { createFor = null }) { Text("Cancelar") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaleDraftCard(draft: SaleDraft, catalog: List<Articulo>, onUpdate: ((SaleDraft) -> SaleDraft) -> Unit, onSelect: (Articulo?) -> Unit, onCreate: () -> Unit, onRemove: () -> Unit) {
    var productMenu by remember(draft.id) { mutableStateOf(false) }
    var paymentMenu by remember(draft.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.included, { value -> onUpdate { it.copy(included = value) } })
                Column(Modifier.weight(1f)) {
                    Text("Texto reconocido: ${draft.recognizedText.ifBlank { "(vacío)" }}", style = MaterialTheme.typography.labelLarge)
                    if (draft.candidates.isNotEmpty() && draft.productId == null) Text("Posibles coincidencias: ${draft.candidates.joinToString { it.name }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Quitar fila") }
            }
            ExposedDropdownMenuBox(expanded = productMenu, onExpandedChange = { productMenu = it }) {
                OutlinedTextField(value = draft.productName.ifBlank { "Seleccionar artículo" }, onValueChange = {}, readOnly = true,
                    label = { Text("Artículo") }, isError = draft.included && draft.productId == null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(productMenu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true)
                ExposedDropdownMenu(expanded = productMenu, onDismissRequest = { productMenu = false }) {
                    catalog.forEach { article -> DropdownMenuItem(text = { Text("${article.nombre} · ${money(article.precio)}") }, onClick = { onSelect(article); productMenu = false }) }
                    DropdownMenuItem(text = { Text("Crear artículo nuevo…") }, onClick = { onCreate(); productMenu = false })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft.quantityText, { value -> onUpdate { it.copy(quantityText = value.filter(Char::isDigit)) } }, label = { Text("Cantidad") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = draft.included && draft.quantity == null, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(draft.priceText, { value -> onUpdate { it.copy(priceText = value.filter(Char::isDigit)) } }, label = { Text("Precio c/u ($)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = draft.included && draft.price == null, modifier = Modifier.weight(1f), singleLine = true)
                Text(draft.subtotal?.let(::money) ?: "—", style = MaterialTheme.typography.labelLarge)
            }
            ExposedDropdownMenuBox(expanded = paymentMenu, onExpandedChange = { paymentMenu = it }) {
                OutlinedTextField(value = pagoLabel(draft.payment), onValueChange = {}, readOnly = true, label = { Text("Medio de pago") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(paymentMenu) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(), singleLine = true)
                ExposedDropdownMenu(expanded = paymentMenu, onDismissRequest = { paymentMenu = false }) {
                    SaleDraft.PAYMENTS.forEach { payment -> DropdownMenuItem(text = { Text(pagoLabel(payment)) }, onClick = { onUpdate { it.copy(payment = payment) }; paymentMenu = false }) }
                }
            }
            if (draft.issues.isNotEmpty()) Text(draft.issues.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
