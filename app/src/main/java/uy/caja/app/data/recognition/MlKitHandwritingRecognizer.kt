package uy.caja.app.data.recognition

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.caja.app.domain.scan.NormalizedRegion
import uy.caja.app.domain.scan.RecognizedLine

/** ML Kit Latin Text Recognition v2; bundled model works on-device without a request-time download. */
class MlKitHandwritingRecognizer : HandwritingRecognizer {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap): List<RecognizedLine> = withContext(Dispatchers.IO) {
        val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)))
        result.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                RecognizedLine(
                    text = line.text,
                    bounds = NormalizedRegion(
                        left = (box.left.toFloat() / bitmap.width).coerceIn(0f, 1f),
                        top = (box.top.toFloat() / bitmap.height).coerceIn(0f, 1f),
                        right = (box.right.toFloat() / bitmap.width).coerceIn(0f, 1f),
                        bottom = (box.bottom.toFloat() / bitmap.height).coerceIn(0f, 1f)
                    ),
                    confidence = line.confidence
                )
            }
        }
    }

    override fun close() = client.close()
}
