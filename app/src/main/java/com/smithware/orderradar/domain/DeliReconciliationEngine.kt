package com.smithware.orderradar.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

object DeliPromoDefaults {
    fun multiplierFor(dealType: PromoDealType, discountPct: Double? = null): Double =
        when (dealType) {
            PromoDealType.BOGO -> 2.25
            PromoDealType.B2G1 -> 1.65
            PromoDealType.B2G2 -> 1.9
            PromoDealType.PRICE_POINT -> 1.3
            PromoDealType.MULTI_BUY -> if ((discountPct ?: 0.0) >= 30.0) 1.5 else 1.3
        }
}

object DeliReconciliationEngine {
    fun reconcile(request: DeliReconciliationRequest): DeliReconciliationResult {
        val groupedInventory = request.inventory.groupBy { it.sku.normalizedSku() }
        val verifyList = request.inventory
            .filter { it.needsVerification(request.confidenceThreshold) }
            .sortedWith(compareBy<DeliInventoryItem> { it.sku }.thenBy { it.name })

        val expiryRadar = buildExpiryRadar(request.inventory, request.today)
        val coverageEnd = request.nextDeliveryDate.plusDays(request.coverageWindowDays.toLong())

        val orderSheet = request.orderLines
            .sortedBy { it.orderIndex }
            .map { line ->
                val sku = line.sku.normalizedSku()
                val inventory = groupedInventory[sku].orEmpty()
                val confidence = inventory.minOfOrNull { it.confidence } ?: 1.0
                val lowConfidence = inventory.any { it.needsVerification(request.confidenceThreshold) }
                val expiringCases = inventory.sumOf { item ->
                    if (isExcludedFromCoverage(item, request.today, request.nextDeliveryDate)) item.casesOnHand else 0.0
                }
                val usableOnHand = inventory.sumOf { item ->
                    if (isExcludedFromCoverage(item, request.today, request.nextDeliveryDate)) 0.0 else item.casesOnHand
                }
                val promo = request.promos
                    .filter { it.sku.normalizedSku() == sku && it.affectsCoverage(request.nextDeliveryDate, coverageEnd) }
                    .maxByOrNull { it.multiplier() }
                val multiplier = promo?.multiplier() ?: 1.0
                val adjustedForecast = line.forecastDemandCases * multiplier
                val rawRecommended = max(0.0, adjustedForecast - usableOnHand + line.safetyStockCases)
                val recommended = ceil(rawRecommended).coerceAtLeast(0.0)

                when {
                    lowConfidence -> recommendation(
                        line = line,
                        recommended = line.suggestedCases,
                        action = DeliOrderAction.VERIFY,
                        reason = "Verify count before ordering; at least one matching label is below confidence threshold.",
                        usableOnHand = usableOnHand,
                        expiringCases = expiringCases,
                        multiplier = multiplier,
                        confidence = confidence
                    )
                    usableOnHand >= adjustedForecast * 2.0 && adjustedForecast > 0.0 -> recommendation(
                        line = line,
                        recommended = 0.0,
                        action = DeliOrderAction.SKIP,
                        reason = "Overstocked, skip. Usable on hand is ${usableOnHand.clean()} cases against ${adjustedForecast.clean()} forecast cases.",
                        usableOnHand = usableOnHand,
                        expiringCases = expiringCases,
                        multiplier = multiplier,
                        confidence = confidence
                    )
                    recommended <= 0.0 -> recommendation(
                        line = line,
                        recommended = 0.0,
                        action = DeliOrderAction.SKIP,
                        reason = buildReason("Skip", usableOnHand, expiringCases, adjustedForecast, line.safetyStockCases, promo),
                        usableOnHand = usableOnHand,
                        expiringCases = expiringCases,
                        multiplier = multiplier,
                        confidence = confidence
                    )
                    recommended < line.suggestedCases -> recommendation(
                        line = line,
                        recommended = recommended,
                        action = DeliOrderAction.TRIM,
                        reason = buildReason("Trim ${line.suggestedCases.clean()} to ${recommended.clean()}", usableOnHand, expiringCases, adjustedForecast, line.safetyStockCases, promo),
                        usableOnHand = usableOnHand,
                        expiringCases = expiringCases,
                        multiplier = multiplier,
                        confidence = confidence
                    )
                    else -> recommendation(
                        line = line,
                        recommended = recommended,
                        action = DeliOrderAction.ORDER,
                        reason = buildReason("Order ${recommended.clean()}", usableOnHand, expiringCases, adjustedForecast, line.safetyStockCases, promo),
                        usableOnHand = usableOnHand,
                        expiringCases = expiringCases,
                        multiplier = multiplier,
                        confidence = confidence
                    )
                }
            }

        return DeliReconciliationResult(orderSheet, expiryRadar, verifyList)
    }

