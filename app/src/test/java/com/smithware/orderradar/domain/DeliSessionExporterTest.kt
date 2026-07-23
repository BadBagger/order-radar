package com.smithware.orderradar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DeliSessionExporterTest {
    private val today = LocalDate.of(2026, 7, 23)
    private val delivery = LocalDate.of(2026, 7, 26)

    @Test
    fun expiryRadarUsesUseFirstBucketSorting() {
        val result = DeliReconciliationEngine.reconcile(
            request(
                inventory = listOf(
                    item("600", "Raw chicken late", DeliCategory.RAW_CHICKEN, 1.0, 40.0, today.plusDays(10), InventoryLocation.FREEZER),
                    item("200", "Wings urgent", DeliCategory.WINGS_TENDERS, 2.0, 40.0, today.plusDays(1), InventoryLocation.COOLER),
                    item("400", "Pudding mid", DeliCategory.PUDDING, 3.0, 12.0, today.plusDays(4), InventoryLocation.SALES_FLOOR),
                    item("100", "Mac salad later", DeliCategory.SALADS, 4.0, 10.0, today.plusDays(12), InventoryLocation.COOLER)
                )
            )
        )

        assertEquals(
            listOf(
                ExpiryBucket.DAYS_0_TO_2,
                ExpiryBucket.DAYS_3_TO_5,
                ExpiryBucket.DAYS_6_TO_10,
                ExpiryBucket.LATER
            ),
            result.expiryRadar.map { it.bucket }
        )
        assertEquals(listOf("Wings urgent", "Pudding mid", "Raw chicken late", "Mac salad later"), result.expiryRadar.map { it.itemName })
    }

    @Test
    fun productionHintsCoverUrgentAndUpcomingWingsAndRawProteins() {
        val result = DeliReconciliationEngine.reconcile(
            request(
                inventory = listOf(
                    item("200", "Jumbo wings", DeliCategory.WINGS_TENDERS, 2.0, 40.0, today.plusDays(1), InventoryLocation.COOLER),
                    item("300", "Raw chicken breast", DeliCategory.RAW_CHICKEN, 1.5, 40.0, today.plusDays(7), InventoryLocation.FREEZER),
                    item("400", "Grab n go pudding", DeliCategory.PUDDING, 3.0, 12.0, today.plusDays(4), InventoryLocation.SALES_FLOOR)
                )
            )
        )

        val wings = result.expiryRadar.single { it.sku == "200" }
        val rawChicken = result.expiryRadar.single { it.sku == "300" }
        val pudding = result.expiryRadar.single { it.sku == "400" }

        assertTrue(wings.productionHint.orEmpty().contains("hot case"))
        assertTrue(rawChicken.productionHint.orEmpty().contains("production opportunities"))
        assertEquals(null, pudding.productionHint)
    }

    @Test
    fun expiryAndOrderSheetCsvContainSessionDerivedFields() {
        val result = DeliReconciliationEngine.reconcile(
            request(
                inventory = listOf(item("200", "Jumbo wings", DeliCategory.WINGS_TENDERS, 2.0, 40.0, today.plusDays(1), InventoryLocation.COOLER)),
                orderLines = listOf(SupplierOrderLine("200", "Jumbo wings", "40 lb", suggestedCases = 3.0, forecastDemandCases = 4.0, safetyStockCases = 1.0, orderIndex = 0))
            )
        )

        val expiryCsv = DeliSessionExporter.expiryCsv(result, today)
        val orderCsv = DeliSessionExporter.orderSheetCsv(result)
        val summary = DeliSessionExporter.shareSummary(result, today, delivery)

        assertTrue(expiryCsv.startsWith("bucket,sku,item,category,cases,pounds,location,use_by_date,relative_date,production_hint"))
        assertTrue(expiryCsv.contains("0-2 days,200,Jumbo wings,Wings Tenders,2,80,Cooler,2026-07-24,expires tomorrow"))
        assertTrue(orderCsv.startsWith("order_index,action,sku,item,system_suggested_cases,radar_recommended_cases"))
        assertTrue(orderCsv.contains("1,ORDER,200,Jumbo wings,3,5,2,0,2,1,95%"))
        assertTrue(summary.contains("Run date: 2026-07-23"))
        assertTrue(summary.contains("Next delivery: 2026-07-26"))
        assertTrue(summary.contains("Expiry Radar"))
    }

    private fun request(
        inventory: List<DeliInventoryItem>,
        orderLines: List<SupplierOrderLine> = emptyList()
    ) = DeliReconciliationRequest(
        inventory = inventory,
        promos = emptyList(),
        orderLines = orderLines,
        today = today,
        nextDeliveryDate = delivery,
        coverageWindowDays = 7
    )

    private fun item(
        sku: String,
        name: String,
        category: DeliCategory,
        cases: Double,
        weight: Double,
        useBy: LocalDate,
        location: InventoryLocation
    ) = DeliInventoryItem(
        sku = sku,
        name = name,
        category = category,
        casesOnHand = cases,
        caseWeightLbs = weight,
        useByDate = useBy,
        location = location,
        confidence = 0.95,
        verified = true
    )
}
