package com.smithware.orderradar.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProductCategory { BOX_MEAT, GGM, DISPLAY, DELI_MEAT, PREPARED_FOOD, VENDOR, WAREHOUSE, GROCERY, OTHER }
enum class CountSource { MANUAL, PHOTO_ASSIST, OCR_LABEL, DELIVERY, ADJUSTMENT }
enum class MovementType { USED, SOLD, OPENED, PREPPED, WASTED, DAMAGED, TRANSFERRED, ADJUSTED, RECEIVED }
enum class OrderDraftStatus { DRAFT, PLACED, RECEIVED, CANCELLED }
enum class DeliveryStatus { MATCHES_EXPECTED, SHORT, OVER, DAMAGED, UNEXPECTED, NOT_RECEIVED, REVIEW }
enum class VarianceType { RECEIVED_MORE_THAN_ORDERED, RECEIVED_LESS_THAN_ORDERED, ORDERED_NOT_RECEIVED, UNEXPECTED_PRODUCT, DUPLICATE_ORDER_SUSPECTED, WAREHOUSE_ALLOCATION, DISPLAY_ALLOCATION, DAMAGED_PRODUCT, MANUAL_OVERRIDE, UNKNOWN }
enum class DisplayStatus { PLANNED, ACTIVE, REORDER_NEEDED, WATCH, OVERSTOCK_RISK, ENDED }
enum class ForecastStatus { GOOD, WATCH, ORDER_NEEDED, CRITICAL, OVERSTOCK_RISK, UNKNOWN }

@Entity
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: ProductCategory,
    val itemNumber: String? = null,
    val upc: String? = null,
    val vendor: String? = null,
    val department: String = "Deli",
    val storageLocation: String? = null,
    val defaultUnit: String,
    val caseSize: String? = null,
    val boxWeight: Double? = null,
    val safetyStock: Double,
    val reorderPoint: Double,
    val active: Boolean = true,
    val productPhotoUri: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class TruckSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val deliveryDays: String,
    val orderCutoffDays: String,
    val leadTimeDays: Int,
    val notes: String? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class ProductTruckLink(@PrimaryKey(autoGenerate = true) val id: Long = 0, val productId: Long, val truckScheduleId: Long)

@Entity
data class InventoryCount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val quantity: Double,
    val unit: String,
    val countDate: Long = System.currentTimeMillis(),
    val source: CountSource = CountSource.MANUAL,
    val photoUri: String? = null,
    val verified: Boolean = true,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class MovementEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val quantity: Double,
    val unit: String,
    val movementType: MovementType,
    val movementDate: Long = System.currentTimeMillis(),
    val linkedRecipeId: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class OrderDraft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckScheduleId: Long,
    val title: String,
    val orderDate: Long = System.currentTimeMillis(),
    val expectedDeliveryDate: Long,
    val status: OrderDraftStatus = OrderDraftStatus.DRAFT,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class OrderLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderDraftId: Long,
    val productId: Long,
    val recommendedQuantity: Double,
    val userQuantity: Double,
    val unit: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class DeliveryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val truckScheduleId: Long,
    val orderDraftId: Long?,
    val deliveryDate: Long = System.currentTimeMillis(),
    val notes: String? = null,
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class DeliveryLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deliveryRecordId: Long,
    val productId: Long,
    val orderedQuantity: Double,
    val expectedQuantity: Double,
    val actualQuantity: Double,
    val unit: String,
    val variance: Double,
    val status: DeliveryStatus,
    val notes: String? = null
)

@Entity
data class VarianceLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val deliveryLineId: Long? = null,
    val varianceType: VarianceType,
    val orderedQuantity: Double,
    val receivedQuantity: Double,
    val difference: Double,
    val reason: String,
    val resolved: Boolean = false,
    val notes: String? = null,
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class DisplayPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val productId: Long,
    val displayLocation: String,
    val adWeekStart: Long,
    val adWeekEnd: Long,
    val startingQuantity: Double,
    val currentQuantity: Double,
    val forecastedWeeklyUsage: Double,
    val reorderPoint: Double,
    val plannedOrderQuantity: Double,
    val targetDisplayLevel: Double,
    val deliveryDay: String,
    val status: DisplayStatus,
    val photoUri: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val outputQuantity: Double,
    val outputUnit: String,
    val notes: String? = null,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class RecipeIngredient(@PrimaryKey(autoGenerate = true) val id: Long = 0, val recipeId: Long, val productId: Long, val quantityUsed: Double, val unit: String)

@Entity
data class ProductionLog(@PrimaryKey(autoGenerate = true) val id: Long = 0, val recipeId: Long, val batchCount: Double, val productionDate: Long = System.currentTimeMillis(), val notes: String? = null, val createdAt: Long = System.currentTimeMillis())

@Entity
data class PhotoAttachment(@PrimaryKey(autoGenerate = true) val id: Long = 0, val relatedType: String, val relatedId: Long, val photoUri: String, val caption: String? = null, val createdAt: Long = System.currentTimeMillis())

// One row per confirmed AI shelf-count row: what the AI guessed vs. what the user actually
// confirmed. This is the training signal for VisionLearningEngine -- every save teaches it,
// whether the user corrected the count or left it exactly as guessed.
@Entity
data class VisionCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val productName: String,
    val aiEstimatedQuantity: Double,
    val confirmedQuantity: Double,
    val aiConfidencePercent: Int,
    val createdAt: Long = System.currentTimeMillis()
)

data class ProductSnapshot(
    val product: Product,
    val latestCount: InventoryCount?,
    val movements: List<MovementEntry>,
    val truck: TruckSchedule?
)

data class ForecastResult(
    val productId: Long,
    val currentOnHand: Double,
    val averageDailyUsage: Double,
    val daysUntilNextTruck: Int,
    val neededUntilNextTruck: Double,
    val safetyStock: Double,
    val recommendedOrderQuantity: Double,
    val daysOfSupply: Double,
    val status: ForecastStatus,
    val reason: String
)