    private fun recommendation(
        line: SupplierOrderLine,
        recommended: Double,
        action: DeliOrderAction,
        reason: String,
        usableOnHand: Double,
        expiringCases: Double,
        multiplier: Double,
        confidence: Double
    ) = DeliOrderRecommendation(
        sku = line.sku,
        itemName = line.name,
        systemSuggestedCases = line.suggestedCases,
        radarRecommendedCases = recommended,
        deltaCases = recommended - line.suggestedCases,
        action = action,
        reason = reason,
        usableOnHandCases = usableOnHand,
        excludedExpiringCases = expiringCases,
        demandMultiplier = multiplier,
        confidence = confidence,
        orderIndex = line.orderIndex
    )

    private fun buildReason(
        prefix: String,
        usableOnHand: Double,
        expiringCases: Double,
        adjustedForecast: Double,
        safetyStock: Double,
        promo: PromoItem?
    ): String {
        val promoText = promo?.let { " Promo ${it.dealType.name} applies ${it.multiplier().clean()}x demand." } ?: ""
        val expiryText = if (expiringCases > 0.0) " Excluded ${expiringCases.clean()} expiring case(s) from coverage." else ""
        return "$prefix: ${usableOnHand.clean()} usable cases, ${adjustedForecast.clean()} forecast cases, ${safetyStock.clean()} safety stock.$promoText$expiryText"
    }

    private fun isExcludedFromCoverage(item: DeliInventoryItem, today: LocalDate, nextDeliveryDate: LocalDate): Boolean {
        val useBy = item.useByDate ?: return false
        val expiresWithin48Hours = !useBy.isAfter(today.plusDays(2))
        return useBy.isBefore(nextDeliveryDate) || expiresWithin48Hours
    }

    private fun buildExpiryRadar(inventory: List<DeliInventoryItem>, today: LocalDate): List<ExpiryRadarItem> =
        inventory
            .map { item ->
                val days = item.useByDate?.let { ChronoUnit.DAYS.between(today, it).toInt() }
                ExpiryRadarItem(
                    sku = item.sku,
                    itemName = item.name,
                    category = item.category,
                    cases = item.casesOnHand,
                    pounds = item.caseWeightLbs?.let { it * item.casesOnHand },
                    useByDate = item.useByDate,
                    daysUntilExpiry = days,
                    bucket = bucket(days),
                    location = item.location,
                    productionHint = productionHint(item, days)
                )
            }
            .sortedWith(compareBy<ExpiryRadarItem> { it.daysUntilExpiry ?: Int.MAX_VALUE }.thenBy { it.itemName })

    private fun bucket(days: Int?): ExpiryBucket =
        when {
            days == null -> ExpiryBucket.UNKNOWN
            days <= 2 -> ExpiryBucket.DAYS_0_TO_2
            days <= 5 -> ExpiryBucket.DAYS_3_TO_5
            days <= 10 -> ExpiryBucket.DAYS_6_TO_10
            else -> ExpiryBucket.LATER
        }

    private fun productionHint(item: DeliInventoryItem, days: Int?): String? {
        if (days == null || days > 5) return null
        if (item.category !in setOf(DeliCategory.RAW_CHICKEN, DeliCategory.WINGS_TENDERS)) return null
        val pounds = item.caseWeightLbs?.let { item.casesOnHand * it }
        val amount = pounds?.let { "${it.clean()} lb" } ?: "${item.casesOnHand.clean()} cases"
        val whenText = if (days <= 0) "today" else "in $days day(s)"
        return "$amount ${item.name} expires $whenText. Prioritize hot case or production before ordering more."
    }
}

private fun PromoItem.affectsCoverage(nextDeliveryDate: LocalDate, coverageEnd: LocalDate): Boolean {
    val overlapsCoverage = !adEndDate.isBefore(nextDeliveryDate) && !adStartDate.isAfter(coverageEnd)
    val startsAfterDelivery = !nextDeliveryDate.isAfter(adStartDate)
    val activeAtDelivery = !nextDeliveryDate.isBefore(adStartDate) && !nextDeliveryDate.isAfter(adEndDate)
    return overlapsCoverage && (startsAfterDelivery || activeAtDelivery)
}

private fun PromoItem.multiplier(): Double =
    expectedDemandMultiplier ?: DeliPromoDefaults.multiplierFor(dealType, discountPct)

private fun String.normalizedSku(): String = trim().uppercase()

private fun DeliInventoryItem.needsVerification(confidenceThreshold: Double): Boolean =
    !verified || confidence < confidenceThreshold || sku.startsWith("UNKNOWN-", ignoreCase = true)

