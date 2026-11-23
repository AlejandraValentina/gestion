package uy.caja.app.ui.scan

import android.Manifest
import androidx.activity.compose.BackHandler
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.caja.app.data.CajaDao
import uy.caja.app.data.Habitacion
import uy.caja.app.domain.scan.NormalizedRegion
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class CropDragMode { CREATE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, NONE }

@Composable
internal fun HabitacionesScanFlow(
    jornadaId: Long,
    fecha: String,
    habitaciones: List<Habitacion>,
    dao: CajaDao,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scanVm: HabitacionesScanViewModel = viewModel(
        key = "habitaciones-scan",
        factory = remember(dao, context.applicationContext) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    HabitacionesScanViewModel(context.applicationContext as android.app.Application, dao) as T
            }
        }
    )
    val state by scanVm.state.collectAsState()
    LaunchedEffect(Unit) {
        if (state.jornadaId == 0L) scanVm.start(jornadaId, fecha)
    }
    BackHandler {
        scanVm.cancel()
        onClose()
    }
    val sameJornada = state.jornadaId == jornadaId

    Column(Modifier.fillMaxSize()) {
        if (!sameJornada) {
            Text(
                "Cambió la jornada seleccionada. Cancelá y empezá un escaneo nuevo para evitar guardar en una fecha equivocada.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
        when (state.step) {
            ScanStep.CAMERA -> CameraCaptureScreen(
                canCapture = sameJornada,
                onPrepareCapture = scanVm::prepareCaptureFile,
                onCaptureResult = scanVm::captureCompleted,
                onCancel = { scanVm.cancel(); onClose() }
            )
            ScanStep.PHOTO -> PhotoRegionScreen(
                state = state,
                onCropChanged = scanVm::updateCrop,
                onRetake = scanVm::retake,
                onCancel = { scanVm.cancel(); onClose() },
                onProcess = { scanVm.process(state.crop) }
            )
            ScanStep.REVIEW -> ReviewRecordsScreen(
                state = state,
                existingHabitaciones = habitaciones,
                sameJornada = sameJornada,
                onUpdate = scanVm::updateDraft,
                onAdd = scanVm::addManualDraft,
                onRemove = scanVm::removeDraft,
                onRetake = scanVm::retake,
                onCancel = { scanVm.cancel(); onClose() },
                onConfirm = { scanVm.confirm(jornadaId) }
            )
            ScanStep.COMPLETE -> ScanCompleteScreen(state.savedCount) {
                scanVm.closeSession()
                onClose()
            }
        }
    }
}

@Composable
private fun CameraCaptureScreen(
    canCapture: Boolean,
    onPrepareCapture: () -> File,
    onCaptureResult: (File, Boolean, String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by rememberSaveable { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        cameraError = if (granted) null else "Se necesita permiso de cámara para fotografiar el cuaderno."
    }

    LaunchedEffect(hasPermission, lifecycleOwner, previewView, imageCapture) {
        if (!hasPermission) return@LaunchedEffect
        cameraReady = false
        cameraError = null
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val cameraProvider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
                    provider = cameraProvider
                    cameraReady = true
                } catch (error: Exception) {
                    cameraError = "No se pudo abrir la cámara. ${error.localizedMessage.orEmpty()}".trim()
                    cameraReady = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (error: Exception) {
            cameraError = "La cámara no está disponible. ${error.localizedMessage.orEmpty()}".trim()
        }
    }
    val boundProvider = provider
    DisposableEffect(boundProvider) {
        onDispose { boundProvider?.unbindAll() }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Escanear cuaderno", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Enfocá la página y evitá sombras. Después vas a elegir qué zona leer.", style = MaterialTheme.typography.bodyMedium)
        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, RoundedCornerShape(16.dp))
            )
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("La cámara necesita permiso para capturar la página.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Permitir cámara") }
            }
        }
        cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            Button(
                enabled = hasPermission && cameraReady && canCapture && !capturing,
                onClick = {
                    capturing = true
                    val file = try { onPrepareCapture() } catch (error: Exception) {
                        cameraError = error.localizedMessage ?: "No se pudo preparar el archivo de captura."
                        capturing = false
                        return@Button
                    }
                    imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(options, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            capturing = false
                            onCaptureResult(file, true, null)
                        }
                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            onCaptureResult(file, false, exception.localizedMessage)
                        }
                    })
                },
                modifier = Modifier.weight(1f)
            ) {
                if (capturing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Fotografiar")
            }
        }
    }
}

