package uy.caja.app.domain.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomRecordParserTest {
    private val crop = NormalizedRegion(0.1f, 0.2f, 0.9f, 0.8f)

    @Test fun parsesRoomsNamesAmountsAndExplicitPaymentWithoutAssumingCash() {
        val rows = listOf(
            line("101 Juan \$500", 0.1f, 0.1f, 0.7f, 0.2f),
            line("102 María \$800 tarjeta", 0.1f, 0.3f, 0.8f, 0.4f),
            line("103 $1.200", 0.1f, 0.5f, 0.5f, 0.6f),
            line("104 Pedro $650 efectivo", 0.1f, 0.7f, 0.9f, 0.8f)
        )

        val result = RoomRecordParser.parse(rows, crop)

        assertEquals(4, result.size)
        assertEquals("101", result[0].roomNumber)
        assertEquals(500L, result[0].amount)
        assertEquals("SIN_CLASIFICAR", result[0].payment)
        assertEquals("102", result[1].roomNumber)
        assertEquals(800L, result[1].amount)
        assertEquals("TARJETA", result[1].payment)
        assertEquals(1200L, result[2].amount)
        assertEquals("SIN_CLASIFICAR", result[2].payment)
        assertEquals("EFECTIVO", result[3].payment)
    }

    @Test fun retainsIncompleteLinesForReviewAndMapsSourceBoundsToOriginalImage() {
        val result = RoomRecordParser.parse(listOf(line("101 Ana", 0.25f, 0.25f, 0.75f, 0.5f)), crop).single()

        assertEquals("101", result.roomNumber)
        assertEquals(null, result.amount)
        assertTrue(result.issues.isNotEmpty())
        assertEquals(0.3f, result.sourceRegion.left, 0.0001f)
        assertEquals(0.35f, result.sourceRegion.top, 0.0001f)
        assertEquals(0.7f, result.sourceRegion.right, 0.0001f)
        assertEquals(0.5f, result.sourceRegion.bottom, 0.0001f)
    }

    @Test fun doesNotInventRoomOrAmountWhenLineIsAmbiguous() {
        val result = RoomRecordParser.parse(listOf(line("Ana sin número", 0f, 0f, 1f, 1f)), NormalizedRegion(0f, 0f, 1f, 1f)).single()
        assertEquals(null, result.roomNumber)
        assertEquals(null, result.amount)
        assertEquals("SIN_CLASIFICAR", result.payment)
        assertFalse(result.issues.isEmpty())
    }

    private fun line(text: String, left: Float, top: Float, right: Float, bottom: Float) =
        RecognizedLine(text, NormalizedRegion(left, top, right, bottom))
}
