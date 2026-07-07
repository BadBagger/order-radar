package com.smithware.orderradar.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Calendar
import kotlin.math.max

class OrderRadarRepository(private val dao: OrderRadarDao) {
    val products = dao.products()
    val trucks = dao.trucks()
    val counts = dao.counts()
    val movements = dao.movements()
    val orders = dao.orders()
    val orderLines = dao.orderLines()
    val deliveries = dao.deliveries()
    val deliveryLines = dao.deliveryLines()
    val variances = dao.variances()
    val displays = dao.displays()
    val recipes = dao.recipes()
    val recipeIngredients = dao.recipeIngredients()
    val productTruckLinks = dao.productTruckLinks()

    val snapshots: Flow<List<ProductSnapshot>> = combine(products, counts, movements, trucks, productTruckLinks) { products, counts, movements, trucks, links ->
        products.map { product ->
            val truckId = links.firstOrNull { it.productId == product.id }?.truckScheduleId
            ProductSnapshot(
                product = product,
                latestCount = counts.firstOrNull { it.productId == product.id },
                movements = movements.filter { it.productId == product.id },
                truck = trucks.firstOrNull { it.id == truckId }
            )
        }
    }

    suspend fun seedIfNeeded() {
        if (dao.productCount() > 0) return
        val boxMeat = dao.insertTruck(TruckSchedule(name = "Box Meat Truck", deliveryDays = "Mon, Thu", orderCutoffDays = "Order by 8:00 AM day before", leadTimeDays = 0))
        val ggm = dao.insertTruck(TruckSchedule(name = "GGM Truck", deliveryDays = "Tue, Fri", orderCutoffDays = "Order by 8:00 AM day before", leadTimeDays = 0))
        val grocery = dao.insertTruck(TruckSchedule(name = "Grocery Truck", deliveryDays = "Wed", orderCutoffDays = "Order by 9:00 AM day before", leadTimeDays = 0))
        val vendor = dao.insertTruck(TruckSchedule(name = "Vendor Delivery", deliveryDays = "Fri", orderCutoffDays = "Order by 2:00 PM 2 days before", leadTimeDays = 1))

        val chicken = addProduct("Chicken Breast Box", ProductCategory.BOX_MEAT, "Box Meat Co.", "Cooler A - Shelf 2", "boxes", "40 lb", 40.0, 1.0, 2.0, boxMeat, 2.0, 1.2)
        addProduct("Caesar Pasta Salad", ProductCategory.GGM, "GGM", "GGM Rack", "boxes", null, null, 1.0, 1.0, ggm, 1.0, 0.8)
        addProduct("Turkey Case", ProductCategory.DELI_MEAT, "Warehouse", "Cooler B", "cases", null, null, 1.0, 1.0, boxMeat, 3.0, 0.7)
        addProduct("Ham Case", ProductCategory.DELI_MEAT, "Warehouse", "Cooler B", "cases", null, null, 1.0, 1.0, boxMeat, 2.0, 0.3)
        addProduct("GGM Entree Tray", ProductCategory.GGM, "GGM", "Grab-and-go", "trays", null, null, 6.0, 8.0, ggm, 5.0, 1.0)
        val chips = addProduct("Stacy's Chips Display", ProductCategory.DISPLAY, "Vendor", "Front Lobby", "boxes", null, null, 2.0, 1.0, vendor, 1.0, 0.7)
        addProduct("Pasta Salad Base", ProductCategory.PREPARED_FOOD, "Warehouse", "Prep Cooler", "boxes", null, null, 1.0, 1.0, grocery, 2.0, 0.4)
        addProduct("Cheese Box", ProductCategory.WAREHOUSE, "Warehouse", "Cooler C", "boxes", null, null, 1.0, 1.0, grocery, 4.0, 0.5)

        val now = System.currentTimeMillis()
        dao.insertDisplay(DisplayPlan(name = "Stacy's Chips Display", productId = chips, displayLocation = "Front Lobby", adWeekStart = now - days(1), adWeekEnd = now + days(4), startingQuantity = 3.0, currentQuantity = 1.0, forecastedWeeklyUsage = 5.0, reorderPoint = 1.0, plannedOrderQuantity = 3.0, targetDisplayLevel = 3.0, deliveryDay = "Friday", status = DisplayStatus.REORDER_NEEDED, notes = "Ad-week feature"))
        val orderId = dao.insertOrder(OrderDraft(truckScheduleId = boxMeat, title = "Box Meat Truck Order", expectedDeliveryDate = now + days(3), notes = "Chicken projected short before next truck."))
        dao.insertOrderLine(OrderLine(orderDraftId = orderId, productId = chicken, recommendedQuantity = 3.0, userQuantity = 3.0, unit = "boxes", reason = "Order 3 boxes because you have 2 on hand, average usage is 1.2/day, next truck is in 3 days, and safety stock is 1 box."))
        val deliveryId = dao.insertDelivery(DeliveryRecord(truckScheduleId = vendor, orderDraftId = null, notes = "Display allocation surprise."))
        val deliveryLineId = dao.insertDeliveryLine(DeliveryLine(deliveryRecordId = deliveryId, productId = chips, orderedQuantity = 3.0, expectedQuantity = 3.0, actualQuantity = 6.0, unit = "boxes", variance = 3.0, status = DeliveryStatus.OVER, notes = "Review display allocation or duplicate order."))
        dao.insertVariance(VarianceLog(productId = chips, deliveryLineId = deliveryLineId, varianceType = VarianceType.RECEIVED_MORE_THAN_ORDERED, orderedQuantity = 3.0, receivedQuantity = 6.0, difference = 3.0, reason = "Received 3 more than expected. Review for display allocation, duplicate order, or manual override."))
        val recipeId = dao.insertRecipe(Recipe(name = "Chicken Caesar Pasta Salad", outputQuantity = 24.0, outputUnit = "containers"))
        dao.insertRecipeIngredient(RecipeIngredient(recipeId = recipeId, productId = chicken, quantityUsed = 0.2, unit = "boxes"))
    }

