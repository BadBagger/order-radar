package com.smithware.orderradar.domain

import java.time.LocalDate

enum class DeliOcrTextSourceType {
    ON_DEVICE_OCR,
    VISION_MODEL,
    MANUAL_ENTRY,
    IMPORTED_TEXT
}

enum class DeliScanSessionProgressState {
    CREATED,
    SOURCES_READY,
    EXTRACTING_TEXT,
    BUILDING_BATCH,
    NEEDS_VERIFICATION,
    READY_FOR_REVIEW,
    FAILED
}

data class DeliSourcePhotoMetadata(
    val photoId: String,
    val uri: String,
    val capturedAtMillis: Long,
    val location: InventoryLocation,
    val locationTags: Set<String> = emptySet(),
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val sizeBytes: Long? = null
)

data class DeliScanTextSource(
    val id: String,
    val kind: DeliTextSourceKind,
    val text: String,
    val textSourceType: DeliOcrTextSourceType,
    val photoMetadata: DeliSourcePhotoMetadata? = null,
    val locationTags: Set<String> = emptySet(),
    val capturedAtMillis: Long? = photoMetadata?.capturedAtMillis,
    val receivedAtMillis: Long
)

data class DeliScanSessionSummary(
    val totalSourceCount: Int,
    val duplicateSourceCount: Int,
    val processedSourceCount: Int,
    val inventoryItemCount: Int,
    val promoItemCount: Int,
    val orderLineCount: Int,
    val verifyItemCount: Int,
    val stickyNoteCount: Int,
    val locationTags: Set<String>
)

data class DeliScanSessionProgress(
    val state: DeliScanSessionProgressState,
    val completedSources: Int,
    val totalSources: Int,
    val message: String,
    val updatedAtMillis: Long
)

data class DeliScanSession(
    val sessionId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val sources: List<DeliScanTextSource>,
    val duplicateSourceIds: Set<String>,
    val progress: DeliScanSessionProgress,
    val result: DeliExtractionBatch? = null,
    val summary: DeliScanSessionSummary? = null,
    val failedReason: String? = null
)

data class DeliScanSessionRequest(
    val sessionId: String,
    val sources: List<DeliScanTextSource>,
    val defaultAdStart: LocalDate,
    val defaultAdEnd: LocalDate,
    val createdAtMillis: Long,
    val confidenceThreshold: Double = 0.80
)

object DeliScanSessionAssembler {
    fun assemble(request: DeliScanSessionRequest): DeliScanSession {
        val uniqueSources = request.sources.distinctBy { it.id }
        val duplicateIds = request.sources
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val batch = DeliExtractionBatchBuilder.build(
            inputs = uniqueSources.map { source ->
                DeliTextExtractionInput(
                    id = source.id,
                    kind = source.kind,
                    text = source.text,
                    location = source.photoMetadata?.location
                )
            },
            defaultAdStart = request.defaultAdStart,
            defaultAdEnd = request.defaultAdEnd,
            confidenceThreshold = request.confidenceThreshold
        )
        val summary = batch.toSessionSummary(
            totalSources = request.sources.size,
            duplicateSources = duplicateIds.size,
            processedSources = uniqueSources.size,
            locationTags = uniqueSources.flatMap { source ->
                source.locationTags + source.photoMetadata.locationTagsOrEmpty()
            }.toSet()
        )
        val state = if (summary.verifyItemCount > 0) {
            DeliScanSessionProgressState.NEEDS_VERIFICATION
        } else {
            DeliScanSessionProgressState.READY_FOR_REVIEW
        }

        return DeliScanSession(
            sessionId = request.sessionId,
            createdAtMillis = request.createdAtMillis,
            updatedAtMillis = request.createdAtMillis,
            sources = uniqueSources,
            duplicateSourceIds = duplicateIds,
            progress = DeliScanSessionProgress(
                state = state,
                completedSources = uniqueSources.size,
                totalSources = uniqueSources.size,
                message = state.defaultMessage(),
                updatedAtMillis = request.createdAtMillis
            ),
            result = batch,
            summary = summary
        )
    }
}

object DeliScanSessionStateMachine {
    fun transition(
        session: DeliScanSession,
        nextState: DeliScanSessionProgressState,
        updatedAtMillis: Long,
        message: String = nextState.defaultMessage(),
        failedReason: String? = null
    ): DeliScanSession {
        require(nextState in allowedTransitions.getValue(session.progress.state)) {
            "Cannot transition deli scan session from ${session.progress.state} to $nextState"
        }
        return session.copy(
            updatedAtMillis = updatedAtMillis,
            progress = session.progress.copy(
                state = nextState,
                message = message,
                updatedAtMillis = updatedAtMillis
            ),
            failedReason = failedReason
        )
    }

    private val allowedTransitions = mapOf(
        DeliScanSessionProgressState.CREATED to setOf(
            DeliScanSessionProgressState.SOURCES_READY,
            DeliScanSessionProgressState.FAILED
        ),
        DeliScanSessionProgressState.SOURCES_READY to setOf(
            DeliScanSessionProgressState.EXTRACTING_TEXT,
            DeliScanSessionProgressState.BUILDING_BATCH,
            DeliScanSessionProgressState.FAILED
        ),
        DeliScanSessionProgressState.EXTRACTING_TEXT to setOf(
            DeliScanSessionProgressState.BUILDING_BATCH,
            DeliScanSessionProgressState.FAILED
        ),
        DeliScanSessionProgressState.BUILDING_BATCH to setOf(
            DeliScanSessionProgressState.NEEDS_VERIFICATION,
            DeliScanSessionProgressState.READY_FOR_REVIEW,
            DeliScanSessionProgressState.FAILED
        ),
        DeliScanSessionProgressState.NEEDS_VERIFICATION to setOf(
            DeliScanSessionProgressState.READY_FOR_REVIEW,
            DeliScanSessionProgressState.FAILED
        ),
        DeliScanSessionProgressState.READY_FOR_REVIEW to emptySet(),
        DeliScanSessionProgressState.FAILED to emptySet()
    )
}

private fun DeliExtractionBatch.toSessionSummary(
    totalSources: Int,
    duplicateSources: Int,
    processedSources: Int,
    locationTags: Set<String>
): DeliScanSessionSummary =
    DeliScanSessionSummary(
        totalSourceCount = totalSources,
        duplicateSourceCount = duplicateSources,
        processedSourceCount = processedSources,
        inventoryItemCount = inventoryItems.size,
        promoItemCount = promoItems.size,
        orderLineCount = orderLines.size,
        verifyItemCount = verifyLabels.size,
        stickyNoteCount = stickyNotes.size,
        locationTags = locationTags
    )

private fun DeliScanSessionProgressState.defaultMessage(): String =
    when (this) {
        DeliScanSessionProgressState.CREATED -> "Scan session created."
        DeliScanSessionProgressState.SOURCES_READY -> "Photos and text sources are ready."
        DeliScanSessionProgressState.EXTRACTING_TEXT -> "Extracting text from deli photos."
        DeliScanSessionProgressState.BUILDING_BATCH -> "Building deli inventory-to-order batch."
        DeliScanSessionProgressState.NEEDS_VERIFICATION -> "Review low-confidence or unknown deli items."
        DeliScanSessionProgressState.READY_FOR_REVIEW -> "Deli scan is ready for order review."
        DeliScanSessionProgressState.FAILED -> "Deli scan session failed."
    }

private fun DeliSourcePhotoMetadata?.locationTagsOrEmpty(): Set<String> = this?.locationTags.orEmpty()
