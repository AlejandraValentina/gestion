package uy.caja.app.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.caja.app.data.CajaDao
import uy.caja.app.data.Habitacion
import uy.caja.app.data.recognition.HandwritingRecognizer
import uy.caja.app.data.recognition.MlKitHandwritingRecognizer
import uy.caja.app.domain.scan.NormalizedRegion
import uy.caja.app.domain.scan.RoomProposal
import uy.caja.app.domain.scan.RoomRecordParser
import java.io.File
import java.util.UUID

internal enum class ScanStep { CAMERA, PHOTO, REVIEW, COMPLETE }

internal data class RoomDraft(
    val id: String = UUID.randomUUID().toString(),
    val roomNumber: String = "",
    val person: String = "",
    val amountText: String = "",
    val payment: String = "SIN_CLASIFICAR",
    val sourceText: String = "",
    val sourceRegion: NormalizedRegion? = null,
    val issues: List<String> = emptyList(),
    val included: Boolean = true,
    val manual: Boolean = false
) {
    val amount: Long? get() = amountText.filter(Char::isDigit).toLongOrNull()
    val isValid: Boolean get() = roomNumber.isNotBlank() && amount != null && payment in PAYMENTS

    companion object { val PAYMENTS = setOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR") }
}

internal data class ScanUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val jornadaId: Long = 0,
    val jornadaFecha: String = "",
    val batchId: String = UUID.randomUUID().toString(),
    val photoPath: String? = null,
    val crop: NormalizedRegion = NormalizedRegion(0.03f, 0.03f, 0.97f, 0.97f),
    val drafts: List<RoomDraft> = emptyList(),
    val processing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val savedCount: Int = 0
)

internal class HabitacionesScanViewModel(
    application: Application,
    private val dao: CajaDao,
    private val recognizer: HandwritingRecognizer = MlKitHandwritingRecognizer()
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()
    private var processingJob: Job? = null

    fun start(jornadaId: Long, fecha: String) {
        processingJob?.cancel()
        deletePhoto(_state.value.photoPath)
        _state.value = ScanUiState(jornadaId = jornadaId, jornadaFecha = fecha)
    }

    fun prepareCaptureFile(): File {
        val directory = File(getApplication<Application>().filesDir, "room-scans")
        check(directory.exists() || directory.mkdirs()) { "No se pudo preparar el almacenamiento de la fotografía." }
        return File(directory, "${_state.value.batchId}.jpg")
    }

    fun captureCompleted(file: File, success: Boolean, message: String? = null) {
        if (!success || !file.exists() || file.length() == 0L) {
            file.delete()
            _state.value = _state.value.copy(step = ScanStep.CAMERA, error = message ?: "No se pudo guardar la foto. Probá otra vez.")
            return
        }
        _state.value = _state.value.copy(step = ScanStep.PHOTO, photoPath = file.absolutePath, error = null)
    }

    fun retake() {
        processingJob?.cancel()
        deletePhoto(_state.value.photoPath)
        _state.value = _state.value.copy(step = ScanStep.CAMERA, photoPath = null, crop = NormalizedRegion(0.03f, 0.03f, 0.97f, 0.97f), error = null, drafts = emptyList())
    }

    fun process(crop: NormalizedRegion) {
        val current = _state.value
        val path = current.photoPath ?: return
        if (current.processing || !crop.isValid()) {
            _state.value = current.copy(error = "Seleccioná una región válida para procesar.")
            return
        }
        _state.value = current.copy(crop = crop, processing = true, error = null)
        processingJob = viewModelScope.launch {
            try {
                val proposals = withContext(Dispatchers.IO) {
                    val source = loadOrientedBitmap(path)
                    val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
                    val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
                    val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
                    val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                    val selected = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                    try {
                        RoomRecordParser.parse(recognizer.recognize(selected), crop)
                    } finally {
                        if (selected !== source) selected.recycle()
                        source.recycle()
                    }
                }
                _state.value = _state.value.copy(
                    step = ScanStep.REVIEW,
                    drafts = proposals.map { it.toDraft() },
                    processing = false,
                    error = if (proposals.isEmpty()) "No se encontró texto en esa zona. Podés agregar los registros manualmente o volver a ajustar la región." else null
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    processing = false,
                    error = "No se pudo reconocer la zona. ${error.localizedMessage ?: "Revisá la foto e intentá otra vez."}"
                )
            }
        }
    }

    fun updateCrop(crop: NormalizedRegion) {
        if (crop.isValid()) _state.value = _state.value.copy(crop = crop)
    }

    fun addManualDraft() {
        _state.value = _state.value.copy(
            drafts = _state.value.drafts + RoomDraft(manual = true, sourceText = "Agregado manualmente"),
            error = null
        )
    }

    fun updateDraft(id: String, update: (RoomDraft) -> RoomDraft) {
        _state.value = _state.value.copy(drafts = _state.value.drafts.map { if (it.id == id) update(it) else it }, error = null)
    }

    fun removeDraft(id: String) {
        _state.value = _state.value.copy(drafts = _state.value.drafts.filterNot { it.id == id }, error = null)
    }

    fun confirm(currentJornadaId: Long) {
        val current = _state.value
        if (current.saving || current.step == ScanStep.COMPLETE) return
        if (current.jornadaId != currentJornadaId) {
            _state.value = current.copy(error = "Cambió la jornada seleccionada. Cancelá el escaneo y volvé a iniciarlo en la jornada correcta.")
            return
        }
        val selected = current.drafts.filter(RoomDraft::included)
        if (selected.isEmpty() || selected.any { !it.isValid }) {
            _state.value = current.copy(error = "Corregí o excluí los registros incompletos antes de guardar.")
            return
        }
        val records = selected.map { draft ->
            Habitacion(
                jornadaId = current.jornadaId,
                numero = draft.roomNumber.trim(),
                persona = draft.person.trim(),
                importe = draft.amount ?: 0L,
                pago = draft.payment,
                scanBatchId = current.batchId
            )
        }
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val inserted = dao.importarHabitaciones(current.batchId, records)
                _state.value = if (inserted) {
                    _state.value.copy(step = ScanStep.COMPLETE, saving = false, savedCount = records.size, error = null)
                } else {
                    _state.value.copy(saving = false, error = "Esta confirmación ya se guardó. No se agregaron registros duplicados.")
                }
            } catch (error: Exception) {
                _state.value = _state.value.copy(saving = false, error = "No se pudieron guardar los registros. ${error.localizedMessage ?: "Intentá nuevamente."}")
            }
        }
    }

    fun cancel() {
        processingJob?.cancel()
        deletePhoto(_state.value.photoPath)
        _state.value = ScanUiState()
    }

    fun closeSession() = cancel()

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }

    private fun deletePhoto(path: String?) {
        if (path != null) File(path).delete()
    }
}

private fun RoomProposal.toDraft() = RoomDraft(
    roomNumber = roomNumber.orEmpty(),
    person = person.orEmpty(),
    amountText = amount?.toString().orEmpty(),
    payment = payment,
    sourceText = sourceText,
    sourceRegion = sourceRegion,
    issues = issues
)

internal fun loadOrientedBitmap(path: String): Bitmap {
    val decoded = BitmapFactory.decodeFile(path) ?: error("La fotografía no se pudo abrir.")
    val orientation = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return decoded
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
        if (it !== decoded) decoded.recycle()
    }
}
