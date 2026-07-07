package com.smithware.orderradar.domain

import com.smithware.orderradar.data.*
import kotlin.math.ceil
import kotlin.math.max

object MovementAverageEngine {
    private val usageTypes = setOf(MovementType.USED, MovementType.SOLD, MovementType.OPENED, MovementType.PREPPED, MovementType.WASTED, MovementType.DAMAGED, MovementType.TRANSFERRED)

    fun averageDailyUsage(movements: List<MovementEntry>, windowDays: Int = 14): Double {
        val cutoff = System.currentTimeMillis() - windowDays * 24L * 60L * 60L * 1000L
        val total = movements.filter { it.movementType in usageTypes && it.movementDate >= cutoff }.sumOf { it.quantity }
        return total / windowDays.coerceAtLeast(1)
    }

    fun weeklyUsage(movements: List<MovementEntry>) = averageDailyUsage(movements, 14) * 7
}

object ForecastEngine {
    fun forecast(snapshot: ProductSnapshot, daysUntilNextTruck: Int, forecastWindow: Int = 14): ForecastResult {
        val product = snapshot.product
        val onHand = snapshot.latestCount?.quantity ?: 0.0
        val avg = MovementAverageEngine.averageDailyUsage(snapshot.movements, forecastWindow)
        if (snapshot.truck == null) {
            return ForecastResult(product.id, onHand, avg, -1, 0.0, product.safetyStock, 0.0, 0.0, ForecastStatus.UNKNOWN, "Assign a truck schedule before forecasting this product.")
        }
        val daysSupply = if (avg <= 0.0) Double.POSITIVE_INFINITY else onHand / avg
        val needed = avg * daysUntilNextTruck
        val recommended = max(0.0, ceil(needed + product.safetyStock - onHand))
        val status = when {
            onHand <= 0.0 -> ForecastStatus.CRITICAL
            onHand <= product.reorderPoint -> ForecastStatus.ORDER_NEEDED
            avg <= 0.0 -> ForecastStatus.WATCH
            daysSupply < daysUntilNextTruck -> ForecastStatus.ORDER_NEEDED
            daysSupply <= daysUntilNextTruck + 1 -> ForecastStatus.WATCH
            daysSupply > daysUntilNextTruck + 10 -> ForecastStatus.OVERSTOCK_RISK
            else -> ForecastStatus.GOOD
        }
        val reason = when (status) {
            ForecastStatus.CRITICAL -> "${product.name} is at zero. Order before the next truck if available."
            ForecastStatus.ORDER_NEEDED -> "Order ${recommended.clean()} ${product.defaultUnit} because you have ${onHand.clean()} on hand, average usage is ${avg.clean()}/day, next truck is in $daysUntilNextTruck days, and safety stock is ${product.safetyStock.clean()} ${product.defaultUnit}."
            ForecastStatus.WATCH -> "${product.name} is close. It should last ${daysSupply.clean()} days and the next truck is in $daysUntilNextTruck days."
            ForecastStatus.OVERSTOCK_RISK -> "${product.name} has more supply than the near-term forecast needs."
            ForecastStatus.UNKNOWN -> "Forecast needs more setup."
            ForecastStatus.GOOD -> "${product.name} is enough until next truck."
        }
        return ForecastResult(product.id, onHand, avg, daysUntilNextTruck, needed, product.safetyStock, recommended, daysSupply, status, reason)
    }
}

object DeliveryVarianceEngine {
    fun evaluate(expected: Double, actual: Double): Pair<DeliveryStatus, String> {
        val variance = actual - expected
        return when {
            variance > 0 -> DeliveryStatus.OVER to "Received ${variance.clean()} more than expected. Review for display allocation, duplicate order, or manual override."
            variance < 0 -> DeliveryStatus.SHORT to "Received ${(-variance).clean()} less than expected. Watch supply before next truck."
            else -> DeliveryStatus.MATCHES_EXPECTED to "Delivery matches expected quantity."
        }
    }
}

data class DisplayForecast(val projectedEnding: Double, val daysLeft: Int, val reorderNeeded: Boolean, val overstockRisk: Boolean, val explanation: String)

object DisplayForecastEngine {
    fun forecast(display: DisplayPlan): DisplayForecast {
        val now = System.currentTimeMillis()
        val daysLeft = max(0, ceil((display.adWeekEnd - now) / (24.0 * 60.0 * 60.0 * 1000.0)).toInt())
        val dailyUse = display.forecastedWeeklyUsage / 7.0
        val projectedEnding = display.currentQuantity + display.plannedOrderQuantity - dailyUse * daysLeft
        val reorder = display.currentQuantity <= display.reorderPoint || projectedEnding < display.reorderPoint
        val overstock = projectedEnding > display.targetDisplayLevel * 1.5
        val text = when {
            reorder -> "Projected to run low before the ad week ends. Recommended order: ${display.plannedOrderQuantity.clean()} boxes."
            overstock -> "Projected ending is high. Watch for overstock risk."
            else -> "Display is on pace through the ad week."
        }
        return DisplayForecast(projectedEnding, daysLeft, reorder, overstock, text)
    }
}

object RecipeUsageEngine {
    fun movementsFor(recipe: Recipe, ingredients: List<RecipeIngredient>, batchCount: Double, products: List<Product>): List<MovementEntry> =
        ingredients.mapNotNull { ingredient ->
            val product = products.firstOrNull { it.id == ingredient.productId } ?: return@mapNotNull null
            MovementEntry(productId = product.id, quantity = ingredient.quantityUsed * batchCount, unit = ingredient.unit, movementType = MovementType.PREPPED, linkedRecipeId = recipe.id, notes = "Production: ${recipe.name}, ${batchCount.clean()} batch(es)")
        }
}

object OrderRecommendationEngine {
    fun lines(snapshots: List<ProductSnapshot>, daysUntilTruck: (ProductSnapshot) -> Int): List<ForecastResult> =
        snapshots.map { ForecastEngine.forecast(it, daysUntilTruck(it)) }
            .filter { it.status in setOf(ForecastStatus.ORDER_NEEDED, ForecastStatus.CRITICAL, ForecastStatus.WATCH) }
}

fun Double.clean(): String = if (this.isInfinite()) "unknown" else if (this % 1.0 == 0.0) this.toInt().toString() else "%.1f".format(this)
