package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliOrderScreenReviewEngineTest {
    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun correctedSuggestedCasesFlowIntoReconciliation() {
        val parsedLine = line("441229", "Grab n go pudding", suggested = 6.0, forecast = 6.0, safety = 1.0)
        val correctedLine = DeliOrderScreenReviewEngine.applyEdit(
            parsedLine,
            SupplierOrderLineEdit(suggestedCases = 2.0)
        )

        val result = DeliReconciliationEngine.reconcile(
            request(
                inventory = listOf(item("441229", "Grab n go pudding", DeliCategory.PUDDING, cases = 3.0)),
                orderLines = listOf(correctedLine)
            )
        )

        val recommendation = result.orderSheet.single()
        assertEquals(2.0, recommendation.systemSuggestedCases, 0.001)
        assertEquals(2.0, correctedLine.forecastDemandCases, 0.001)
        assertEquals(DeliOrderAction.SKIP, recommendation.action)
    }

    @Test
    fun everyReviewedSupplierRowReceivesAReconciliationAction() {
        val lines = listOf(
            line("100", "Order needed soup", suggested = 2.0, forecast = 4.0, safety = 1.0, index = 0),
            line("200", "Trim macaroni salad", suggested = 6.0, forecast = 8.0, safety = 1.0, index = 1),
            line("300", "Skip chicken salad", suggested = 4.0, forecast = 3.0, safety = 1.0, index = 2),
            line("400", "Verify turkey breast", suggested = 2.0, forecast = 3.0, safety = 1.0, index = 3)
        )

        val result = DeliReconciliationEngine.reconcile(
            request(
                inventory = listOf(
                    item("200", "Trim macaroni salad", DeliCategory.SALADS, cases = 5.0),
                    item("300", "Skip chicken salad", DeliCategory.SALADS, cases = 8.0),
                    item("400", "Verify turkey breast", DeliCategory.DELI_MEAT, cases = 1.0, confidence = 0.52, verified = false)
                ),
                orderLines = lines
            )
        )

        assertTrue(DeliOrderScreenReviewEngine.hasRecommendationForEveryReviewedRow(lines, result))
        assertEquals(listOf("100", "200", "300", "400"), result.orderSheet.map { it.sku })
        assertEquals(
            listOf(DeliOrderAction.ORDER, DeliOrderAction.TRIM, DeliOrderAction.SKIP, DeliOrderAction.VERIFY),
            result.orderSheet.map { it.action }
        )
    }

    private fun request(
        inventory: List<DeliInventoryItem>,
        orderLines: List<SupplierOrderLine>,
        promos: List<PromoItem> = emptyList()
    ) = DeliReconciliationRequest(
        inventory = inventory,
        promos = promos,
        orderLines = orderLines,
        today = today,
        nextDeliveryDate = today.plusDays(3),
        coverageWindowDays = 7
    )

    private fun item(
        sku: String,
        name: String,
        category: DeliCategory = DeliCategory.OTHER,
        cases: Double,
        confidence: Double = 0.95,
        verified: Boolean = true
    ) = DeliInventoryItem(
        sku = sku,
        name = name,
        category = category,
        casesOnHand = cases,
        caseWeightLbs = 10.0,
        useByDate = today.plusDays(8),
        location = InventoryLocation.COOLER,
        confidence = confidence,
        photoRefs = listOf("verified-inventory"),
        verified = verified
    )

    private fun line(
        sku: String,
        name: String,
        suggested: Double,
        forecast: Double,
        safety: Double,
        index: Int = 0
    ) = SupplierOrderLine(
        sku = sku,
        name = name,
        packSize = "case",
        suggestedCases = suggested,
        forecastDemandCases = forecast,
        safetyStockCases = safety,
        orderIndex = index
    )
}
