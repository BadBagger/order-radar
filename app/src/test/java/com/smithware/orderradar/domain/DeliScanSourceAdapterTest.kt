package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliScanSourceAdapterTest {
    @Test
    fun reviewedTextSourceCarriesSourceTypeAndInventoryLocationTags() {
        val source = DeliScanSourceAdapter.fromReviewedText(
            DeliScanSourceDraft(
                id = "slicer-1",
                kind = DeliTextSourceKind.INVENTORY,
                location = InventoryLocation.SLICER_BACKSTOCK,
                rawText = "2 blocks yellow cheddar cheese",
                receivedAtMillis = 200
            )
        )

        assertEquals(DeliTextSourceKind.INVENTORY, source.kind)
        assertEquals(DeliOcrTextSourceType.MANUAL_ENTRY, source.textSourceType)
        assertEquals(InventoryLocation.SLICER_BACKSTOCK, source.photoMetadata?.location)
        assertTrue(source.locationTags.contains("inventory"))
        assertTrue(source.locationTags.contains("slicer-backstock"))
    }

    @Test
    fun runnerBuildsSessionCountsForMixedDeliSources() {
        val session = DeliScanSessionRunner.buildReviewedTextSession(
            sessionId = "ui-session",
            today = LocalDate.of(2026, 7, 23),
            nowMillis = 400,
            drafts = listOf(
                DeliScanSourceDraft(
                    id = "cooler-1",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.COOLER,
                    rawText = "0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount",
                    receivedAtMillis = 100
                ),
                DeliScanSourceDraft(
                    id = "promo-1",
                    kind = DeliTextSourceKind.PROMO,
                    location = InventoryLocation.SALES_FLOOR,
                    rawText = "SKU 0332094 Soup Chicken Noodle BOGO retail 4.99 sale 2.49 50%",
                    receivedAtMillis = 200
                ),
                DeliScanSourceDraft(
                    id = "order-1",
                    kind = DeliTextSourceKind.ORDER_SCREEN,
                    location = InventoryLocation.COOLER,
                    rawText = "0332094 - SOUP CHICKEN NOODLE 4 / 4 LB 2 0 WK",
                    receivedAtMillis = 300
                )
            )
        )

        assertEquals(DeliScanSessionProgressState.READY_FOR_REVIEW, session.progress.state)
        assertEquals(3, session.summary?.processedSourceCount)
        assertEquals(1, session.summary?.inventoryItemCount)
        assertEquals(1, session.summary?.promoItemCount)
        assertEquals(1, session.summary?.orderLineCount)
        assertEquals(0, session.summary?.verifyItemCount)
    }

    @Test
    fun runnerSurfacesNeedsVerificationForLooseBackstock() {
        val session = DeliScanSessionRunner.buildReviewedTextSession(
            sessionId = "verify-session",
            today = LocalDate.of(2026, 7, 23),
            nowMillis = 400,
            drafts = listOf(
                DeliScanSourceDraft(
                    id = "freezer-1",
                    kind = DeliTextSourceKind.INVENTORY,
                    location = InventoryLocation.FREEZER,
                    rawText = "hard salami log",
                    receivedAtMillis = 100
                )
            )
        )

        assertEquals(DeliScanSessionProgressState.NEEDS_VERIFICATION, session.progress.state)
        assertEquals(1, session.summary?.inventoryItemCount)
        assertEquals(1, session.summary?.verifyItemCount)
        assertTrue(session.summary?.locationTags?.contains("freezer") == true)
    }
}
