package com.smithware.orderradar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderRadarDao {
    @Query("SELECT * FROM Product ORDER BY active DESC, name")
    fun products(): Flow<List<Product>>

    @Query("SELECT * FROM TruckSchedule WHERE active = 1 ORDER BY name")
    fun trucks(): Flow<List<TruckSchedule>>

    @Query("SELECT * FROM InventoryCount ORDER BY countDate DESC")
    fun counts(): Flow<List<InventoryCount>>

    @Query("SELECT * FROM MovementEntry ORDER BY movementDate DESC")
    fun movements(): Flow<List<MovementEntry>>

    @Query("SELECT * FROM OrderDraft ORDER BY orderDate DESC")
    fun orders(): Flow<List<OrderDraft>>

    @Query("SELECT * FROM OrderLine")
    fun orderLines(): Flow<List<OrderLine>>

    @Query("SELECT * FROM DeliveryRecord ORDER BY deliveryDate DESC")
    fun deliveries(): Flow<List<DeliveryRecord>>

    @Query("SELECT * FROM DeliveryLine")
    fun deliveryLines(): Flow<List<DeliveryLine>>

    @Query("SELECT * FROM VarianceLog ORDER BY createdAt DESC")
    fun variances(): Flow<List<VarianceLog>>

    @Query("SELECT * FROM DisplayPlan ORDER BY adWeekEnd")
    fun displays(): Flow<List<DisplayPlan>>

    @Query("SELECT * FROM Recipe WHERE active = 1 ORDER BY name")
    fun recipes(): Flow<List<Recipe>>

    @Query("SELECT * FROM RecipeIngredient")
    fun recipeIngredients(): Flow<List<RecipeIngredient>>

    @Query("SELECT * FROM ProductTruckLink")
    fun productTruckLinks(): Flow<List<ProductTruckLink>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: Product): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTruck(truck: TruckSchedule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProductTruckLink(link: ProductTruckLink): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCount(count: InventoryCount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovement(entry: MovementEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderDraft): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderLine(line: OrderLine): Long

    @Query("SELECT * FROM OrderDraft WHERE truckScheduleId = :truckId AND status = :status ORDER BY orderDate DESC LIMIT 1")
    suspend fun latestDraftForTruck(truckId: Long, status: OrderDraftStatus = OrderDraftStatus.DRAFT): OrderDraft?

    @Query("SELECT * FROM OrderLine WHERE orderDraftId = :orderId AND productId = :productId LIMIT 1")
    suspend fun orderLineForProduct(orderId: Long, productId: Long): OrderLine?

    @Query("SELECT * FROM OrderLine WHERE orderDraftId = :orderId")
    suspend fun orderLinesForDraft(orderId: Long): List<OrderLine>

    @Query("UPDATE OrderLine SET userQuantity = :quantity, updatedAt = :updatedAt WHERE id = :lineId")
    suspend fun updateOrderLineQuantity(lineId: Long, quantity: Double, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM OrderLine WHERE id = :lineId")
    suspend fun deleteOrderLine(lineId: Long)

    @Query("UPDATE OrderDraft SET status = :status, updatedAt = :updatedAt WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Long, status: OrderDraftStatus, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDelivery(record: DeliveryRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliveryLine(line: DeliveryLine): Long

    @Query("SELECT * FROM DeliveryRecord WHERE orderDraftId = :orderId LIMIT 1")
    suspend fun deliveryForOrder(orderId: Long): DeliveryRecord?

    @Query("UPDATE DeliveryLine SET actualQuantity = :actualQuantity, variance = :variance, status = :status, notes = :notes WHERE id = :lineId")
    suspend fun updateDeliveryLineActual(lineId: Long, actualQuantity: Double, variance: Double, status: DeliveryStatus, notes: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariance(log: VarianceLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDisplay(display: DisplayPlan): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: Recipe): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeIngredient(ingredient: RecipeIngredient): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduction(log: ProductionLog): Long

    @Query("SELECT COUNT(*) FROM Product")
    suspend fun productCount(): Int

    @Query("DELETE FROM Product")
    suspend fun clearProducts()

    @Query("DELETE FROM Product WHERE id = :productId")
    suspend fun deleteProduct(productId: Long)

    @Query("SELECT * FROM VisionCorrection ORDER BY createdAt DESC")
    fun visionCorrections(): Flow<List<VisionCorrection>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisionCorrection(correction: VisionCorrection): Long

    @Transaction
    suspend fun replaceDeliScanSession(bundle: DeliScanSessionEntityBundle): Long {
        deleteDeliSessionChildren(bundle.session.sessionId)
        insertDeliScanSession(bundle.session)
        insertDeliScanSources(bundle.sources)
        val snapshotId = bundle.snapshot?.let { insertDeliInventorySnapshot(it) } ?: 0L
        val inventoryItems = if (snapshotId > 0L) {
            bundle.inventoryItems.map { it.copy(snapshotId = snapshotId) }
        } else {
            bundle.inventoryItems
        }
        insertDeliInventoryItems(inventoryItems)
        insertDeliPromoItems(bundle.promoItems)
        insertDeliSupplierOrderLines(bundle.orderLines)
        insertDeliVerifyLabels(bundle.verifyLabels)
        insertDeliStickyNotes(bundle.stickyNotes)
        return snapshotId
    }

    @Transaction
    suspend fun loadDeliScanSessionBundle(sessionId: String): DeliScanSessionEntityBundle? {
        val session = deliScanSession(sessionId) ?: return null
        val snapshot = latestDeliInventorySnapshotForSession(sessionId)
        return DeliScanSessionEntityBundle(
            session = session,
            sources = deliScanSources(sessionId),
            snapshot = snapshot,
            inventoryItems = deliInventoryItems(sessionId),
            promoItems = deliPromoItems(sessionId),
            orderLines = deliSupplierOrderLines(sessionId),
            verifyLabels = deliVerifyLabels(sessionId),
            stickyNotes = deliStickyNotes(sessionId)
        )
    }

    @Query("SELECT * FROM DeliScanSessionRecord ORDER BY updatedAtMillis DESC")
    fun deliScanSessions(): Flow<List<DeliScanSessionRecord>>

    @Query("SELECT * FROM DeliScanSessionRecord WHERE sessionId = :sessionId LIMIT 1")
    suspend fun deliScanSession(sessionId: String): DeliScanSessionRecord?

    @Query("DELETE FROM DeliScanSourceRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliScanSources(sessionId: String)

    @Query("DELETE FROM DeliInventorySnapshotRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliInventorySnapshots(sessionId: String)

    @Query("DELETE FROM DeliInventorySnapshotItemRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliInventoryItems(sessionId: String)

    @Query("DELETE FROM DeliPromoItemRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliPromoItems(sessionId: String)

    @Query("DELETE FROM DeliSupplierOrderLineRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliSupplierOrderLines(sessionId: String)

    @Query("DELETE FROM DeliVerifyLabelRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliVerifyLabels(sessionId: String)

    @Query("DELETE FROM DeliStickyNoteRecord WHERE sessionId = :sessionId")
    suspend fun deleteDeliStickyNotes(sessionId: String)

    suspend fun deleteDeliSessionChildren(sessionId: String) {
        deleteDeliScanSources(sessionId)
        deleteDeliInventorySnapshots(sessionId)
        deleteDeliInventoryItems(sessionId)
        deleteDeliPromoItems(sessionId)
        deleteDeliSupplierOrderLines(sessionId)
        deleteDeliVerifyLabels(sessionId)
        deleteDeliStickyNotes(sessionId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliScanSession(session: DeliScanSessionRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliScanSources(sources: List<DeliScanSourceRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliInventorySnapshot(snapshot: DeliInventorySnapshotRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliInventoryItems(items: List<DeliInventorySnapshotItemRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliPromoItems(items: List<DeliPromoItemRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliSupplierOrderLines(lines: List<DeliSupplierOrderLineRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliVerifyLabels(labels: List<DeliVerifyLabelRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeliStickyNotes(notes: List<DeliStickyNoteRecord>)

    @Query("SELECT * FROM DeliScanSourceRecord WHERE sessionId = :sessionId ORDER BY id")
    suspend fun deliScanSources(sessionId: String): List<DeliScanSourceRecord>

    @Query("SELECT * FROM DeliInventorySnapshotRecord WHERE sessionId = :sessionId ORDER BY capturedAtMillis DESC LIMIT 1")
    suspend fun latestDeliInventorySnapshotForSession(sessionId: String): DeliInventorySnapshotRecord?

    @Query("SELECT * FROM DeliInventorySnapshotRecord WHERE weekStartEpochDay = :weekStartEpochDay ORDER BY capturedAtMillis DESC LIMIT 1")
    suspend fun latestDeliInventorySnapshotForWeek(weekStartEpochDay: Long): DeliInventorySnapshotRecord?

    @Query("SELECT * FROM DeliInventorySnapshotItemRecord WHERE sessionId = :sessionId ORDER BY name")
    suspend fun deliInventoryItems(sessionId: String): List<DeliInventorySnapshotItemRecord>

    @Query("SELECT * FROM DeliInventorySnapshotItemRecord WHERE snapshotId = :snapshotId ORDER BY name")
    suspend fun deliInventoryItemsForSnapshot(snapshotId: Long): List<DeliInventorySnapshotItemRecord>

    @Query("SELECT * FROM DeliPromoItemRecord WHERE sessionId = :sessionId ORDER BY name")
    suspend fun deliPromoItems(sessionId: String): List<DeliPromoItemRecord>

    @Query("SELECT * FROM DeliSupplierOrderLineRecord WHERE sessionId = :sessionId ORDER BY orderIndex")
    suspend fun deliSupplierOrderLines(sessionId: String): List<DeliSupplierOrderLineRecord>

    @Query("SELECT * FROM DeliVerifyLabelRecord WHERE sessionId = :sessionId ORDER BY confidence, itemName")
    suspend fun deliVerifyLabels(sessionId: String): List<DeliVerifyLabelRecord>

    @Query("SELECT * FROM DeliStickyNoteRecord WHERE sessionId = :sessionId ORDER BY id")
    suspend fun deliStickyNotes(sessionId: String): List<DeliStickyNoteRecord>
}
