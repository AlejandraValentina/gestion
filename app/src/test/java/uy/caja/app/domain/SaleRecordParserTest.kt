package uy.caja.app.domain

import org.junit.Assert.*
import org.junit.Test
import uy.caja.app.domain.scan.CatalogMatcher
import uy.caja.app.domain.scan.NormalizedRegion
import uy.caja.app.domain.scan.ProductCandidate
import uy.caja.app.domain.scan.RecognizedLine
import uy.caja.app.domain.scan.SaleRecordParser

class SaleRecordParserTest {
    @Test fun readsQuantityBeforeAndAfterProductAndDoesNotGuessMissingOrConflictingQuantities() {
        val rows = SaleRecordParser.parse(listOf("Agua 3", "2 refresco", "Galletitas x 1", "Cerveza", "Agua 2 4").mapIndexed { i, text ->
            line(text, i * 0.15f, i * 0.15f + 0.08f)
        })
        assertEquals(listOf("Agua", "refresco", "Galletitas", "Cerveza", "Agua"), rows.map { it.productText })
        assertEquals(listOf(3, 2, 1, null, null), rows.map { it.quantity })
        assertTrue(rows[3].issues.isNotEmpty())
        assertTrue(rows[4].issues.isNotEmpty())
    }

    @Test fun joinsProductAndQuantityRecognizedAsSeparateBlocksAndKeepsRepeatedItemsSeparate() {
        val rows = SaleRecordParser.parse(listOf(
            RecognizedLine("Agua", NormalizedRegion(0.1f, 0.1f, 0.35f, 0.16f)),
            RecognizedLine("3", NormalizedRegion(0.8f, 0.105f, 0.9f, 0.16f)),
            RecognizedLine("Agua", NormalizedRegion(0.1f, 0.3f, 0.35f, 0.36f)),
            RecognizedLine("2", NormalizedRegion(0.8f, 0.305f, 0.9f, 0.36f))
        ))
        assertEquals(2, rows.size)
        assertEquals(listOf(3, 2), rows.map { it.quantity })
        assertEquals(listOf("Agua", "Agua"), rows.map { it.productText })
    }

    @Test fun catalogMatcherIgnoresCaseAndAccentsAndRequiresChoiceForAmbiguousNames() {
        val catalog = listOf(
            ProductCandidate(1, "Agua", 80, 0.0),
            ProductCandidate(2, "Agua con gas", 100, 0.0),
            ProductCandidate(3, "Refresco", 120, 0.0)
        )
        assertEquals(1L, CatalogMatcher.match("ÁGUA", catalog).selected?.id)
        assertEquals(3L, CatalogMatcher.match("refrescc", catalog).selected?.id)
        val ambiguous = CatalogMatcher.match("agua", listOf(
            ProductCandidate(4, "Agua fría", 90, 0.0),
            ProductCandidate(5, "Agua con gas", 100, 0.0)
        ))
        assertNull(ambiguous.selected)
        assertTrue(ambiguous.ambiguous)
    }

    @Test fun flagsLowConfidenceRowsForVisualReview() {
        val result = SaleRecordParser.parse(listOf(RecognizedLine("3 Agua", NormalizedRegion(0f, 0f, 1f, 0.1f), 0.2f))).single()
        assertEquals(3, result.quantity)
        assertTrue(result.issues.any { it.contains("confianza OCR") })
        assertEquals(0.2f, result.confidence!!, 0.0001f)
    }

    private fun line(text: String, top: Float, bottom: Float) =
        RecognizedLine(text, NormalizedRegion(0f, top, 1f, bottom))
}
