package uy.caja.app.data.recognition

import android.graphics.Bitmap
import uy.caja.app.domain.scan.RecognizedLine

/** Replaceable boundary for OCR engines that read photographed documents. */
interface HandwritingRecognizer : AutoCloseable {
    suspend fun recognize(bitmap: Bitmap): List<RecognizedLine>
}
