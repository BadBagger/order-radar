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
    fun parsesMessyBlountAndGrandmasCaseLabelOcrVariants() {
        val text = """
            O33Z094  BL0UNT Fine Foods  Soup Chicken Noodle 4/4 LB U5E BY 7/31/26
            item O25037O GRANDMA S KITCHEN Apple Vinaigrette Slaw 2 / 5 LB best by 08/22/26
            code 80O17 Blount lobster bisque 4 / 4 LB EXP. 8/02/2026 mark 2
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseInventoryLabels(text, "messy-case-labels", InventoryLocation.COOLER)

        assertEquals(listOf("0332094", "0250370", "80017"), parsed.map { it.sku })
        assertEquals("Blount Fine Foods Soup Chicken Noodle", parsed[0].name)
        assertEquals("Grandma's Kitchen", parsed[1].brandVendor)
        assertEquals(LocalDate.of(2026, 8, 22), parsed[1].useByDate)
        assertEquals(2.0, parsed[2].casesOnHand, 0.001)
        assertEquals(DeliCategory.SOUPS, parsed[2].category)
    }

    @Test
    fun parsesPublixPbxCheeseAndDeliMeatInventoryLabels() {
        val text = """
            0080704 PBX DELI SWISS (2) 18.18 LB Use By 08/03/26 count 3
            0077712 Publix deli tavern ham 2 / 6 LB Use By 8/04/2026 cases 2
            item O60123 PBX Deluxe Roast Beef 2/7 LB exp 8/05/26
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseInventoryLabels(text, "pbx-slicer-case", InventoryLocation.SLICER_BACKSTOCK)

        assertEquals(listOf("0080704", "0077712", "060123"), parsed.map { it.sku })
        assertEquals("Publix", parsed[0].brandVendor)
        assertEquals(DeliCategory.CHEESE, parsed[0].category)
        assertEquals(3.0, parsed[0].casesOnHand, 0.001)
        assertEquals(DeliCategory.DELI_MEAT, parsed[1].category)
        assertEquals(12.0, parsed[1].caseWeightLbs ?: 0.0, 0.001)
        assertEquals(DeliCategory.DELI_MEAT, parsed[2].category)
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
    fun readsHandwrittenMarkerCountsOnLooseCheeseAndMeat() {
        val text = """
            #4 yellow american cheese
            turkey breast 2
            x3 hard salami logs
            shelf marker 29
            please add 8 extra
        """.trimIndent()

        val parsed = DeliTextExtractionParser.parseLooseBackstockItems(text, "marker-counts", InventoryLocation.SLICER_BACKSTOCK)

        assertEquals(listOf(4.0, 2.0, 3.0), parsed.map { it.casesOnHand })
        assertEquals(listOf(DeliCategory.CHEESE, DeliCategory.DELI_MEAT, DeliCategory.DELI_MEAT), parsed.map { it.category })
        assertEquals(listOf("yellow american cheese", "turkey breast", "hard salami"), parsed.map { it.name })
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

        assertEquals(listOf("please add 8 extra"), notes)
    }

    @Test
    fun combinesShelfNoteFragmentsForExtraCaseRequests() {
        val notes = DeliTextExtractionParser.parseStickyNotes(
            """
            cooler shelf note
            pls add
            8 xtra
            PBX SWISS
            add 2 extra ham
            """.trimIndent()
        )

        assertEquals(listOf("cooler shelf note", "pls add 8 xtra", "add 2 extra ham"), notes)
    }

    @Test
    fun buildsExtractionBatchAcrossInventoryPromoOrderAndNotes() {
        val batch = DeliExtractionBatchBuilder.build(
            inputs = listOf(
                DeliTextExtractionInput(
                    id = "cooler-1",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.COOLER,
                    text = """
                        0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount
                        0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount
                    """.trimIndent()
                ),
                DeliTextExtractionInput(
                    id = "slicer-1",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.SLICER_BACKSTOCK,
                    text = "2 blocks yellow cheddar cheese"
                ),
                DeliTextExtractionInput(
                    id = "promo-1",
                    kind = DeliTextSourceKind.PROMO,
                    text = "SKU 0332094 Soup Chicken Noodle BOGO retail 4.99 sale 2.49 50%"
                ),
                DeliTextExtractionInput(
                    id = "order-1",
                    kind = DeliTextSourceKind.ORDER_SCREEN,
                    text = "0332094 - SOUP CHICKEN NOODLE 4 / 4 LB 2 0 WK"
                ),
                DeliTextExtractionInput(
                    id = "order-2",
                    kind = DeliTextSourceKind.ORDER_SCREEN,
                    text = "0080704 - PBX DELI SWISS (2) 18.18 LB 1 0 WK"
                ),
                DeliTextExtractionInput(
                    id = "note-1",
                    kind = DeliTextSourceKind.NOTE,
                    text = "please add 8 extra wings"
                )
            ),
            defaultAdStart = LocalDate.of(2026, 7, 27),
            defaultAdEnd = LocalDate.of(2026, 8, 2)
        )

        assertEquals(2, batch.inventoryItems.size)
        assertEquals(2.0, batch.inventoryItems.single { it.sku == "0332094" }.casesOnHand, 0.001)
        assertEquals(2.0, batch.inventoryItems.single { it.sku.startsWith("UNKNOWN-") }.casesOnHand, 0.001)
        assertEquals(listOf(0, 1), batch.orderLines.map { it.orderIndex })
        assertEquals(listOf("0332094", "0080704"), batch.orderLines.map { it.sku })
        assertEquals(PromoDealType.BOGO, batch.promoItems.single().dealType)
        assertEquals(listOf("please add 8 extra wings"), batch.stickyNotes)
        assertEquals(1, batch.verifyLabels.size)
        assertEquals(null, batch.verifyLabels.single().sku)
    }

    @Test
    fun preservesSupplierOrderRowsAcrossMultiplePhotographedPages() {
        val batch = DeliExtractionBatchBuilder.build(
            inputs = listOf(
                DeliTextExtractionInput(
                    id = "order-page-1",
                    kind = DeliTextSourceKind.ORDER_SCREEN,
                    text = """
                        0080704 - PBX DELI SWISS (2) 18.18 LB 1 0 WK
                        0075034 - FRYER CHICKEN TENDERLOIN 20 LB 4 6 6 WK
                    """.trimIndent()
                ),
                DeliTextExtractionInput(
                    id = "order-page-2",
                    kind = DeliTextSourceKind.ORDER_SCREEN,
                    text = """
                        0332094 - SOUP CHICKEN NOODLE 4 / 4 LB 2 0 WK
                        0927214 - ASIAGO SUN TOM SALAD KIT 4 / 12.5 OZ 3 0 WK
                    """.trimIndent()
                )
            ),
            defaultAdStart = LocalDate.of(2026, 7, 27),
            defaultAdEnd = LocalDate.of(2026, 8, 2)
        )

        assertEquals(listOf("0080704", "0075034", "0332094", "0927214"), batch.orderLines.map { it.sku })
        assertEquals(listOf(0, 1, 2, 3), batch.orderLines.map { it.orderIndex })
        assertEquals(listOf("18.18 LB", "20 LB", "4 / 4 LB", "4 / 12.5 OZ"), batch.orderLines.map { it.packSize })
        assertEquals(listOf(1.0, 4.0, 2.0, 3.0), batch.orderLines.map { it.suggestedCases })
    }

    @Test
    fun avoidsDoubleCountingDuplicateStacksAcrossNearbyPhotoAngles() {
        val batch = DeliExtractionBatchBuilder.build(
            inputs = listOf(
                DeliTextExtractionInput(
                    id = "angle-left",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.COOLER,
                    text = """
                        0332094 Blount Soup Chicken Noodle 4 / 4 LB Use By 07/31/26
                        0332094 Blount Soup Chicken Noodle 4 / 4 LB Use By 07/31/26
                    """.trimIndent()
                ),
                DeliTextExtractionInput(
                    id = "angle-right",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.COOLER,
                    text = """
                        0332094 BL0UNT Soup Chicken Noodle 4 / 4 LB U5E BY 07/31/26
                        O332094 Blount Soup Chicken Noodle 4 / 4 LB Use By 07/31/26
                    """.trimIndent()
                )
            ),
            defaultAdStart = LocalDate.of(2026, 7, 27),
            defaultAdEnd = LocalDate.of(2026, 8, 2)
        )

        val soup = batch.inventoryItems.single()
        assertEquals("0332094", soup.sku)
        assertEquals(2.0, soup.casesOnHand, 0.001)
        assertEquals(listOf("angle-left", "angle-right"), soup.photoRefs)
    }
}

