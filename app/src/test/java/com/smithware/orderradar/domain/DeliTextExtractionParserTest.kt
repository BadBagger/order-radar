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
    fun parsesRealBackstockCaseLabelsFromPhotos() {
        val text = """
            0250370 Grandma's Kitchen Apple Vinaigrette Slaw 2 / 5 LB Use By 08/22/26
            0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount
            80017 Blount Fine Foods Lobster Bisque 4 / 4 LB Exp 8/02/2026
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseInventoryLabels(text, "case-rack-1", InventoryLocation.COOLER)

        assertEquals(listOf("0250370", "0332094", "80017"), parsed.map { it.sku })
        assertEquals("Grandma's Kitchen Apple Vinaigrette Slaw", parsed[0].name)
        assertEquals(10.0, parsed[0].caseWeightLbs ?: 0.0, 0.001)
        assertEquals(LocalDate.of(2026, 8, 22), parsed[0].useByDate)
        assertEquals(DeliCategory.SALADS, parsed[0].category)
        assertEquals(16.0, parsed[1].caseWeightLbs ?: 0.0, 0.001)
        assertEquals(DeliCategory.SOUPS, parsed[1].category)
        assertEquals("Blount", parsed[2].brandVendor)
    }

    @Test
    fun createsVerifyItemsForLooseSlicerBackstockWithoutVisibleSkus() {
        val text = """
            3 blocks yellow cheddar cheese
            hard salami log
            provolone cheese loaf
            shelf marker 29
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseLooseBackstockItems(text, "slicer-a", InventoryLocation.SLICER_BACKSTOCK)

        assertEquals(3, parsed.size)
        assertEquals("UNKNOWN-SLICER-A-1", parsed[0].sku)
        assertEquals(3.0, parsed[0].casesOnHand, 0.001)
        assertEquals(DeliCategory.CHEESE, parsed[0].category)
        assertEquals(DeliCategory.DELI_MEAT, parsed[1].category)
        assertTrue(parsed.all { !it.verified && it.confidence < 0.80 })
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

    @Test
    fun parsesPublixOrderReviewRowsFromScreenPhotos() {
        val text = """
            0080704 - PBX DELI SWISS (2) 18.18 LB 1 0 WK
            0075034 - FRYER CHICKEN TENDERLOIN 20 LB 4 6 6 WK
            0100567 - CHICKEN WINGS HOT & SPICY 20 LB 3 6 6
            0332094 - SOUP CHICKEN NOODLE 4 / 4 LB
            0927214 - ASIAGO SUN TOM SALAD KIT 4 / 12.5 OZ
            0344054 - KRINOS LONG STEM ARTICHOK 6 / 5.3 LB
        """.trimIndent()

        val lines = DeliTextExtractionParser.parseOrderScreenLines(text)

        assertEquals(
            listOf("0080704", "0075034", "0100567", "0332094", "0927214", "0344054"),
            lines.map { it.sku }
        )
        assertEquals("PBX DELI SWISS (2)", lines[0].name)
        assertEquals("18.18 LB", lines[0].packSize)
        assertEquals(1.0, lines[0].suggestedCases, 0.001)
        assertEquals("FRYER CHICKEN TENDERLOIN", lines[1].name)
        assertEquals(4.0, lines[1].suggestedCases, 0.001)
        assertEquals(3.0, lines[2].suggestedCases, 0.001)
        assertEquals("4 / 4 LB", lines[3].packSize)
        assertEquals(0.0, lines[3].suggestedCases, 0.001)
        assertEquals("4 / 12.5 OZ", lines[4].packSize)
        assertEquals("6 / 5.3 LB", lines[5].packSize)
    }

    @Test
    fun capturesHandwrittenExtraNotesFromOrderPhotoText() {
        val notes = DeliTextExtractionParser.parseStickyNotes(
            """
            please add
            8 extra
            CHICKEN WINGS PLAIN
            """.trimIndent()
        )

        assertEquals(listOf("please add", "8 extra"), notes)
    }
}