    private suspend fun addProduct(name: String, category: ProductCategory, vendor: String, location: String, unit: String, caseSize: String?, boxWeight: Double?, safety: Double, reorder: Double, truckId: Long, count: Double, dailyUse: Double): Long {
        val productId = dao.upsertProduct(Product(name = name, category = category, vendor = vendor, storageLocation = location, defaultUnit = unit, caseSize = caseSize, boxWeight = boxWeight, safetyStock = safety, reorderPoint = reorder, notes = "Seed sample for Order Radar MVP"))
        dao.insertProductTruckLink(ProductTruckLink(productId = productId, truckScheduleId = truckId))
        dao.insertCount(InventoryCount(productId = productId, quantity = count, unit = unit, notes = "Opening sample count"))
        repeat(14) { day ->
            dao.insertMovement(MovementEntry(productId = productId, quantity = dailyUse, unit = unit, movementType = MovementType.USED, movementDate = System.currentTimeMillis() - days(14 - day), notes = "Sample daily movement"))
        }
        return productId
    }

    suspend fun addCount(product: Product, quantity: Double, note: String) = dao.insertCount(InventoryCount(productId = product.id, quantity = quantity, unit = product.defaultUnit, notes = note))
    suspend fun addMovement(product: Product, quantity: Double, type: MovementType, note: String) = dao.insertMovement(MovementEntry(productId = product.id, quantity = quantity, unit = product.defaultUnit, movementType = type, notes = note))
    suspend fun saveProduct(product: Product): Long = dao.upsertProduct(product.copy(updatedAt = System.currentTimeMillis()))
    suspend fun createOrderFromPhoto(
        truck: TruckSchedule,
        lines: List<Pair<Product, Double>>,
        sourceNote: String
    ): Long {
        val now = System.currentTimeMillis()
        val orderId = dao.insertOrder(
            OrderDraft(
                truckScheduleId = truck.id,
                title = "${truck.name} Photo Import",
                orderDate = now,
                expectedDeliveryDate = now + days(max(1, nextTruckDays(truck))),
                status = OrderDraftStatus.DRAFT,
                notes = "Imported from photo/OCR assist. Review every line before using this in the official ordering system. $sourceNote"
            )
        )
        lines.forEach { (product, quantity) ->
            dao.insertOrderLine(
                OrderLine(
                    orderDraftId = orderId,
                    productId = product.id,
                    recommendedQuantity = 0.0,
                    userQuantity = quantity,
                    unit = product.defaultUnit,
                    reason = "Imported from order photo. Confirm quantity before marking placed."
                )
            )
        }
        return orderId
    }
    suspend fun updateOrderLineQuantity(line: OrderLine, quantity: Double) {
        if (quantity <= 0.0) {
            dao.deleteOrderLine(line.id)
        } else {
            dao.updateOrderLineQuantity(line.id, quantity)
        }
    }

