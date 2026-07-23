package com.smithware.orderradar.domain

import java.time.LocalDate
import java.util.Locale

data class DeliScanSourceDraft(
    val id: String,
    val kind: DeliTextSourceKind,
    val location: InventoryLocation,
    val rawText: String,
    val textSourceType: DeliOcrTextSourceType = DeliOcrTextSourceType.MANUAL_ENTRY,
    val receivedAtMillis: Long,
    val capturedAtMillis: Long = receivedAtMillis
)

object DeliScanSourceAdapter {
    fun fromReviewedText(draft: DeliScanSourceDraft): DeliScanTextSource {
        require(draft.rawText.isNotBlank()) { "Deli scan source text cannot be blank" }
        val normalizedTags = buildSet {
            add(draft.kind.name.readableTag())
            add(draft.location.name.readableTag())
        }
        return DeliScanTextSource(
            id = draft.id,
            kind = draft.kind,
            text = draft.rawText.trim(),
            textSourceType = draft.textSourceType,
            photoMetadata = DeliSourcePhotoMetadata(
                photoId = draft.id,
                uri = "reviewed-text://${draft.id}",
                capturedAtMillis = draft.capturedAtMillis,
                location = draft.location,
                locationTags = normalizedTags
            ),
            locationTags = normalizedTags,
            receivedAtMillis = draft.receivedAtMillis
        )
    }
}

object DeliScanSessionRunner {
    fun buildReviewedTextSession(
        sessionId: String,
        drafts: List<DeliScanSourceDraft>,
        today: LocalDate,
        nowMillis: Long,
        confidenceThreshold: Double = 0.80
    ): DeliScanSession {
        val sources = drafts.map(DeliScanSourceAdapter::fromReviewedText)
        return DeliScanSessionAssembler.assemble(
            DeliScanSessionRequest(
                sessionId = sessionId,
                sources = sources,
                defaultAdStart = today.plusDays(1),
                defaultAdEnd = today.plusDays(7),
                createdAtMillis = nowMillis,
                confidenceThreshold = confidenceThreshold
            )
        )
    }
}

private fun String.readableTag(): String =
    lowercase(Locale.US).replace('_', '-')
