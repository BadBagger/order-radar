package com.smithware.orderradar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smithware.orderradar.domain.DeliCategory
import com.smithware.orderradar.domain.DeliOcrTextSourceType
import com.smithware.orderradar.domain.DeliScanSessionProgressState
import com.smithware.orderradar.domain.DeliTextSourceKind
import com.smithware.orderradar.domain.InventoryLocation
import com.smithware.orderradar.domain.PromoDealType

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
    // How to tell this product apart from a photo without reading its label: box/case size,
    // a color-coding system on the lid or wrap (e.g. "Red lid = Original, Yellow = Lemon
    // Pepper"), how it's normally stacked, etc. Fed into the AI shelf-count prompt as a
    // per-product hint.
    val visualIdentifiers: String? = null,
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

@Entity
data class DeliScanSessionRecord(
    @PrimaryKey val sessionId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val progressState: DeliScanSessionProgressState,
    val completedSources: Int,
    val totalSources: Int,
    val progressMessage: String,
    val progressUpdatedAtMillis: Long,
    val failedReason: String? = null,
    val duplicateSourceIds: String = "",
    val totalSourceCount: Int = 0,
    val duplicateSourceCount: Int = 0,
    val processedSourceCount: Int = 0,
    val inventoryItemCount: Int = 0,
    val promoItemCount: Int = 0,
    val orderLineCount: Int = 0,
    val verifyItemCount: Int = 0,
    val stickyNoteCount: Int = 0,
    val locationTags: String = ""
)

@Entity
data class DeliScanSourceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sourceId: String,
    val kind: DeliTextSourceKind,
    val text: String,
    val textSourceType: DeliOcrTextSourceType,
    val photoId: String? = null,
    val uri: String? = null,
    val capturedAtMillis: Long? = null,
    val receivedAtMillis: Long,
    val location: InventoryLocation? = null,
    val locationTags: String = "",
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val sizeBytes: Long? = null
)

@Entity(indices = [Index("weekStartEpochDay")])
data class DeliInventorySnapshotRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val capturedAtMillis: Long,
    val weekStartEpochDay: Long,
    val status: DeliScanSessionProgressState,
    val sourceRefs: String = "",
    val notes: String? = null
)

@Entity(indices = [Index("snapshotId")])
data class DeliInventorySnapshotItemRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val snapshotId: Long,
    val sessionId: String,
    val sku: String,
    val name: String,
    val category: DeliCategory,
    val casesOnHand: Double,
    val caseWeightLbs: Double? = null,
    val useByEpochDay: Long? = null,
    val location: InventoryLocation,
    val confidence: Double,
    val verified: Boolean,
    val brandVendor: String? = null,
    val sourceRefs: String = "",
    val createdAtMillis: Long
)

@Entity
data class DeliPromoItemRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sku: String,
    val name: String,
    val retailPrice: Double? = null,
    val salePrice: Double? = null,
    val dealType: PromoDealType,
    val discountPct: Double? = null,
    val adStartEpochDay: Long,
    val adEndEpochDay: Long,
    val placement: String? = null,
    val expectedDemandMultiplier: Double? = null,
    val sourceRefs: String = "",
    val createdAtMillis: Long
)

@Entity
data class DeliSupplierOrderLineRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val sku: String,
    val name: String,
    val packSize: String? = null,
    val suggestedCases: Double,
    val forecastDemandCases: Double,
    val safetyStockCases: Double,
    val orderIndex: Int,
    val sourceRefs: String = "",
    val createdAtMillis: Long
)

@Entity
data class DeliVerifyLabelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val itemName: String,
    val sku: String? = null,
    val packSize: String? = null,
    val caseWeightLbs: Double? = null,
    val packDateEpochDay: Long? = null,
    val useByEpochDay: Long? = null,
    val brandVendor: String? = null,
    val confidence: Double,
    val sourcePhotoId: String,
    val createdAtMillis: Long
)

@Entity
data class DeliStickyNoteRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val noteText: String,
    val sourceRefs: String = "",
    val createdAtMillis: Long
)

data class DeliScanSessionEntityBundle(
    val session: DeliScanSessionRecord,
    val sources: List<DeliScanSourceRecord>,
    val snapshot: DeliInventorySnapshotRecord?,
    val inventoryItems: List<DeliInventorySnapshotItemRecord>,
    val promoItems: List<DeliPromoItemRecord>,
    val orderLines: List<DeliSupplierOrderLineRecord>,
    val verifyLabels: List<DeliVerifyLabelRecord>,
    val stickyNotes: List<DeliStickyNoteRecord>
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
