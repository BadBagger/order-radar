package com.smithware.orderradar.data

import com.smithware.orderradar.domain.DeliExtractionBatch
import com.smithware.orderradar.domain.DeliInventoryItem
import com.smithware.orderradar.domain.DeliScanSession
import com.smithware.orderradar.domain.DeliScanSessionProgress
import com.smithware.orderradar.domain.DeliScanSessionSummary
import com.smithware.orderradar.domain.DeliScanTextSource
import com.smithware.orderradar.domain.DeliSourcePhotoMetadata
import com.smithware.orderradar.domain.ExtractedDeliLabel
import com.smithware.orderradar.domain.PromoItem
import com.smithware.orderradar.domain.SupplierOrderLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

object DeliScanSessionPersistenceMapper {
    fun toEntityBundle(session: DeliScanSession, zoneId: ZoneId = ZoneId.systemDefault()): DeliScanSessionEntityBundle {
        val createdAt = session.createdAtMillis
        val summary = session.summary
        val batch = session.result
        val sourceRefs = session.sources.map { it.id }.joinStable()
        val snapshot = batch?.let {
            DeliInventorySnapshotRecord(
                sessionId = session.sessionId,
                capturedAtMillis = session.updatedAtMillis,
                weekStartEpochDay = weekStartEpochDay(session.updatedAtMillis, zoneId),
                status = session.progress.state,
                sourceRefs = sourceRefs,
                notes = session.failedReason
            )
        }
        return DeliScanSessionEntityBundle(
            session = DeliScanSessionRecord(
                sessionId = session.sessionId,
                createdAtMillis = session.createdAtMillis,
                updatedAtMillis = session.updatedAtMillis,
                progressState = session.progress.state,
                completedSources = session.progress.completedSources,
                totalSources = session.progress.totalSources,
                progressMessage = session.progress.message,
                progressUpdatedAtMillis = session.progress.updatedAtMillis,
                failedReason = session.failedReason,
                duplicateSourceIds = session.duplicateSourceIds.joinStable(),
                totalSourceCount = summary?.totalSourceCount ?: session.sources.size,
                duplicateSourceCount = summary?.duplicateSourceCount ?: session.duplicateSourceIds.size,
                processedSourceCount = summary?.processedSourceCount ?: session.sources.size,
                inventoryItemCount = summary?.inventoryItemCount ?: batch?.inventoryItems.orEmpty().size,
                promoItemCount = summary?.promoItemCount ?: batch?.promoItems.orEmpty().size,
                orderLineCount = summary?.orderLineCount ?: batch?.orderLines.orEmpty().size,
                verifyItemCount = summary?.verifyItemCount ?: batch?.verifyLabels.orEmpty().size,
                stickyNoteCount = summary?.stickyNoteCount ?: batch?.stickyNotes.orEmpty().size,
                locationTags = summary?.locationTags.orEmpty().joinStable()
            ),
            sources = session.sources.map { source ->
                val photo = source.photoMetadata
                DeliScanSourceRecord(
                    sessionId = session.sessionId,
                    sourceId = source.id,
                    kind = source.kind,
                    text = source.text,
                    textSourceType = source.textSourceType,
                    photoId = photo?.photoId,
                    uri = photo?.uri,
                    capturedAtMillis = source.capturedAtMillis,
                    receivedAtMillis = source.receivedAtMillis,
                    location = photo?.location,
                    locationTags = (source.locationTags + photo?.locationTags.orEmpty()).joinStable(),
                    widthPx = photo?.widthPx,
                    heightPx = photo?.heightPx,
                    sizeBytes = photo?.sizeBytes
                )
            },
            snapshot = snapshot,
            inventoryItems = batch?.inventoryItems.orEmpty().map { item ->
                DeliInventorySnapshotItemRecord(
                    snapshotId = 0,
                    sessionId = session.sessionId,
                    sku = item.sku,
                    name = item.name,
                    category = item.category,
                    casesOnHand = item.casesOnHand,
                    caseWeightLbs = item.caseWeightLbs,
                    useByEpochDay = item.useByDate?.toEpochDay(),
                    location = item.location,
                    confidence = item.confidence,
                    verified = item.verified,
                    brandVendor = item.brandVendor,
                    sourceRefs = item.photoRefs.joinStable(),
                    createdAtMillis = createdAt
                )
            },
            promoItems = batch?.promoItems.orEmpty().map { promo ->
                DeliPromoItemRecord(
                    sessionId = session.sessionId,
                    sku = promo.sku,
                    name = promo.name,
                    retailPrice = promo.retailPrice,
                    salePrice = promo.salePrice,
                    dealType = promo.dealType,
                    discountPct = promo.discountPct,
                    adStartEpochDay = promo.adStartDate.toEpochDay(),
                    adEndEpochDay = promo.adEndDate.toEpochDay(),
                    placement = promo.placement,
                    expectedDemandMultiplier = promo.expectedDemandMultiplier,
                    sourceRefs = sourceRefs,
                    createdAtMillis = createdAt
                )
            },
            orderLines = batch?.orderLines.orEmpty().map { line ->
                DeliSupplierOrderLineRecord(
                    sessionId = session.sessionId,
                    sku = line.sku,
                    name = line.name,
                    packSize = line.packSize,
                    suggestedCases = line.suggestedCases,
                    forecastDemandCases = line.forecastDemandCases,
                    safetyStockCases = line.safetyStockCases,
                    orderIndex = line.orderIndex,
                    sourceRefs = sourceRefs,
                    createdAtMillis = createdAt
                )
            },
            verifyLabels = batch?.verifyLabels.orEmpty().map { label ->
                DeliVerifyLabelRecord(
                    sessionId = session.sessionId,
                    itemName = label.itemName,
                    sku = label.sku,
                    packSize = label.packSize,
                    caseWeightLbs = label.caseWeightLbs,
                    packDateEpochDay = label.packDate?.toEpochDay(),
                    useByEpochDay = label.useByDate?.toEpochDay(),
                    brandVendor = label.brandVendor,
                    confidence = label.confidence,
                    sourcePhotoId = label.sourcePhotoId,
                    createdAtMillis = createdAt
                )
            },
            stickyNotes = batch?.stickyNotes.orEmpty().map { note ->
                DeliStickyNoteRecord(
                    sessionId = session.sessionId,
                    noteText = note,
                    sourceRefs = sourceRefs,
                    createdAtMillis = createdAt
                )
            }
        )
    }

