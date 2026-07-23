package com.smithware.orderradar.domain

import java.time.LocalDate

enum class DeliCategory {
    RAW_CHICKEN,
    WINGS_TENDERS,
    SALADS,
    SOUPS,
    PUDDING,
    DELI_MEAT,
    CHEESE,
    DIPS,
    BREADS,
    OTHER
}

enum class InventoryLocation {
    COOLER,
    FREEZER,
    SLICER_BACKSTOCK,
    SALES_FLOOR
}

enum class PromoDealType {
    BOGO,
    B2G1,
    B2G2,
    PRICE_POINT,
    MULTI_BUY
}

enum class DeliOrderAction {
    ORDER,
    TRIM,
    SKIP,
    VERIFY
}

enum class ExpiryBucket {
    DAYS_0_TO_2,
    DAYS_3_TO_5,
    DAYS_6_TO_10,
    LATER,
    UNKNOWN
}

data class DeliInventoryItem(
    val sku: String,
    val name: String,
    val category: DeliCategory,
    val casesOnHand: Double,
    val caseWeightLbs: Double? = null,
    val useByDate: LocalDate? = null,
    val location: InventoryLocation,
    val confidence: Double,
    val photoRefs: List<String> = emptyList(),
    val verified: Boolean = false,
    val brandVendor: String? = null
)

data class PromoItem(
    val sku: String,
    val name: String,
    val retailPrice: Double? = null,
    val salePrice: Double? = null,
    val dealType: PromoDealType,
    val discountPct: Double? = null,
    val adStartDate: LocalDate,
    val adEndDate: LocalDate,
    val placement: String? = null,
    val expectedDemandMultiplier: Double? = null
)

data class SupplierOrderLine(
    val sku: String,
    val name: String,
    val packSize: String? = null,
    val suggestedCases: Double,
    val forecastDemandCases: Double,
    val safetyStockCases: Double,
    val orderIndex: Int
)

data class DeliReconciliationRequest(
    val inventory: List<DeliInventoryItem>,
    val promos: List<PromoItem>,
    val orderLines: List<SupplierOrderLine>,
    val today: LocalDate,
    val nextDeliveryDate: LocalDate,
    val coverageWindowDays: Int,
    val confidenceThreshold: Double = 0.80
)

data class DeliOrderRecommendation(
    val sku: String,
    val itemName: String,
    val systemSuggestedCases: Double,
    val radarRecommendedCases: Double,
    val deltaCases: Double,
    val action: DeliOrderAction,
    val reason: String,
    val usableOnHandCases: Double,
    val excludedExpiringCases: Double,
    val demandMultiplier: Double,
    val confidence: Double,
    val orderIndex: Int
)

data class ExpiryRadarItem(
    val sku: String,
    val itemName: String,
    val category: DeliCategory,
    val cases: Double,
    val pounds: Double?,
    val useByDate: LocalDate?,
    val daysUntilExpiry: Int?,
    val bucket: ExpiryBucket,
    val location: InventoryLocation,
    val productionHint: String? = null
)

data class DeliReconciliationResult(
    val orderSheet: List<DeliOrderRecommendation>,
    val expiryRadar: List<ExpiryRadarItem>,
    val verifyList: List<DeliInventoryItem>
)

