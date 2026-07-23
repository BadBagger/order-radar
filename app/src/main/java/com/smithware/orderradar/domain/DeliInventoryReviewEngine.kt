package com.smithware.orderradar.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.round

data class DeliInventoryReviewGroup(
    val sku: String,
    val category: DeliCategory,
    val location: InventoryLocation,
    val items: List<DeliInventoryItem>
) {
    val totalCases: Double = items.sumOf { it.casesOnHand }
    val totalPounds: Double? = items.mapNotNull { item -> item.caseWeightLbs?.let { it * item.casesOnHand } }
        .takeIf { it.isNotEmpty() }
        ?.sum()
    val needsVerification: Boolean = items.any { !it.verified || it.confidence < 0.80 || it.sku.startsWith("UNKNOWN-") }
    val hasMergeReview: Boolean = items.size > 1
}

data class DeliInventoryItemEdit(
    val sku: String? = null,
    val name: String? = null,
    val category: DeliCategory? = null,
    val casesOnHand: Double? = null,
    val caseWeightLbs: Double? = null,
    val useByDate: LocalDate? = null,
    val location: InventoryLocation? = null,
    val brandVendor: String? = null,
    val verified: Boolean? = null
)

object DeliInventoryReviewEngine {
    fun groupedCards(items: List<DeliInventoryItem>): List<DeliInventoryReviewGroup> =
        items.groupBy { item -> ReviewKey(item.sku.trim().uppercase(), item.category, item.location) }
            .values
            .map { group ->
                val first = group.first()
                DeliInventoryReviewGroup(
                    sku = first.sku,
                    category = first.category,
                    location = first.location,
                    items = group.sortedWith(compareBy<DeliInventoryItem> { it.useByDate ?: LocalDate.MAX }.thenBy { it.name })
                )
            }
            .sortedWith(compareBy<DeliInventoryReviewGroup> { it.category.name }.thenBy { it.location.name }.thenBy { it.sku })

    fun adjustCases(item: DeliInventoryItem, delta: Double): DeliInventoryItem =
        item.copy(
            casesOnHand = roundToHalf(max(0.0, item.casesOnHand + delta)),
            verified = true,
            confidence = max(item.confidence, 0.95)
        )

    fun applyEdit(item: DeliInventoryItem, edit: DeliInventoryItemEdit): DeliInventoryItem {
        val edited = item.copy(
            sku = edit.sku?.trim()?.ifBlank { item.sku } ?: item.sku,
            name = edit.name?.trim()?.ifBlank { item.name } ?: item.name,
            category = edit.category ?: item.category,
            casesOnHand = edit.casesOnHand?.let { roundToHalf(max(0.0, it)) } ?: item.casesOnHand,
            caseWeightLbs = edit.caseWeightLbs?.takeIf { it > 0.0 } ?: item.caseWeightLbs,
            useByDate = edit.useByDate ?: item.useByDate,
            location = edit.location ?: item.location,
            brandVendor = edit.brandVendor?.trim()?.ifBlank { null } ?: item.brandVendor,
            verified = edit.verified ?: true
        )
        return edited.copy(confidence = if (edited.verified) max(edited.confidence, 0.95) else edited.confidence)
    }

    fun mergeGroup(items: List<DeliInventoryItem>): DeliInventoryItem {
        require(items.isNotEmpty()) { "Cannot merge an empty deli inventory group" }
        val first = items.first()
        return first.copy(
            casesOnHand = roundToHalf(items.sumOf { it.casesOnHand }),
            confidence = items.minOf { it.confidence },
            photoRefs = items.flatMap { it.photoRefs }.distinct(),
            verified = items.all { it.verified },
            brandVendor = first.brandVendor ?: items.firstNotNullOfOrNull { it.brandVendor },
            caseWeightLbs = first.caseWeightLbs ?: items.firstNotNullOfOrNull { it.caseWeightLbs },
            useByDate = items.mapNotNull { it.useByDate }.minOrNull() ?: first.useByDate
        )
    }

    private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0
}

private data class ReviewKey(
    val sku: String,
    val category: DeliCategory,
    val location: InventoryLocation
)