    fun fromEntityBundle(bundle: DeliScanSessionEntityBundle): DeliScanSession =
        DeliScanSession(
            sessionId = bundle.session.sessionId,
            createdAtMillis = bundle.session.createdAtMillis,
            updatedAtMillis = bundle.session.updatedAtMillis,
            sources = bundle.sources.map { it.toDomain() },
            duplicateSourceIds = bundle.session.duplicateSourceIds.splitStable().toSet(),
            progress = DeliScanSessionProgress(
                state = bundle.session.progressState,
                completedSources = bundle.session.completedSources,
                totalSources = bundle.session.totalSources,
                message = bundle.session.progressMessage,
                updatedAtMillis = bundle.session.progressUpdatedAtMillis
            ),
            result = DeliExtractionBatch(
                inventoryItems = bundle.inventoryItems.map { it.toDomain() },
                promoItems = bundle.promoItems.map { it.toDomain() },
                orderLines = bundle.orderLines.map { it.toDomain() },
                verifyLabels = bundle.verifyLabels.map { it.toDomain() },
                stickyNotes = bundle.stickyNotes.map { it.noteText }
            ),
            summary = DeliScanSessionSummary(
                totalSourceCount = bundle.session.totalSourceCount,
                duplicateSourceCount = bundle.session.duplicateSourceCount,
                processedSourceCount = bundle.session.processedSourceCount,
                inventoryItemCount = bundle.session.inventoryItemCount,
                promoItemCount = bundle.session.promoItemCount,
                orderLineCount = bundle.session.orderLineCount,
                verifyItemCount = bundle.session.verifyItemCount,
                stickyNoteCount = bundle.session.stickyNoteCount,
                locationTags = bundle.session.locationTags.splitStable().toSet()
            ),
            failedReason = bundle.session.failedReason
        )

    fun weekStartEpochDay(millis: Long, zoneId: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis)
            .atZone(zoneId)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toEpochDay()
}

private fun DeliScanSourceRecord.toDomain(): DeliScanTextSource {
    val tags = locationTags.splitStable().toSet()
    val photo = if (photoId != null && uri != null && capturedAtMillis != null && location != null) {
        DeliSourcePhotoMetadata(
            photoId = photoId,
            uri = uri,
            capturedAtMillis = capturedAtMillis,
            location = location,
            locationTags = tags,
            widthPx = widthPx,
            heightPx = heightPx,
            sizeBytes = sizeBytes
        )
    } else {
        null
    }
    return DeliScanTextSource(
        id = sourceId,
        kind = kind,
        text = text,
        textSourceType = textSourceType,
        photoMetadata = photo,
        locationTags = tags,
        capturedAtMillis = capturedAtMillis,
        receivedAtMillis = receivedAtMillis
    )
}

private fun DeliInventorySnapshotItemRecord.toDomain(): DeliInventoryItem =
    DeliInventoryItem(
        sku = sku,
        name = name,
        category = category,
        casesOnHand = casesOnHand,
        caseWeightLbs = caseWeightLbs,
        useByDate = useByEpochDay?.let(LocalDate::ofEpochDay),
        location = location,
        confidence = confidence,
        photoRefs = sourceRefs.splitStable(),
        verified = verified,
        brandVendor = brandVendor
    )

private fun DeliPromoItemRecord.toDomain(): PromoItem =
    PromoItem(
        sku = sku,
        name = name,
        retailPrice = retailPrice,
        salePrice = salePrice,
        dealType = dealType,
        discountPct = discountPct,
        adStartDate = LocalDate.ofEpochDay(adStartEpochDay),
        adEndDate = LocalDate.ofEpochDay(adEndEpochDay),
        placement = placement,
        expectedDemandMultiplier = expectedDemandMultiplier
    )

private fun DeliSupplierOrderLineRecord.toDomain(): SupplierOrderLine =
    SupplierOrderLine(
        sku = sku,
        name = name,
        packSize = packSize,
        suggestedCases = suggestedCases,
        forecastDemandCases = forecastDemandCases,
        safetyStockCases = safetyStockCases,
        orderIndex = orderIndex
    )

private fun DeliVerifyLabelRecord.toDomain(): ExtractedDeliLabel =
    ExtractedDeliLabel(
        itemName = itemName,
        sku = sku,
        packSize = packSize,
        caseWeightLbs = caseWeightLbs,
        packDate = packDateEpochDay?.let(LocalDate::ofEpochDay),
        useByDate = useByEpochDay?.let(LocalDate::ofEpochDay),
        brandVendor = brandVendor,
        confidence = confidence,
        sourcePhotoId = sourcePhotoId
    )

private fun Iterable<String>.joinStable(): String =
    filter { it.isNotBlank() }.distinct().joinToString("\n")

private fun String.splitStable(): List<String> =
    lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
