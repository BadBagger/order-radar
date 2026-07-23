package com.smithware.orderradar.domain

import kotlin.math.max
import kotlin.math.round

object DeliOrderScreenReviewEngine {
    fun applyEdit(line: SupplierOrderLine, edit: SupplierOrderLineEdit): SupplierOrderLine {
        val correctedSuggested = edit.suggestedCases?.roundToHalf()?.coerceAtLeast(0.0)
        return line.copy(
            sku = edit.sku?.trim()?.ifBlank { line.sku } ?: line.sku,
            name = edit.name?.trim()?.ifBlank { line.name } ?: line.name,
            packSize = edit.packSize?.trim()?.ifBlank { null } ?: line.packSize,
            suggestedCases = correctedSuggested ?: line.suggestedCases,
            forecastDemandCases = edit.forecastDemandCases?.roundToHalf()?.coerceAtLeast(0.0)
                ?: correctedSuggested
                ?: line.forecastDemandCases,
            safetyStockCases = edit.safetyStockCases?.roundToHalf()?.coerceAtLeast(0.0) ?: line.safetyStockCases
        )
    }

    fun moveLine(lines: List<SupplierOrderLine>, fromIndex: Int, toIndex: Int): List<SupplierOrderLine> {
        if (fromIndex !in lines.indices || toIndex !in lines.indices || fromIndex == toIndex) return lines
        val mutable = lines.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable.withIndex().map { (index, line) -> line.copy(orderIndex = index) }
    }

    fun hasRecommendationForEveryReviewedRow(
        reviewedLines: List<SupplierOrderLine>,
        result: DeliReconciliationResult
    ): Boolean {
        val recommendedIndexes = result.orderSheet.map { it.orderIndex }.toSet()
        return reviewedLines.all { it.orderIndex in recommendedIndexes } &&
            result.orderSheet.all { it.action in DeliOrderAction.entries }
    }
}

private fun Double.roundToHalf(): Double = round(max(0.0, this) * 2.0) / 2.0
