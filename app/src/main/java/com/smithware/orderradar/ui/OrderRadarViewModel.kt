package com.smithware.orderradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smithware.orderradar.OrderRadarApp
import com.smithware.orderradar.data.*
import com.smithware.orderradar.domain.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VisionCountRow(
    val suggestion: VisionCountSuggestion,
    val matchedProduct: Product?,
    val confirmedQuantity: Double
)

data class OrderRadarUiState(
    val snapshots: List<ProductSnapshot> = emptyList(),
    val forecasts: List<ForecastResult> = emptyList(),
    val trucks: List<TruckSchedule> = emptyList(),
    val orders: List<OrderDraft> = emptyList(),
    val orderLines: List<OrderLine> = emptyList(),
    val deliveries: List<DeliveryRecord> = emptyList(),
    val deliveryLines: List<DeliveryLine> = emptyList(),
    val variances: List<VarianceLog> = emptyList(),
    val displays: List<DisplayPlan> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val recipeIngredients: List<RecipeIngredient> = emptyList(),
    val visionCorrections: List<VisionCorrection> = emptyList()
)

class OrderRadarViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = (application as OrderRadarApp).repository
    private val settingsStore = SettingsStore(application)

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val state: StateFlow<OrderRadarUiState> = combine(
        repo.snapshots,
        repo.trucks,
        repo.orders,
        repo.orderLines,
        repo.deliveries,
        repo.deliveryLines,
        repo.variances,
        repo.displays,
        repo.recipes,
        repo.recipeIngredients,
        repo.visionCorrections
    ) { values ->
        val snapshots = values[0] as List<ProductSnapshot>
        val forecasts = snapshots.map { ForecastEngine.forecast(it, repo.nextTruckDays(it.truck)) }
        OrderRadarUiState(
            snapshots = snapshots,
            forecasts = forecasts,
            trucks = values[1] as List<TruckSchedule>,
            orders = values[2] as List<OrderDraft>,
            orderLines = values[3] as List<OrderLine>,
            deliveries = values[4] as List<DeliveryRecord>,
            deliveryLines = values[5] as List<DeliveryLine>,
            variances = values[6] as List<VarianceLog>,
            displays = values[7] as List<DisplayPlan>,
            recipes = values[8] as List<Recipe>,
            recipeIngredients = values[9] as List<RecipeIngredient>,
            visionCorrections = values[10] as List<VisionCorrection>
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrderRadarUiState())

    init {
        viewModelScope.launch { repo.seedIfNeeded() }
    }

    fun addCount(product: Product, quantity: Double, note: String = "Manual cooler count") = viewModelScope.launch {
        repo.addCount(product, quantity, note)
    }

    fun addMovement(product: Product, quantity: Double, type: MovementType = MovementType.USED, note: String = "Manual usage entry") = viewModelScope.launch {
        repo.addMovement(product, quantity, type, note)
    }

    fun saveProduct(product: Product) = viewModelScope.launch {
        repo.saveProduct(product)
    }

    fun createOrderFromPhoto(truck: TruckSchedule, lines: List<Pair<Product, Double>>, sourceNote: String) = viewModelScope.launch {
        repo.createOrderFromPhoto(truck, lines, sourceNote)
    }

    fun updateOrderLineQuantity(line: OrderLine, quantity: Double) = viewModelScope.launch {
        repo.updateOrderLineQuantity(line, quantity)
    }

    fun markOrderPlaced(order: OrderDraft) = viewModelScope.launch {
        repo.markOrderPlaced(order)
    }

    fun addForecastToDraft(snapshot: ProductSnapshot, forecast: ForecastResult, fallbackTruck: TruckSchedule?) = viewModelScope.launch {
        val truck = snapshot.truck ?: fallbackTruck ?: return@launch
        repo.addForecastToDraft(snapshot.product, truck, forecast.recommendedOrderQuantity, forecast.reason)
    }

    fun updateDeliveryActual(line: DeliveryLine, actualQuantity: Double) = viewModelScope.launch {
        repo.updateDeliveryActual(line, actualQuantity)
    }

    fun addVariance(product: Product, ordered: Double, received: Double) = viewModelScope.launch {
        val (_, reason) = DeliveryVarianceEngine.evaluate(ordered, received)
        repo.addVariance(product, ordered, received, reason)
    }

    fun setVisionProvider(provider: VisionProvider) = viewModelScope.launch {
        settingsStore.setVisionProvider(provider)
    }

    fun updateAnthropicVisionSettings(apiKey: String, model: String) = viewModelScope.launch {
        settingsStore.updateAnthropicVisionSettings(apiKey, model)
    }

    fun updateOpenAiVisionSettings(apiKey: String, model: String) = viewModelScope.launch {
        settingsStore.updateOpenAiVisionSettings(apiKey, model)
    }

    fun saveVisionCounts(rows: List<VisionCountRow>, photoPath: String?) = viewModelScope.launch {
        rows.forEach { row ->
            val note = "AI shelf photo count (${row.suggestion.confidencePercent}% confidence). ${row.suggestion.notes}".trim()
            val product = row.matchedProduct ?: run {
                val newProduct = Product(
                    name = row.suggestion.itemName,
                    category = ProductCategory.OTHER,
                    defaultUnit = row.suggestion.unit,
                    safetyStock = settings.value.defaultSafetyStock,
                    reorderPoint = settings.value.defaultSafetyStock,
                    notes = "Created from AI shelf photo count."
                )
                newProduct.copy(id = repo.saveProduct(newProduct))
            }
            repo.addCountWithPhoto(product, row.confirmedQuantity, note, photoPath)
            // Every confirmed row -- corrected or left as-is -- feeds VisionLearningEngine so
            // future estimates for this product get better, whether or not the user changed anything.
            repo.addVisionCorrection(
                VisionCorrection(
                    productId = product.id,
                    productName = product.name,
                    aiEstimatedQuantity = row.suggestion.estimatedQuantity,
                    confirmedQuantity = row.confirmedQuantity,
                    aiConfidencePercent = row.suggestion.confidencePercent
                )
            )
        }
    }
}