@Composable
private fun PhotoRegionScreen(
    state: ScanUiState,
    onCropChanged: (NormalizedRegion) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onProcess: () -> Unit
) {
    val path = state.photoPath
    val bitmapState = produceState<Result<Bitmap>?>(initialValue = null, key1 = path) {
        value = if (path == null) null else runCatching { withContext(Dispatchers.IO) { loadOrientedBitmap(path) } }
    }
    val bitmap = bitmapState.value?.getOrNull()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Seleccioná la zona", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Arrastrá dentro del marco para moverlo; arrastrá una esquina para ajustar los límites. Se procesará solo el recorte.", style = MaterialTheme.typography.bodySmall)
        if (bitmap != null) {
            CropImageSelector(bitmap, state.crop, onCropChanged, Modifier.weight(1f).fillMaxWidth())
        } else if (bitmapState.value?.isFailure == true) {
            Text("No se pudo abrir la foto. Repetí la captura.", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Repetir foto") }
            Button(enabled = bitmap != null && !state.processing, onClick = onProcess, modifier = Modifier.weight(1f)) {
                if (state.processing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Reconocer")
            }
        }
    }
}

@Composable
private fun CropImageSelector(
    bitmap: Bitmap,
    crop: NormalizedRegion,
    onCropChanged: (NormalizedRegion) -> Unit,
    modifier: Modifier = Modifier
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val handleColor = MaterialTheme.colorScheme.primary
    val latestCrop by rememberUpdatedState(crop)
    val latestOnChange by rememberUpdatedState(onCropChanged)
    Canvas(
        modifier = modifier
            .background(Color(0xFF202020), RoundedCornerShape(16.dp))
            .pointerInput(bitmap.width, bitmap.height) {
                var mode = CropDragMode.NONE
                var origin = latestCrop
                var anchor = Offset.Zero
                fun imageRect(): Rect {
                    val scale = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                    val w = bitmap.width * scale
                    val h = bitmap.height * scale
                    val left = (size.width - w) / 2f
                    val top = (size.height - h) / 2f
                    return Rect(left, top, left + w, top + h)
                }
                fun normalized(point: Offset, rect: Rect) = NormalizedRegion(
                    ((point.x - rect.left) / rect.width).coerceIn(0f, 1f),
                    ((point.y - rect.top) / rect.height).coerceIn(0f, 1f),
                    ((point.x - rect.left) / rect.width).coerceIn(0f, 1f),
                    ((point.y - rect.top) / rect.height).coerceIn(0f, 1f)
                )
                detectDragGestures(
                    onDragStart = { point ->
                        val bounds = imageRect()
                        anchor = normalized(point, bounds).let { Offset(it.left, it.top) }
                        origin = latestCrop
                        mode = if (!bounds.contains(point)) CropDragMode.NONE else {
                            val x = anchor.x; val y = anchor.y
                            val corners = listOf(
                                Triple(CropDragMode.TOP_LEFT, origin.left, origin.top),
                                Triple(CropDragMode.TOP_RIGHT, origin.right, origin.top),
                                Triple(CropDragMode.BOTTOM_LEFT, origin.left, origin.bottom),
                                Triple(CropDragMode.BOTTOM_RIGHT, origin.right, origin.bottom)
                            )
                            corners.firstOrNull { (_, cx, cy) -> abs(x - cx) < 0.07f && abs(y - cy) < 0.07f }?.first
                                ?: if (x in origin.left..origin.right && y in origin.top..origin.bottom) CropDragMode.MOVE else CropDragMode.CREATE
                        }
                    },
                    onDragEnd = { mode = CropDragMode.NONE },
                    onDragCancel = { mode = CropDragMode.NONE },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (mode == CropDragMode.NONE) return@detectDragGestures
                        val bounds = imageRect()
                        val dx = dragAmount.x / bounds.width
                        val dy = dragAmount.y / bounds.height
                        val currentPoint = normalized(change.position, bounds)
                        val minimum = 0.04f
                        val next = when (mode) {
                            CropDragMode.CREATE -> NormalizedRegion(
                                min(anchor.x, currentPoint.left), min(anchor.y, currentPoint.top),
                                max(anchor.x, currentPoint.left), max(anchor.y, currentPoint.top)
                            )
                            CropDragMode.MOVE -> {
                                val left = (origin.left + dx).coerceIn(0f, 1f - origin.width)
                                val top = (origin.top + dy).coerceIn(0f, 1f - origin.height)
                                NormalizedRegion(left, top, left + origin.width, top + origin.height)
                            }
                            CropDragMode.TOP_LEFT -> NormalizedRegion(
                                currentPoint.left.coerceIn(0f, origin.right - minimum),
                                currentPoint.top.coerceIn(0f, origin.bottom - minimum),
                                origin.right, origin.bottom
                            )
                            CropDragMode.TOP_RIGHT -> NormalizedRegion(
                                origin.left, currentPoint.top.coerceIn(0f, origin.bottom - minimum),
                                currentPoint.left.coerceIn(origin.left + minimum, 1f), origin.bottom
                            )
                            CropDragMode.BOTTOM_LEFT -> NormalizedRegion(
                                currentPoint.left.coerceIn(0f, origin.right - minimum), origin.top,
                                origin.right, currentPoint.top.coerceIn(origin.top + minimum, 1f)
                            )
                            CropDragMode.BOTTOM_RIGHT -> NormalizedRegion(
                                origin.left, origin.top,
                                currentPoint.left.coerceIn(origin.left + minimum, 1f),
                                currentPoint.top.coerceIn(origin.top + minimum, 1f)
                            )
                            CropDragMode.NONE -> origin
                        }
                        if (next.isValid(minimum)) latestOnChange(next)
                    }
                )
            }
    ) {
        val scale = min(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val imageLeft = (size.width - imageWidth) / 2f
        val imageTop = (size.height - imageHeight) / 2f
        val imageRect = Rect(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)
        drawImage(image, dstOffset = IntOffset(imageLeft.toInt(), imageTop.toInt()), dstSize = IntSize(imageWidth.toInt(), imageHeight.toInt()))
        val selected = Rect(
            imageLeft + imageWidth * crop.left,
            imageTop + imageHeight * crop.top,
            imageLeft + imageWidth * crop.right,
            imageTop + imageHeight * crop.bottom
        )
        val mask = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(imageRect)
            addRect(selected)
        }
        drawPath(mask, Color.Black.copy(alpha = 0.48f))
        drawRect(Color.White, selected.topLeft, selected.size, style = Stroke(3.dp.toPx()))
        val radius = 7.dp.toPx()
        listOf(selected.topLeft, Offset(selected.right, selected.top), Offset(selected.left, selected.bottom), selected.bottomRight).forEach { handle ->
            drawCircle(Color.White, radius, handle)
            drawCircle(handleColor, radius, handle, style = Stroke(2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewRecordsScreen(
    state: ScanUiState,
    existingHabitaciones: List<Habitacion>,
    sameJornada: Boolean,
    onUpdate: (String, (RoomDraft) -> RoomDraft) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var showPhoto by rememberSaveable { mutableStateOf(false) }
    val selected = state.drafts.filter(RoomDraft::included)
    val valid = selected.filter(RoomDraft::isValid)
    val total = runCatching { valid.fold(0L) { sum, draft -> Math.addExact(sum, draft.amount!!) } }.getOrNull()
    val existingNumbers = remember(existingHabitaciones) { existingHabitaciones.map { it.numero.trim() }.toSet() }
    val duplicateCounts = remember(state.drafts) { state.drafts.filter { it.included }.groupingBy { it.roomNumber.trim() }.eachCount() }

    Column(Modifier.fillMaxSize()) {
        Text("Revisar registros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("El reconocimiento es una propuesta. Corregí los campos antes de confirmar.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showPhoto = true }) { Text("Ver foto") }
            TextButton(onClick = onRetake) { Text("Repetir foto") }
            TextButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Text("Agregar fila") }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (state.drafts.isEmpty()) {
            Text("No se detectaron líneas de texto. Podés agregar cada ingreso manualmente o repetir la foto.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(state.drafts, key = RoomDraft::id) { draft ->
                val duplicate = draft.included && draft.roomNumber.isNotBlank() &&
                    (draft.roomNumber.trim() in existingNumbers || (duplicateCounts[draft.roomNumber.trim()] ?: 0) > 1)
                RoomDraftCard(draft, duplicate, onUpdate, onRemove)
            }
        }
        Surface(tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Incluidos: ${selected.size} · Importe válido: ${valid.size}", style = MaterialTheme.typography.bodyMedium)
                Text("Total a incorporar: ${total?.let(::formatAmount) ?: "Fuera de rango"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (duplicateCounts.values.any { it > 1 } || selected.any { it.roomNumber.trim() in existingNumbers }) {
                    Text("Hay habitaciones repetidas. Puede ser válido; revisá si corresponde.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                    Button(
                        enabled = sameJornada && !state.saving && selected.isNotEmpty() && selected.all(RoomDraft::isValid) && total != null,
                        onClick = { showConfirm = true }, modifier = Modifier.weight(1f)
                    ) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("Confirmar e incorporar")
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Incorporar registros") },
            text = { Text("Se guardarán ${selected.size} ingresos por ${total?.let(::formatAmount)} en la jornada ${state.jornadaFecha}.") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onConfirm() }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Revisar") } }
        )
    }
    if (showPhoto) OriginalPhotoDialog(state.photoPath) { showPhoto = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomDraftCard(
    draft: RoomDraft,
    duplicate: Boolean,
    onUpdate: (String, (RoomDraft) -> RoomDraft) -> Unit,
    onRemove: (String) -> Unit
) {
    var paymentMenu by remember(draft.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.included, onCheckedChange = { value -> onUpdate(draft.id) { it.copy(included = value) } })
                Column(Modifier.weight(1f)) {
                    Text(if (draft.manual) "Registro manual" else "Propuesta reconocida", style = MaterialTheme.typography.labelLarge)
                    if (draft.sourceText.isNotBlank()) Text(draft.sourceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconButton(onClick = { onRemove(draft.id) }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Quitar fila") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.roomNumber,
                    onValueChange = { value -> onUpdate(draft.id) { it.copy(roomNumber = value.filter(Char::isLetterOrDigit), issues = emptyList()) } },
                    label = { Text("Habitación") }, singleLine = true, modifier = Modifier.weight(1f),
                    isError = draft.included && draft.roomNumber.isBlank()
                )
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { value -> onUpdate(draft.id) { it.copy(amountText = value.filter(Char::isDigit), issues = emptyList()) } },
                    label = { Text("Importe ($)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), isError = draft.included && draft.amount == null
                )
            }
            OutlinedTextField(
                value = draft.person,
                onValueChange = { value -> onUpdate(draft.id) { it.copy(person = value, issues = emptyList()) } },
                label = { Text("Persona · opcional") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenuBox(expanded = paymentMenu, onExpandedChange = { paymentMenu = it }) {
                OutlinedTextField(
                    value = payLabel(draft.payment), onValueChange = {}, readOnly = true,
                    label = { Text("Medio de pago") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(paymentMenu) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = paymentMenu, onDismissRequest = { paymentMenu = false }) {
                    listOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR").forEach { value ->
                        DropdownMenuItem(text = { Text(payLabel(value)) }, onClick = {
                            onUpdate(draft.id) { it.copy(payment = value) }
                            paymentMenu = false
                        })
                    }
                }
            }
            if (draft.issues.isNotEmpty() && draft.included) {
                Text(draft.issues.joinToString(" "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (duplicate) {
                Text("Esta habitación ya aparece en la jornada o en la captura. Puede ser válido; revisalo.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun OriginalPhotoDialog(path: String?, onDismiss: () -> Unit) {
    val bitmapState = produceState<Result<Bitmap>?>(initialValue = null, key1 = path) {
        value = if (path == null) null else runCatching { withContext(Dispatchers.IO) { loadOrientedBitmap(path) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fotografía original") },
        text = {
            val bitmap = bitmapState.value?.getOrNull()
            if (bitmap == null) Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                Text(if (bitmapState.value?.isFailure == true) "No se pudo abrir la foto." else "Cargando foto…")
            }
            else androidx.compose.foundation.Image(
                bitmap.asImageBitmap(), contentDescription = "Fotografía capturada",
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
private fun ScanCompleteScreen(count: Int, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Registros incorporados", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Se guardaron $count ingresos en la jornada.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone) { Text("Volver a Habitaciones") }
    }
}

private fun payLabel(value: String): String = when (value) {
    "EFECTIVO" -> "Efectivo"
    "TARJETA" -> "Tarjeta"
    else -> "Sin clasificar"
}

private fun formatAmount(value: Long): String = "${java.text.NumberFormat.getIntegerInstance(java.util.Locale("es", "UY")).format(value)} $"
