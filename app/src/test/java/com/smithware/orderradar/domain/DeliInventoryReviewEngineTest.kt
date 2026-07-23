package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliInventoryReviewEngineTest {
    private val today = LocalDate.of(2026, 7, 23)

    @Test
    fun partialCaseAdjustmentUsesHalfCaseMath() {
        val adjusted = DeliInventoryReviewEngine.adjustCases(
            item(cases = 1.0, verified = false, confidence = 0.52),
            delta = -0.5
        )

        assertEquals(0.5, adjusted.casesOnHand, 0.001)
        assertTrue(adjusted.verified)
        assertEquals(0.95, adjusted.confidence, 0.001)

        val clamped = DeliInventoryReviewEngine.adjustCases(adjusted, delta = -2.0)
        assertEquals(0.0, clamped.casesOnHand, 0.001)
    }

    @Test
    fun editedItemMarksVerifiedAndLeavesVerifyQueue() {
        val edited = DeliInventoryReviewEngine.applyEdit(
            item(sku = "UNKNOWN-SLICER-1", name = "Trky brst", cases = 1.0, verified = false, confidence = 0.55),
            DeliInventoryItemEdit(
                sku = "772018",
                name = "Turkey breast",
                brandVendor = "Publix",
                verified = true
            )
        )
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(edited),
                orderLines = listOf(line("772018", "Turkey breast", suggested = 2.0, forecast = 3.0))
            )
        )

        assertTrue(edited.verified)
        assertEquals("Publix", edited.brandVendor)
        assertTrue(result.verifyList.isEmpty())
        assertFalse(result.orderSheet.single().action == DeliOrderAction.VERIFY)
    }

    @Test
    fun manualUnverifyReturnsItemToVerifyQueue() {
        val unverified = DeliInventoryReviewEngine.applyEdit(
            item(cases = 2.0, verified = true, confidence = 0.97),
            DeliInventoryItemEdit(verified = false)
        )

        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(unverified),
                orderLines = listOf(line("100", "Chicken salad", suggested = 2.0, forecast = 3.0))
            )
        )

        assertFalse(unverified.verified)
        assertEquals(1, result.verifyList.size)
        assertEquals(DeliOrderAction.VERIFY, result.orderSheet.single().action)
    }

    @Test
    fun duplicateReviewMergeAndEditedWeightFeedReconciliation() {
        val merged = DeliInventoryReviewEngine.mergeGroup(
            listOf(
                item(sku = "100", name = "Chicken salad", cases = 1.0, weight = 10.0, useBy = today.plusDays(9)),
                item(sku = "100", name = "Chicken salad", cases = 0.5, weight = 10.0, useBy = today.plusDays(9))
            )
        )
        val edited = DeliInventoryReviewEngine.applyEdit(
            merged,
            DeliInventoryItemEdit(
                casesOnHand = 2.5,
                caseWeightLbs = 12.0,
                useByDate = today.plusDays(10),
                verified = true
            )
        )

        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(edited),
                orderLines = listOf(line("100", "Chicken salad", suggested = 4.0, forecast = 3.5, safety = 1.0))
            )
        )

        val recommendation = result.orderSheet.single()
        assertEquals(2.0, recommendation.radarRecommendedCases, 0.001)
        assertEquals(30.0, result.expiryRadar.single().pounds ?: 0.0, 0.001)
    }

    private fun baseRequest(
        inventory: List<DeliInventoryItem>,
        orderLines: List<SupplierOrderLine>
    ) = DeliReconciliationRequest(
        inventory = inventory,
        promos = emptyList(),
        orderLines = orderLines,
        today = today,
        nextDeliveryDate = today.plusDays(3),
        coverageWindowDays = 7
    )

    private fun item(
        sku: String = "100",
        name: String = "Chicken salad",
        category: DeliCategory = DeliCategory.SALADS,
        cases: Double,
        weight: Double? = 10.0,
        useBy: LocalDate? = today.plusDays(7),
        confidence: Double = 0.95,
        verified: Boolean = true
    ) = DeliInventoryItem(
        sku = sku,
        name = name,
        category = category,
        casesOnHand = cases,
        caseWeightLbs = weight,
        useByDate = useBy,
        location = InventoryLocation.COOLER,
        confidence = confidence,
        photoRefs = listOf("photo-1"),
        verified = verified
    )

    private fun line(
        sku: String,
        name: String,
        suggested: Double,
        forecast: Double,
        safety: Double = 1.0
    ) = SupplierOrderLine(
        sku = sku,
        name = name,
        suggestedCases = suggested,
        forecastDemandCases = forecast,
        safetyStockCases = safety,
        orderIndex = 0
    )
}
