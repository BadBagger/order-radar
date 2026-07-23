package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliReconciliationEngineTest {
    private val today = LocalDate.of(2026, 7, 23)
    private val delivery = LocalDate.of(2026, 7, 26)

    @Test
    fun overstockedItemIsSkippedAndOrderScreenOrderIsPreserved() {
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(item("100", "Chicken salad", cases = 12.0, useBy = today.plusDays(8))),
                orderLines = listOf(
                    line("200", "Mac salad", suggested = 2.0, forecast = 4.0, index = 0),
                    line("100", "Chicken salad", suggested = 6.0, forecast = 5.0, index = 1)
                )
            )
        )

        assertEquals(listOf("200", "100"), result.orderSheet.map { it.sku })
        val chicken = result.orderSheet.single { it.sku == "100" }
        assertEquals(DeliOrderAction.SKIP, chicken.action)
        assertEquals(0.0, chicken.radarRecommendedCases, 0.001)
        assertTrue(chicken.reason.contains("Overstocked"))
    }

    @Test
    fun nearExpiryIsExcludedFromCoverageAndAppearsOnExpiryRadarWithProductionHint() {
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(item("300", "Wings", DeliCategory.WINGS_TENDERS, cases = 10.0, weight = 40.0, useBy = today.plusDays(1))),
                orderLines = listOf(line("300", "Wings", suggested = 3.0, forecast = 4.0, safety = 1.0))
            )
        )

        val wings = result.orderSheet.single()
        assertEquals(DeliOrderAction.ORDER, wings.action)
        assertEquals(5.0, wings.radarRecommendedCases, 0.001)
        assertEquals(10.0, wings.excludedExpiringCases, 0.001)

        val expiry = result.expiryRadar.single()
        assertEquals(ExpiryBucket.DAYS_0_TO_2, expiry.bucket)
        assertEquals(400.0, expiry.pounds ?: 0.0, 0.001)
        assertTrue(expiry.productionHint?.contains("hot case") == true)
    }

    @Test
    fun bogoPromoInCoverageWindowIncreasesRecommendedOrder() {
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(item("400", "Potato wedges", DeliCategory.OTHER, cases = 1.0, useBy = today.plusDays(9))),
                promos = listOf(
                    PromoItem(
                        sku = "400",
                        name = "Potato wedges",
                        dealType = PromoDealType.BOGO,
                        adStartDate = today.plusDays(4),
                        adEndDate = today.plusDays(10)
                    )
                ),
                orderLines = listOf(line("400", "Potato wedges", suggested = 2.0, forecast = 4.0, safety = 1.0))
            )
        )

        val wedges = result.orderSheet.single()
        assertEquals(DeliOrderAction.ORDER, wedges.action)
        assertEquals(2.25, wedges.demandMultiplier, 0.001)
        assertEquals(9.0, wedges.radarRecommendedCases, 0.001)
        assertTrue(wedges.reason.contains("BOGO"))
    }

    @Test
    fun lowConfidenceInventoryCreatesVerifyRecommendation() {
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(item("500", "Turkey breast", DeliCategory.DELI_MEAT, cases = 2.0, confidence = 0.54, verified = false)),
                orderLines = listOf(line("500", "Turkey breast", suggested = 2.0, forecast = 3.0))
            )
        )

        val turkey = result.orderSheet.single()
        assertEquals(DeliOrderAction.VERIFY, turkey.action)
        assertEquals(1, result.verifyList.size)
        assertTrue(turkey.reason.contains("Verify count"))
    }

    @Test
    fun trimsSystemSuggestionWhenUsableInventoryCoversMostDemand() {
        val result = DeliReconciliationEngine.reconcile(
            baseRequest(
                inventory = listOf(item("600", "Pudding", DeliCategory.PUDDING, cases = 5.0, useBy = today.plusDays(12))),
                orderLines = listOf(line("600", "Pudding", suggested = 6.0, forecast = 8.0, safety = 1.0))
            )
        )

        val pudding = result.orderSheet.single()
        assertEquals(DeliOrderAction.TRIM, pudding.action)
        assertEquals(4.0, pudding.radarRecommendedCases, 0.001)
        assertTrue(pudding.reason.contains("Trim 6 to 4"))
    }

    private fun baseRequest(
        inventory: List<DeliInventoryItem>,
        promos: List<PromoItem> = emptyList(),
        orderLines: List<SupplierOrderLine>
    ) = DeliReconciliationRequest(
        inventory = inventory,
        promos = promos,
        orderLines = orderLines,
        today = today,
        nextDeliveryDate = delivery,
        coverageWindowDays = 7
    )

    private fun item(
        sku: String,
        name: String,
        category: DeliCategory = DeliCategory.OTHER,
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
        safety: Double = 1.0,
        index: Int = 0
    ) = SupplierOrderLine(
        sku = sku,
        name = name,
        suggestedCases = suggested,
        forecastDemandCases = forecast,
        safetyStockCases = safety,
        orderIndex = index
    )
}

