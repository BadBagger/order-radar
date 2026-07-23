package com.smithware.orderradar.data

import com.smithware.orderradar.domain.DeliCategory
import com.smithware.orderradar.domain.DeliExtractionBatch
import com.smithware.orderradar.domain.DeliInventoryItem
import com.smithware.orderradar.domain.DeliOcrTextSourceType
import com.smithware.orderradar.domain.DeliScanSession
import com.smithware.orderradar.domain.DeliScanSessionProgress
import com.smithware.orderradar.domain.DeliScanSessionProgressState
import com.smithware.orderradar.domain.DeliScanSessionSummary
import com.smithware.orderradar.domain.DeliScanTextSource
import com.smithware.orderradar.domain.DeliSourcePhotoMetadata
import com.smithware.orderradar.domain.DeliTextSourceKind
import com.smithware.orderradar.domain.ExtractedDeliLabel
import com.smithware.orderradar.domain.InventoryLocation
import com.smithware.orderradar.domain.PromoDealType
import com.smithware.orderradar.domain.PromoItem
import com.smithware.orderradar.domain.SupplierOrderLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DeliScanSessionPersistenceTest {
    @Test
    fun saveLoadRoundTripKeepsSessionSnapshotAndParsedRows() {
        val store = InMemoryDeliStore()
        val session = sampleSession()

        val snapshotId = store.save(session)
        val loaded = store.load("deli-session-1")

        assertEquals(1L, snapshotId)
        assertEquals(session.sessionId, loaded?.sessionId)
        assertEquals(DeliScanSessionProgressState.NEEDS_VERIFICATION, loaded?.progress?.state)
        assertEquals(4, loaded?.sources?.size)
        assertEquals(setOf("cooler", "weekly-ad", "order-review"), loaded?.summary?.locationTags)
        assertEquals("0332094", loaded?.result?.inventoryItems?.single()?.sku)
        assertEquals(listOf("inventory-photo-1"), loaded?.result?.inventoryItems?.single()?.photoRefs)
        assertEquals(PromoDealType.BOGO, loaded?.result?.promoItems?.single()?.dealType)
        assertEquals(2.0, loaded?.result?.orderLines?.single()?.suggestedCases ?: 0.0, 0.001)
        assertEquals("Note: add extra soup for front case lunch rush", loaded?.result?.stickyNotes?.single())
    }

    @Test
    fun weekOverWeekSnapshotLookupUsesStableMondayBucket() {
        val store = InMemoryDeliStore(ZoneId.of("America/New_York"))
        val first = sampleSession(
            sessionId = "week-one",
            updatedAtMillis = 1_779_475_200_000
        )
        val nextWeek = sampleSession(
            sessionId = "week-two",
            updatedAtMillis = 1_780_079_600_000
        )

        val firstSnapshot = store.save(first)
        val secondSnapshot = store.save(nextWeek)
        val firstWeek = DeliScanSessionPersistenceMapper.weekStartEpochDay(first.updatedAtMillis, ZoneId.of("America/New_York"))
        val secondWeek = DeliScanSessionPersistenceMapper.weekStartEpochDay(nextWeek.updatedAtMillis, ZoneId.of("America/New_York"))

        assertTrue(secondWeek > firstWeek)
        assertEquals(firstSnapshot, store.latestSnapshotForWeek(firstWeek)?.id)
        assertEquals(secondSnapshot, store.latestSnapshotForWeek(secondWeek)?.id)
    }

    @Test
    fun unknownAndLowConfidenceVerifyLabelsSurviveRoundTrip() {
        val store = InMemoryDeliStore()
        val session = sampleSession().copy(
            result = sampleSession().result?.copy(
                inventoryItems = listOf(
                    DeliInventoryItem(
                        sku = "UNKNOWN-SLICER-1",
                        name = "hard salami log",
                        category = DeliCategory.DELI_MEAT,
                        casesOnHand = 1.0,
                        location = InventoryLocation.SLICER_BACKSTOCK,
                        confidence = 0.55,
                        photoRefs = listOf("slicer-photo-1"),
                        verified = false
                    )
                ),
                verifyLabels = listOf(
                    ExtractedDeliLabel(
                        itemName = "hard salami log",
                        sku = null,
                        confidence = 0.55,
                        sourcePhotoId = "slicer-photo-1"
                    )
                )
            ),
            summary = sampleSession().summary?.copy(inventoryItemCount = 1, verifyItemCount = 1)
        )

        store.save(session)
        val loadedVerify = store.load(session.sessionId)?.result?.verifyLabels?.single()

        assertNull(loadedVerify?.sku)
        assertEquals("hard salami log", loadedVerify?.itemName)
        assertEquals(0.55, loadedVerify?.confidence ?: 0.0, 0.001)
        assertEquals("slicer-photo-1", loadedVerify?.sourcePhotoId)
    }

    private fun sampleSession(
        sessionId: String = "deli-session-1",
        updatedAtMillis: Long = 1_779_475_200_000
    ): DeliScanSession {
        val createdAt = updatedAtMillis - 1_000
        val sources = listOf(
            source("inventory-photo-1", DeliTextSourceKind.INVENTORY, InventoryLocation.COOLER, setOf("cooler")),
            source("promo-import-1", DeliTextSourceKind.PROMO, InventoryLocation.SALES_FLOOR, setOf("weekly-ad")),
            source("order-screen-1", DeliTextSourceKind.ORDER_SCREEN, InventoryLocation.COOLER, setOf("order-review")),
            source("note-1", DeliTextSourceKind.NOTE, InventoryLocation.COOLER, emptySet())
        )
        return DeliScanSession(
            sessionId = sessionId,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAtMillis,
            sources = sources,
            duplicateSourceIds = setOf("duplicate-source"),
            progress = DeliScanSessionProgress(
                state = DeliScanSessionProgressState.NEEDS_VERIFICATION,
                completedSources = 4,
                totalSources = 4,
                message = "Review low-confidence or unknown deli items.",
                updatedAtMillis = updatedAtMillis
            ),
            result = DeliExtractionBatch(
                inventoryItems = listOf(
                    DeliInventoryItem(
                        sku = "0332094",
                        name = "Soup Chicken Noodle",
                        category = DeliCategory.SOUPS,
                        casesOnHand = 2.0,
                        caseWeightLbs = 16.0,
                        useByDate = LocalDate.of(2026, 7, 31),
                        location = InventoryLocation.COOLER,
                        confidence = 0.92,
                        photoRefs = listOf("inventory-photo-1"),
                        verified = true,
                        brandVendor = "Blount"
                    )
                ),
                promoItems = listOf(
                    PromoItem(
                        sku = "0332094",
                        name = "Soup Chicken Noodle",
                        retailPrice = 4.99,
                        salePrice = 2.49,
                        dealType = PromoDealType.BOGO,
                        discountPct = 50.0,
                        adStartDate = LocalDate.of(2026, 7, 27),
                        adEndDate = LocalDate.of(2026, 8, 2),
                        placement = "weekly ad",
                        expectedDemandMultiplier = 2.25
                    )
                ),
                orderLines = listOf(
                    SupplierOrderLine(
                        sku = "0332094",
                        name = "Soup Chicken Noodle",
                        packSize = "4 / 4 LB",
                        suggestedCases = 2.0,
                        forecastDemandCases = 2.0,
                        safetyStockCases = 1.0,
                        orderIndex = 0
                    )
                ),
                verifyLabels = listOf(
                    ExtractedDeliLabel(
                        itemName = "Unknown cheese block",
                        sku = null,
                        confidence = 0.55,
                        sourcePhotoId = "inventory-photo-1"
                    )
                ),
                stickyNotes = listOf("Note: add extra soup for front case lunch rush")
            ),
            summary = DeliScanSessionSummary(
                totalSourceCount = 4,
                duplicateSourceCount = 1,
                processedSourceCount = 4,
                inventoryItemCount = 1,
                promoItemCount = 1,
                orderLineCount = 1,
                verifyItemCount = 1,
                stickyNoteCount = 1,
                locationTags = setOf("cooler", "weekly-ad", "order-review")
            )
        )
    }

    private fun source(
        id: String,
        kind: DeliTextSourceKind,
        location: InventoryLocation,
        tags: Set<String>
    ): DeliScanTextSource =
        DeliScanTextSource(
            id = id,
            kind = kind,
            text = "Reviewed source $id",
            textSourceType = DeliOcrTextSourceType.MANUAL_ENTRY,
            photoMetadata = DeliSourcePhotoMetadata(
                photoId = id,
                uri = "reviewed-text://$id",
                capturedAtMillis = 1_779_475_199_000,
                location = location,
                locationTags = tags,
                widthPx = 1920,
                heightPx = 1080,
                sizeBytes = 240_000
            ),
            locationTags = tags,
            receivedAtMillis = 1_779_475_200_000
        )
}

private class InMemoryDeliStore(private val zoneId: ZoneId = ZoneId.of("UTC")) {
    private val saved = mutableMapOf<String, DeliScanSessionEntityBundle>()
    private var nextSnapshotId = 1L

    fun save(session: DeliScanSession): Long {
        val bundle = DeliScanSessionPersistenceMapper.toEntityBundle(session, zoneId)
        val snapshotId = bundle.snapshot?.let { nextSnapshotId++ } ?: 0L
        saved[session.sessionId] = bundle.copy(
            snapshot = bundle.snapshot?.copy(id = snapshotId),
            inventoryItems = bundle.inventoryItems.map { it.copy(snapshotId = snapshotId) }
        )
        return snapshotId
    }

    fun load(sessionId: String): DeliScanSession? =
        saved[sessionId]?.let(DeliScanSessionPersistenceMapper::fromEntityBundle)

    fun latestSnapshotForWeek(weekStartEpochDay: Long): DeliInventorySnapshotRecord? =
        saved.values
            .mapNotNull { it.snapshot }
            .filter { it.weekStartEpochDay == weekStartEpochDay }
            .maxByOrNull { it.capturedAtMillis }
}
