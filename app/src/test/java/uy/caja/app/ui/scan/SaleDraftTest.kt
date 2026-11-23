package uy.caja.app.ui.scan

import org.junit.Assert.*
import org.junit.Test

class SaleDraftTest {
    @Test fun editableQuantityAndPriceRecalculateSubtotalWithoutChangingCatalogValues() {
        val catalogPrice = 80L
        val draft = SaleDraft(recognizedText = "Agua 3", productQuery = "Agua", productId = 1, productName = "Agua", quantityText = "3", priceText = catalogPrice.toString())
        assertTrue(draft.valid)
        assertEquals(240L, draft.subtotal)

        val corrected = draft.copy(quantityText = "4", priceText = "90")
        assertEquals(360L, corrected.subtotal)
        assertEquals(80L, catalogPrice)
        assertTrue(corrected.valid)
    }

    @Test fun ambiguousItemsAndInvalidQuantitiesCannotBeConfirmed() {
        val ambiguous = SaleDraft(recognizedText = "Agua", productId = 1, productName = "Agua", quantityText = "3", priceText = "80", ambiguous = true)
        assertFalse(ambiguous.valid)
        assertFalse(ambiguous.copy(ambiguous = false, quantityText = "0").valid)
        assertNull(ambiguous.copy(ambiguous = false, quantityText = "2147483648").quantity)
    }
}
