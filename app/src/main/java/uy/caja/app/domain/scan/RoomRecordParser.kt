package uy.caja.app.domain.scan

import java.util.UUID

data class NormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun isValid(minimumSize: Float = 0.02f): Boolean =
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            width >= minimumSize && height >= minimumSize
}

data class RecognizedLine(
    val text: String,
    val bounds: NormalizedRegion,
    val confidence: Float? = null
)

data class RoomProposal(
    val id: String = UUID.randomUUID().toString(),
    val roomNumber: String?,
    val amount: Long?,
    val payment: String,
    val sourceText: String,
    val sourceRegion: NormalizedRegion,
    val issues: List<String>
)

object RoomRecordParser {
    private val currencyAmount = Regex("(?i)(?:\\$\\s*|\\bUYU\\s*)([0-9][0-9., ]*)")
    private val numberToken = Regex("[0-9][0-9.,]*")
    private val cardWord = Regex("\\btarjeta\\b", RegexOption.IGNORE_CASE)
    private val cashWord = Regex("\\befectivo\\b", RegexOption.IGNORE_CASE)

    fun parse(lines: List<RecognizedLine>, crop: NormalizedRegion): List<RoomProposal> =
        lines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null

            val numbers = numberToken.findAll(text).toList()
            val explicitAmount = currencyAmount.find(text)
            val amountToken = explicitAmount?.groups?.get(1)
            var roomToken: MatchResult? = null
            var amount: Long? = null
            val issues = mutableListOf<String>()

            if (amountToken != null) {
                amount = amountToken.value.toLongDigits()
                roomToken = numbers.firstOrNull { it.range.last < amountToken.range.first }
                if (amount == null) issues += "Revisá el importe reconocido."
            } else if (numbers.size >= 2) {
                roomToken = numbers.first()
                amount = numbers.last().value.toLongDigits()
                if (numbers.size > 2) issues += "Se detectaron varios números; revisá su asociación."
            } else if (numbers.size == 1) {
                val token = numbers.single()
                val labeledRoom = Regex("(?i)\\b(?:hab(?:itaci[oó]n)?|h)\\.?\\s*${Regex.escape(token.value)}\\b").containsMatchIn(text)
                if (labeledRoom || token.value.count(Char::isDigit) in 2..4) {
                    roomToken = token
                    issues += "Falta el importe o no se pudo reconocer."
                } else {
                    amount = token.value.toLongDigits()
                    issues += "Falta el número de habitación."
                }
            } else {
                issues += "No se identificaron habitación ni importe."
            }

            val room = roomToken?.value?.filter(Char::isDigit)
            if (room.isNullOrBlank()) issues += "Revisá el número de habitación."
            if (amount == null) issues += "Revisá el importe."

            val payment = when {
                cardWord.containsMatchIn(text) -> "TARJETA"
                cashWord.containsMatchIn(text) -> "EFECTIVO"
                else -> "SIN_CLASIFICAR"
            }
            if (line.confidence != null && line.confidence < 0.5f) {
                issues += "La confianza del reconocimiento es baja; compará con la foto."
            }
            val sourceRegion = NormalizedRegion(
                left = (crop.left + line.bounds.left * crop.width).coerceIn(0f, 1f),
                top = (crop.top + line.bounds.top * crop.height).coerceIn(0f, 1f),
                right = (crop.left + line.bounds.right * crop.width).coerceIn(0f, 1f),
                bottom = (crop.top + line.bounds.bottom * crop.height).coerceIn(0f, 1f)
            )
            RoomProposal(
                roomNumber = room,
                amount = amount,
                payment = payment,
                sourceText = text,
                sourceRegion = sourceRegion,
                issues = issues.distinct()
            )
        }

    private fun String.toLongDigits(): Long? = filter(Char::isDigit).takeIf(String::isNotEmpty)?.toLongOrNull()
}
