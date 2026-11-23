package uy.caja.app.ui.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.caja.app.data.Articulo
import uy.caja.app.data.CajaDao
import uy.caja.app.data.VentaDb
import uy.caja.app.data.recognition.HandwritingRecognizer
import uy.caja.app.data.recognition.MlKitHandwritingRecognizer
import uy.caja.app.domain.scan.CatalogMatcher
import uy.caja.app.domain.scan.NormalizedRegion
import uy.caja.app.domain.scan.ProductCandidate
import uy.caja.app.domain.scan.SaleRecordParser
import java.io.File
import java.util.UUID

internal data class SaleDraft(
    val id: String = UUID.randomUUID().toString(),
    val recognizedText: String,
    val productQuery: String = recognizedText,
    val productId: Long? = null,
    val productName: String = "",
    val quantityText: String = "",
    val priceText: String = "",
    val payment: String = "SIN_CLASIFICAR",
    val candidates: List<ProductCandidate> = emptyList(),
    val ambiguous: Boolean = false,
    val included: Boolean = true,
    val issues: List<String> = emptyList()
) {
    val quantity: Int? get() = quantityText.toIntOrNull()?.takeIf { it > 0 }
    val price: Long? get() = priceText.toLongOrNull()?.takeIf { it >= 0 }
    val subtotal: Long? get() = quantity?.let { qty -> price?.let { runCatching { Math.multiplyExact(qty.toLong(), it) }.getOrNull() } }
    val valid: Boolean get() = productId != null && productName.isNotBlank() && quantity != null && price != null && payment in PAYMENTS && !ambiguous

    companion object { val PAYMENTS = setOf("EFECTIVO", "TARJETA", "SIN_CLASIFICAR") }
}

internal data class SalesScanState(
    val step: ScanStep = ScanStep.CAMERA,
    val jornadaId: Long = 0,
    val jornadaFecha: String = "",
    val batchId: String = UUID.randomUUID().toString(),
    val photoPath: String? = null,
    val crop: NormalizedRegion = NormalizedRegion(0.03f, 0.03f, 0.97f, 0.97f),
    val rows: List<SaleDraft> = emptyList(),
    val processing: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val savedCount: Int = 0
)

