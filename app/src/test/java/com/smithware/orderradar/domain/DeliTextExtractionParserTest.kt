package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliTextExtractionParserTest {
    @Test
    fun parsesInventoryLabelsAndMergesDuplicateCases() {
        val text = """
            Tyson wings item 123456 40 lb pack 7/20/2026 use by 7/29/2026 vendor Tyson
            Tyson wings SKU 123456 40 lbs pack 7/20/2026 use-by 7/29/2026
            Ham off bone item 887700 use by 8/05/2026 12 lb
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseInventoryLabels(text, "photo-a", InventoryLocation.COOLER)
        val merged = DeliTextExtractionParser.mergeDuplicateCases(parsed)

        assertEquals(3, parsed.size)
        assertEquals(2, merged.size)
        val wings = merged.single { it.sku == "123456" }
        assertEquals(2.0, wings.casesOnHand, 0.001)
        assertEquals(DeliCategory.WINGS_TENDERS, wings.category)
        assertEquals(LocalDate.of(2026, 7, 29), wings.useByDate)
    }

    @Test
    fun parsesPromoAndOrderScreenTextIntoEngineInputs() {
        val promoText = "SKU 445566 Grab n go pudding BOGO retail 4.99 sale 2.49 50%"
        val orderText = """
            SKU 445566 Grab n go pudding pack 6ct suggested 2
            item 778899 Mac salad pack 5 lb qty 4
        """.trimIndent()

        val promos = DeliTextExtractionParser.parsePromoItems(
            promoText,
            defaultStart = LocalDate.of(2026, 7, 27),
            defaultEnd = LocalDate.of(2026, 8, 2)
        )
        val lines = DeliTextExtractionParser.parseOrderScreenLines(orderText)

        assertEquals(PromoDealType.BOGO, promos.single().dealType)
        assertEquals(50.0, promos.single().discountPct ?: 0.0, 0.001)
        assertEquals(listOf("445566", "778899"), lines.map { it.sku })
        assertEquals(2.0, lines.first().suggestedCases, 0.001)
        assertTrue(lines.first().packSize?.contains("6ct") == true)
    }
}

