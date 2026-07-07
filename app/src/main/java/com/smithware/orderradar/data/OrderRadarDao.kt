package com.smithware.orderradar.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