    suspend fun markOrderPlaced(order: OrderDraft) {
        dao.updateOrderStatus(order.id, OrderDraftStatus.PLACED)
        if (dao.deliveryForOrder(order.id) != null) return
        val lines = dao.orderLinesForDraft(order.id)
        if (lines.isEmpty()) return
        val deliveryId = dao.insertDelivery(
            DeliveryRecord(
                truckScheduleId = order.truckScheduleId,
                orderDraftId = order.id,
                deliveryDate = order.expectedDeliveryDate,
                notes = "Created from placed order draft. Enter actual received quantities on delivery day."
            )
        )
        lines.forEach { line ->
            dao.insertDeliveryLine(
                DeliveryLine(
                    deliveryRecordId = deliveryId,
                    productId = line.productId,
                    orderedQuantity = line.userQuantity,
                    expectedQuantity = line.userQuantity,
                    actualQuantity = 0.0,
                    unit = line.unit,
                    variance = -line.userQuantity,
                    status = DeliveryStatus.NOT_RECEIVED,
                    notes = "Awaiting delivery check."
                )
            )
        }
    }

    suspend fun updateDeliveryActual(line: DeliveryLine, actualQuantity: Double) {
        val actual = max(0.0, actualQuantity)
        val variance = actual - line.expectedQuantity
        val status = when {
            actual <= 0.0 -> DeliveryStatus.NOT_RECEIVED
            variance < 0.0 -> DeliveryStatus.SHORT
            variance > 0.0 -> DeliveryStatus.OVER
            else -> DeliveryStatus.MATCHES_EXPECTED
        }
        val note = when (status) {
            DeliveryStatus.NOT_RECEIVED -> "Not received yet."
            DeliveryStatus.SHORT -> "Received less than expected. Watch supply before next truck."
            DeliveryStatus.OVER -> "Received more than expected. Review for allocation, duplicate order, or manual override."
            DeliveryStatus.MATCHES_EXPECTED -> "Delivery matches expected quantity."
            else -> "Review delivery line."
        }
        dao.updateDeliveryLineActual(line.id, actual, variance, status, note)
    }

    suspend fun addForecastToDraft(product: Product, truck: TruckSchedule, quantity: Double, reason: String): Long {
        val now = System.currentTimeMillis()
        val order = dao.latestDraftForTruck(truck.id)
        val orderId = order?.id ?: dao.insertOrder(
            OrderDraft(
                truckScheduleId = truck.id,
                title = "${truck.name} Forecast Draft",
                orderDate = now,
                expectedDeliveryDate = now + days(max(1, nextTruckDays(truck))),
                status = OrderDraftStatus.DRAFT,
                notes = "Built from Order Radar forecast suggestions. Confirm every line before using this in the official ordering system."
            )
        )
        val existing = dao.orderLineForProduct(orderId, product.id)
        if (existing != null) {
            dao.updateOrderLineQuantity(existing.id, quantity)
            return orderId
        }
        dao.insertOrderLine(
            OrderLine(
                orderDraftId = orderId,
                productId = product.id,
                recommendedQuantity = quantity,
                userQuantity = quantity,
                unit = product.defaultUnit,
                reason = reason
            )
        )
        return orderId
    }

    suspend fun addVariance(product: Product, ordered: Double, received: Double, reason: String) {
        val difference = received - ordered
        val type = if (difference > 0) VarianceType.RECEIVED_MORE_THAN_ORDERED else VarianceType.RECEIVED_LESS_THAN_ORDERED
        dao.insertVariance(VarianceLog(productId = product.id, varianceType = type, orderedQuantity = ordered, receivedQuantity = received, difference = difference, reason = reason))
    }

    fun nextTruckDays(truck: TruckSchedule?): Int {
        if (truck == null) return -1
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val targets = truck.deliveryDays.split(",").mapNotNull { dayNameToCalendar(it.trim()) }
        return targets.minOfOrNull { target -> (target - today + 7) % 7 }.let { days -> max(1, days ?: 3) }
    }

    private fun dayNameToCalendar(day: String): Int? = when (day.take(3).lowercase()) {
        "sun" -> Calendar.SUNDAY
        "mon" -> Calendar.MONDAY
        "tue" -> Calendar.TUESDAY
        "wed" -> Calendar.WEDNESDAY
        "thu" -> Calendar.THURSDAY
        "fri" -> Calendar.FRIDAY
        "sat" -> Calendar.SATURDAY
        else -> null
    }

    private fun days(value: Int) = value * 24L * 60L * 60L * 1000L
}
