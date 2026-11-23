package uy.caja.app.domain.scan

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class SaleProposal(
    val id: String = UUID.randomUUID().toString(),
    val sourceText: String,
    val productText: String,
    val quantity: Int?,
    val confidence: Float? = null,
    val issues: List<String> = emptyList()
)

/** Reads common handwritten row forms without guessing missing or conflicting quantities. */
object SaleRecordParser {
    private val number = Regex("\\d+")
    private val leadingQuantity = Regex("^\\s*(\\d+)\\s*(?:[xX×*]\\s*)?(.+?)\\s*$")
    private val trailingQuantity = Regex("^\\s*(.+?)\\s*(?:[xX×*]\\s*)?(\\d+)\\s*$")

    fun parse(lines: List<RecognizedLine>): List<SaleProposal> = mergeSameRow(lines).mapNotNull { line ->
        val text = line.text.trim().replace(Regex("\\s+"), " ")
        if (text.isBlank()) return@mapNotNull null
        val numbers = number.findAll(text).toList()
        when {
            numbers.isEmpty() -> withConfidence(SaleProposal(sourceText = text, productText = text, quantity = null, issues = listOf("No se reconoció la cantidad.")), line.confidence)
            numbers.size > 1 -> {
                val name = text.replace(number, " ").replace(Regex("\\s+"), " ").trim(' ', '-', '×', 'x', 'X')
                withConfidence(SaleProposal(sourceText = text, productText = name.ifBlank { text }, quantity = null, issues = listOf("Hay varios números; confirmá el producto y la cantidad.")), line.confidence)
            }
            else -> {
                val count = numbers.single().value.toIntOrNull()?.takeIf { it > 0 }
                val lead = leadingQuantity.matchEntire(text)
                val trail = trailingQuantity.matchEntire(text)
                val name = when {
                    lead != null -> lead.groupValues[2]
                    trail != null -> trail.groupValues[1]
                    else -> text.replace(number, " ").trim()
                }.trim(' ', '-', ':', '×', 'x', 'X')
                val issues = buildList {
                    if (count == null) add("La cantidad debe ser un entero mayor que cero.")
                    if (name.isBlank()) add("No se reconoció el nombre del producto.")
                }
                withConfidence(SaleProposal(sourceText = text, productText = name, quantity = count, issues = issues), line.confidence)
            }
        }
    }

    private fun withConfidence(proposal: SaleProposal, confidence: Float?): SaleProposal = proposal.copy(
        confidence = confidence,
        issues = proposal.issues + if (confidence != null && confidence < 0.5f) listOf("La confianza OCR es baja; compará el texto y la cantidad con la foto.") else emptyList()
    )

    private fun mergeSameRow(lines: List<RecognizedLine>): List<RecognizedLine> {
        val rows = mutableListOf<MutableList<RecognizedLine>>()
        lines.filter { it.text.isNotBlank() }.sortedBy { (it.bounds.top + it.bounds.bottom) / 2f }.forEach { line ->
            val center = (line.bounds.top + line.bounds.bottom) / 2f
            val height = line.bounds.height
            val row = rows.lastOrNull()
            val rowCenter = row?.map { (it.bounds.top + it.bounds.bottom) / 2f }?.average()?.toFloat()
            val rowHeight = row?.maxOfOrNull { it.bounds.height } ?: 0f
            if (row != null && rowCenter != null && kotlin.math.abs(center - rowCenter) <= maxOf(0.018f, minOf(height, rowHeight) * 0.8f)) row += line
            else rows += mutableListOf(line)
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.bounds.left }
            RecognizedLine(
                text = ordered.joinToString(" ") { it.text.trim() },
                bounds = NormalizedRegion(ordered.minOf { it.bounds.left }, ordered.minOf { it.bounds.top }, ordered.maxOf { it.bounds.right }, ordered.maxOf { it.bounds.bottom }),
                confidence = ordered.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
            )
        }
    }
}

data class ProductCandidate(val id: Long, val name: String, val price: Long, val score: Double)
data class ProductMatch(val selected: ProductCandidate?, val candidates: List<ProductCandidate>, val ambiguous: Boolean)

/** Normalized exact matches win; fuzzy matches are automatic only when strong and clearly unique. */
object CatalogMatcher {
    fun match(recognized: String, products: List<ProductCandidate>): ProductMatch {
        val query = normalize(recognized)
        if (query.isBlank() || products.isEmpty()) return ProductMatch(null, emptyList(), false)
        val ranked = products.map { it.copy(score = similarity(query, normalize(it.name))) }
            .sortedWith(compareByDescending<ProductCandidate> { it.score }.thenBy { it.name })
        val first = ranked.first()
        val exact = ranked.filter { it.score >= 0.999 }
        if (exact.size == 1) return ProductMatch(first, exact, false)
        if (exact.size > 1) return ProductMatch(null, exact, true)
        val second = ranked.getOrNull(1)?.score ?: 0.0
        val plausible = ranked.filter { it.score >= maxOf(0.48, first.score - 0.22) }.take(5)
        val clear = first.score >= 0.78 && first.score - second >= 0.12
        return ProductMatch(if (clear) first else null, plausible, !clear && plausible.size > 1)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length >= 4 && b.contains(a)) return (0.82 + 0.18 * a.length / b.length).coerceAtMost(0.96)
        if (b.length >= 4 && a.contains(b)) return (0.82 + 0.18 * b.length / a.length).coerceAtMost(0.96)
        val left = a.split(' ').filter(String::isNotBlank)
        val right = b.split(' ').filter(String::isNotBlank)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val tokenScores = left.map { token -> right.maxOf { other -> editSimilarity(token, other) } }
        return tokenScores.average() * (left.size.toDouble() / maxOf(left.size, right.size))
    }

    private fun editSimilarity(a: String, b: String): Double {
        val prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var diagonal = prev[0]
            prev[0] = i
            for (j in 1..b.length) {
                val old = prev[j]
                prev[j] = minOf(prev[j] + 1, prev[j - 1] + 1, diagonal + if (a[i - 1] == b[j - 1]) 0 else 1)
                diagonal = old
            }
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length, 1)
    }
}