internal class VentasScanViewModel(
    application: Application,
    private val dao: CajaDao,
    private val recognizer: HandwritingRecognizer = MlKitHandwritingRecognizer()
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SalesScanState())
    val state = _state.asStateFlow()
    private var processingJob: Job? = null

    fun start(jornadaId: Long, fecha: String) {
        processingJob?.cancel()
        deletePhoto(_state.value.photoPath)
        _state.value = SalesScanState(jornadaId = jornadaId, jornadaFecha = fecha)
    }

    fun prepareCaptureFile(): File {
        val directory = File(getApplication<Application>().filesDir, "sales-scans")
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
        _state.value = _state.value.copy(step = ScanStep.CAMERA, photoPath = null, crop = NormalizedRegion(0.03f, 0.03f, 0.97f, 0.97f), rows = emptyList(), error = null)
    }

    fun updateCrop(crop: NormalizedRegion) { if (crop.isValid()) _state.value = _state.value.copy(crop = crop) }

    fun process(crop: NormalizedRegion, catalog: List<Articulo>) {
        val current = _state.value
        val path = current.photoPath ?: return
        if (current.processing || !crop.isValid()) {
            _state.value = current.copy(error = "Seleccioná una región válida para procesar.")
            return
        }
        _state.value = current.copy(crop = crop, processing = true, error = null)
        val products = catalog.map { ProductCandidate(it.id, it.nombre, it.precio, 0.0) }
        processingJob = viewModelScope.launch {
            try {
                val proposals = withContext(Dispatchers.IO) {
                    val source = loadOrientedBitmap(path)
                    val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
                    val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
                    val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
                    val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)
                    val selected = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
                    try { SaleRecordParser.parse(recognizer.recognize(selected)) }
                    finally { if (selected !== source) selected.recycle(); source.recycle() }
                }
                val rows = proposals.map { proposal ->
                    val match = CatalogMatcher.match(proposal.productText, products)
                    val lowConfidence = proposal.confidence != null && proposal.confidence < 0.5f
                    val chosen = match.selected.takeUnless { lowConfidence }
                    SaleDraft(
                        recognizedText = proposal.sourceText,
                        productQuery = proposal.productText,
                        productId = chosen?.id,
                        productName = chosen?.name.orEmpty(),
                        quantityText = proposal.quantity?.toString().orEmpty(),
                        priceText = chosen?.price?.toString().orEmpty(),
                        candidates = match.candidates,
                        ambiguous = match.ambiguous || lowConfidence,
                        issues = proposal.issues
                    )
                }
                _state.value = _state.value.copy(step = ScanStep.REVIEW, rows = rows, processing = false,
                    error = if (rows.isEmpty()) "No se detectaron registros. Agregalos manualmente o ajustá el recorte." else null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                _state.value = _state.value.copy(processing = false, error = "No se pudo reconocer la zona. ${error.localizedMessage ?: "Revisá la foto e intentá otra vez."}")
            }
        }
    }

    fun addManualDraft() { _state.value = _state.value.copy(rows = _state.value.rows + SaleDraft(recognizedText = "Agregado manualmente", productQuery = "", quantityText = "1"), error = null) }
    fun updateDraft(id: String, transform: (SaleDraft) -> SaleDraft) {
        _state.value = _state.value.copy(rows = _state.value.rows.map { if (it.id == id) transform(it) else it }, error = null)
    }
    fun selectProduct(id: String, article: Articulo?) = updateDraft(id) {
        it.copy(productId = article?.id, productName = article?.nombre.orEmpty(), priceText = article?.precio?.toString().orEmpty(), ambiguous = false, candidates = emptyList())
    }
    fun removeDraft(id: String) { _state.value = _state.value.copy(rows = _state.value.rows.filterNot { it.id == id }, error = null) }

    fun createProductForDraft(id: String, name: String, price: Long) {
        if (name.isBlank() || price < 0) return
        viewModelScope.launch {
            try {
                val productId = dao.insertarArticulo(Articulo(nombre = name.trim(), precio = price))
                updateDraft(id) { it.copy(productId = productId, productName = name.trim(), priceText = price.toString(), ambiguous = false, candidates = emptyList()) }
            } catch (error: Exception) {
                _state.value = _state.value.copy(error = "No se pudo crear el artículo. ${error.localizedMessage ?: "Intentá nuevamente."}")
            }
        }
    }

    fun applyPaymentToAll(payment: String) {
        if (payment in SaleDraft.PAYMENTS) _state.value = _state.value.copy(rows = _state.value.rows.map { if (it.included) it.copy(payment = payment) else it })
    }

    fun confirm(currentJornadaId: Long) {
        val current = _state.value
        if (current.saving || current.step == ScanStep.COMPLETE) return
        if (current.jornadaId != currentJornadaId) { _state.value = current.copy(error = "Cambió la jornada seleccionada. Cancelá y volvé a iniciar el escaneo."); return }
        val selected = current.rows.filter(SaleDraft::included)
        if (selected.isEmpty() || selected.any { !it.valid }) { _state.value = current.copy(error = "Resuelve los productos ambiguos y corregí o excluí las filas incompletas antes de guardar."); return }
        val sales = selected.map { row -> VentaDb(jornadaId = current.jornadaId, articuloId = row.productId!!,
            articuloNombre = row.productName, cantidad = row.quantity!!, precioAplicado = row.price!!, pago = row.payment) }
        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                val inserted = dao.importarVentasEscaneadas(current.batchId, sales)
                _state.value = if (inserted) _state.value.copy(step = ScanStep.COMPLETE, saving = false, savedCount = sales.size)
                else _state.value.copy(saving = false, error = "Este lote ya se guardó. No se agregaron ventas duplicadas.")
            } catch (error: Exception) { _state.value = _state.value.copy(saving = false, error = "No se pudieron guardar las ventas. ${error.localizedMessage ?: "Intentá nuevamente."}") }
        }
    }

    fun cancel() { processingJob?.cancel(); deletePhoto(_state.value.photoPath); _state.value = SalesScanState() }
    fun closeSession() = cancel()
    override fun onCleared() { recognizer.close(); super.onCleared() }
    private fun deletePhoto(path: String?) { if (path != null) File(path).delete() }
}
