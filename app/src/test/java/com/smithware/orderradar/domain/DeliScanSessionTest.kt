package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class DeliScanSessionTest {
    @Test
    fun assemblesSessionAroundExtractionBatchWithSourceMetadata() {
        val session = DeliScanSessionAssembler.assemble(
            DeliScanSessionRequest(
                sessionId = "scan-20260723-001",
                createdAtMillis = 1_779_406_400_000,
                defaultAdStart = LocalDate.of(2026, 7, 27),
                defaultAdEnd = LocalDate.of(2026, 8, 2),
                sources = listOf(
                    textSource(
                        id = "cooler-1",
                        text = "0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount",
                        location = InventoryLocation.COOLER,
                        tags = setOf("cooler", "case-rack")
                    ),
                    textSource(
                        id = "promo-1",
                        kind = DeliTextSourceKind.PROMO,
                        text = "SKU 0332094 Soup Chicken Noodle BOGO retail 4.99 sale 2.49 50%",
                        location = InventoryLocation.SALES_FLOOR,
                        textSourceType = DeliOcrTextSourceType.IMPORTED_TEXT,
                        tags = setOf("weekly-ad")
                    ),
                    textSource(
                        id = "order-1",
                        kind = DeliTextSourceKind.ORDER_SCREEN,
                        text = "0332094 - SOUP CHICKEN NOODLE 4 / 4 LB 2 0 WK",
                        location = InventoryLocation.COOLER,
                        tags = setOf("order-review")
                    )
                )
            )
        )

        assertEquals("scan-20260723-001", session.sessionId)
        assertEquals(DeliScanSessionProgressState.READY_FOR_REVIEW, session.progress.state)
        assertEquals(3, session.sources.size)
        assertEquals(DeliOcrTextSourceType.ON_DEVICE_OCR, session.sources.first().textSourceType)
        assertEquals(InventoryLocation.COOLER, session.sources.first().photoMetadata?.location)
        assertEquals(setOf("cooler", "case-rack", "weekly-ad", "order-review"), session.summary?.locationTags)
        assertEquals(1, session.result?.inventoryItems?.size)
        assertEquals(1, session.result?.promoItems?.size)
        assertEquals(1, session.result?.orderLines?.size)
    }

    @Test
    fun statusTransitionsAllowExpectedScanFlowAndRejectBackwardsMoves() {
        val created = emptySession(DeliScanSessionProgressState.CREATED)
        val ready = DeliScanSessionStateMachine.transition(
            created,
            DeliScanSessionProgressState.SOURCES_READY,
            updatedAtMillis = 200
        )
        val extracting = DeliScanSessionStateMachine.transition(
            ready,
            DeliScanSessionProgressState.EXTRACTING_TEXT,
            updatedAtMillis = 300
        )
        val building = DeliScanSessionStateMachine.transition(
            extracting,
            DeliScanSessionProgressState.BUILDING_BATCH,
            updatedAtMillis = 400
        )

        assertEquals(DeliScanSessionProgressState.BUILDING_BATCH, building.progress.state)
        assertEquals(400, building.updatedAtMillis)
        try {
            DeliScanSessionStateMachine.transition(
                building,
                DeliScanSessionProgressState.CREATED,
                updatedAtMillis = 500
            )
            fail("Expected backwards transition to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun duplicateSourceIdsAreIgnoredBeforeBatchAssembly() {
        val session = DeliScanSessionAssembler.assemble(
            DeliScanSessionRequest(
                sessionId = "scan-dupes",
                createdAtMillis = 100,
                defaultAdStart = LocalDate.of(2026, 7, 27),
                defaultAdEnd = LocalDate.of(2026, 8, 2),
                sources = listOf(
                    textSource(
                        id = "cooler-1",
                        text = "0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount",
                        location = InventoryLocation.COOLER
                    ),
                    textSource(
                        id = "cooler-1",
                        text = "0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount",
                        location = InventoryLocation.COOLER
                    ),
                    textSource(
                        id = "cooler-2",
                        text = "0332094 Soup Chicken Noodle 4 / 4 LB Use By 07/31/26 Brand Blount",
                        location = InventoryLocation.COOLER
                    )
                )
            )
        )

        assertEquals(setOf("cooler-1"), session.duplicateSourceIds)
        assertEquals(2, session.sources.size)
        assertEquals(3, session.summary?.totalSourceCount)
        assertEquals(1, session.summary?.duplicateSourceCount)
        assertEquals(2.0, session.result?.inventoryItems?.single()?.casesOnHand ?: 0.0, 0.001)
    }

    @Test
    fun verifyItemCountSummarizesUnknownAndLowConfidenceItems() {
        val session = DeliScanSessionAssembler.assemble(
            DeliScanSessionRequest(
                sessionId = "scan-verify",
                createdAtMillis = 100,
                defaultAdStart = LocalDate.of(2026, 7, 27),
                defaultAdEnd = LocalDate.of(2026, 8, 2),
                sources = listOf(
                    textSource(
                        id = "slicer-1",
                        text = """
                            2 blocks yellow cheddar cheese
                            hard salami log
                        """.trimIndent(),
                        location = InventoryLocation.SLICER_BACKSTOCK,
                        tags = setOf("slicer")
                    )
                )
            )
        )

        assertEquals(DeliScanSessionProgressState.NEEDS_VERIFICATION, session.progress.state)
        assertEquals(2, session.summary?.inventoryItemCount)
        assertEquals(2, session.summary?.verifyItemCount)
        assertEquals(2, session.result?.verifyLabels?.size)
        assertTrue(session.result?.verifyLabels?.all { it.sku == null } == true)
    }

    private fun textSource(
        id: String,
        text: String,
        location: InventoryLocation,
        kind: DeliTextSourceKind = DeliTextSourceKind.INVENTORY,
        textSourceType: DeliOcrTextSourceType = DeliOcrTextSourceType.ON_DEVICE_OCR,
        tags: Set<String> = emptySet()
    ): DeliScanTextSource =
        DeliScanTextSource(
            id = id,
            kind = kind,
            text = text,
            textSourceType = textSourceType,
            photoMetadata = DeliSourcePhotoMetadata(
                photoId = "photo-$id",
                uri = "content://order-radar/$id",
                capturedAtMillis = 1_779_406_400_000,
                location = location,
                locationTags = tags,
                widthPx = 1920,
                heightPx = 1080,
                sizeBytes = 240_000
            ),
            locationTags = tags,
            receivedAtMillis = 1_779_406_401_000
        )

    private fun emptySession(state: DeliScanSessionProgressState): DeliScanSession =
        DeliScanSession(
            sessionId = "scan-state",
            createdAtMillis = 100,
            updatedAtMillis = 100,
            sources = emptyList(),
            duplicateSourceIds = emptySet(),
            progress = DeliScanSessionProgress(
                state = state,
                completedSources = 0,
                totalSources = 0,
                message = "state",
                updatedAtMillis = 100
            )
        ).also { assertNotNull(it) }
}
