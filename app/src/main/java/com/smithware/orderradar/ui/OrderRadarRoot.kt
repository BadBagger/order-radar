package com.smithware.orderradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smithware.orderradar.data.*
import com.smithware.orderradar.domain.*
import com.smithware.orderradar.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Count("Count", Icons.Default.CheckBox),
    Orders("Orders", Icons.Default.Assignment),
    Displays("Displays", Icons.Default.Storefront),
    Reports("Reports", Icons.Default.Assessment)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderRadarRoot(vm: OrderRadarViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val settings by vm.settings.collectAsState()
    var tab by remember { mutableStateOf(Tab.Home) }
    var detailProductId by remember { mutableStateOf<Long?>(null) }
    var showProducts by remember { mutableStateOf(false) }
    var editProductId by remember { mutableStateOf<Long?>(null) }
    var showPhotoReview by remember { mutableStateOf(false) }
    var showOrderImport by remember { mutableStateOf(false) }
    var showVisionCount by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = RadarCharcoal,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF151714)) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item; detailProductId = null; showProducts = false; editProductId = null; showOrderImport = false; showVisionCount = false },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = RadarCharcoal, selectedTextColor = RadarLime, indicatorColor = RadarLime, unselectedIconColor = RadarMuted, unselectedTextColor = RadarMuted)
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val product = detailProductId?.let { id -> state.snapshots.firstOrNull { it.product.id == id } }
            val editProduct = editProductId?.let { id -> state.product(id) }
            when {
                showVisionCount -> PhotoVisionCountScreen(
                    snapshots = state.snapshots,
                    provider = settings.visionProvider,
                    apiKey = settings.activeVisionApiKey,
                    model = settings.activeVisionModel,
                    corrections = state.visionCorrections,
                    onSaveCounts = { rows, photoPath -> vm.saveVisionCounts(rows, photoPath) },
                    onOpenSettings = { showVisionCount = false; tab = Tab.Reports },
                    onBack = { showVisionCount = false }
                )
                showPhotoReview -> CameraOcrAssistScreen(
                    products = state.snapshots.map { it.product },
                    onSaveCount = vm::addCount,
                    onBack = { showPhotoReview = false }
                )
                showOrderImport -> OrderPhotoImportScreen(
                    products = state.snapshots.map { it.product },
                    trucks = state.trucks,
                    onSaveOrder = vm::createOrderFromPhoto,
                    onBack = { showOrderImport = false }
                )
                editProductId != null -> ProductEditorScreen(
                    product = editProduct,
                    onBack = { editProductId = null },
                    onSave = { productToSave ->
                        vm.saveProduct(productToSave)
                        editProductId = null
                    }
                )
                showProducts -> ProductListScreen(
                    state = state,
                    onBack = { showProducts = false },
                    onOpenProduct = { detailProductId = it; showProducts = false },
                    onAddProduct = { editProductId = 0L },
                    onEditProduct = { editProductId = it }
                )
                product != null -> ProductDetailScreen(snapshot = product, forecast = state.forecasts.firstOrNull { it.productId == product.product.id }, onBack = { detailProductId = null }, onCount = { tab = Tab.Count; detailProductId = null }, onUsage = { vm.addMovement(product.product, 1.0) }, onEdit = { editProductId = product.product.id })
                tab == Tab.Home -> HomeDashboardScreen(state, onOpenProduct = { detailProductId = it }, onProductList = { showProducts = true }, onPhotoReview = { showPhotoReview = true }, onVisionCount = { showVisionCount = true })
                tab == Tab.Count -> CoolerCountScreen(state, onSaveCount = vm::addCount, onSaveMovement = vm::addMovement, onPhotoReview = { showPhotoReview = true }, onVisionCount = { showVisionCount = true })
                tab == Tab.Orders -> OrdersScreen(
                    state = state,
                    onVariance = vm::addVariance,
                    onImportPhoto = { showOrderImport = true },
                    onUpdateLine = vm::updateOrderLineQuantity,
                    onMarkPlaced = vm::markOrderPlaced,
                    onAddForecast = vm::addForecastToDraft,
                    onUpdateDelivery = vm::updateDeliveryActual
                )
                tab == Tab.Displays -> DisplaysScreen(state)
                tab == Tab.Reports -> ReportsScreen(
                    state,
                    settings,
                    onSetVisionProvider = vm::setVisionProvider,
                    onSaveAnthropicVisionSettings = vm::updateAnthropicVisionSettings,
                    onSaveOpenAiVisionSettings = vm::updateOpenAiVisionSettings
                )
            }
        }
    }
}

@Composable
private fun HomeDashboardScreen(state: OrderRadarUiState, onOpenProduct: (Long) -> Unit, onProductList: () -> Unit, onPhotoReview: () -> Unit, onVisionCount: () -> Unit) {
    Screen("Order Radar", "Forecast orders before you run out.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onProductList, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Inventory2, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Products")
            }
            OutlinedButton(onClick = onPhotoReview, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Photo")
            }
        }
        SectionHeader("Needs Order")
        state.forecasts.filter { it.status == ForecastStatus.ORDER_NEEDED || it.status == ForecastStatus.CRITICAL }.take(4).forEach { forecast ->
            val snapshot = state.snapshot(forecast.productId) ?: return@forEach
            ProductCard(snapshot, forecast, "Order ${forecast.recommendedOrderQuantity.clean()}", onOpenProduct)
        }
        SectionHeader("Watch List")
        state.forecasts.filter { it.status == ForecastStatus.WATCH }.take(3).forEach { forecast ->
            val snapshot = state.snapshot(forecast.productId) ?: return@forEach
            ProductCard(snapshot, forecast, "Watch", onOpenProduct)
        }
        SectionHeader("Enough Until Next Truck")
        state.forecasts.filter { it.status == ForecastStatus.GOOD }.take(3).forEach { forecast ->
            val snapshot = state.snapshot(forecast.productId) ?: return@forEach
            ProductCard(snapshot, forecast, "Good", onOpenProduct)
        }
        SectionHeader("Delivery Variance")
        state.variances.take(3).forEach { variance ->
            val product = state.product(variance.productId)
            DeliveryVarianceCard(product?.name ?: "Unknown product", variance)
        }
        SectionHeader("Upcoming Trucks")
        state.trucks.forEach { TruckCard(it) }
        PhotoCaptureCard(onPhotoReview, onVisionCount)
    }
}

@Composable
private fun CoolerCountScreen(state: OrderRadarUiState, onSaveCount: (Product, Double, String) -> Unit, onSaveMovement: (Product, Double, MovementType, String) -> Unit, onPhotoReview: () -> Unit, onVisionCount: () -> Unit) {
    var selectedId by remember(state.snapshots) { mutableStateOf(state.snapshots.firstOrNull()?.product?.id ?: 0L) }
    var quantity by remember { mutableStateOf("2") }
    val selected = state.product(selectedId) ?: state.snapshots.firstOrNull()?.product
    Screen("Cooler Count", "Fast manual counts with photo/OCR assist.") {
        ProductPicker(state.snapshots.map { it.product }, selectedId) { selectedId = it }
        NumberEntry("Current count", quantity, selected?.defaultUnit ?: "units") { quantity = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("+1" to 1.0, "-1" to -1.0, "+0.5" to 0.5, "-0.5" to -0.5).forEach { (label, delta) ->
                OutlinedButton(onClick = { quantity = ((quantity.toDoubleOrNull() ?: 0.0) + delta).coerceAtLeast(0.0).clean() }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        BigActionButton("Save Count", Icons.Default.Save) { selected?.let { onSaveCount(it, quantity.toDoubleOrNull() ?: 0.0, "Manual cooler count") } }
        OutlinedButton(onClick = { quantity = "0"; selected?.let { onSaveCount(it, 0.0, "Marked empty") } }, modifier = Modifier.fillMaxWidth()) { Text("Mark Empty") }
        PhotoCaptureCard(onPhotoReview, onVisionCount)
        SectionHeader("Movement Tracker")
        NumberEntry("Add usage", "1", selected?.defaultUnit ?: "units") { }
        BigActionButton("Log 1 used", Icons.Default.TrendingDown) { selected?.let { onSaveMovement(it, 1.0, MovementType.USED, "Manual usage entry") } }
        state.snapshots.flatMap { it.movements }.take(8).forEach { entry ->
            val product = state.product(entry.productId)
            SimpleCard {
                Text(product?.name ?: "Product", fontWeight = FontWeight.SemiBold)
                Text("${entry.movementType.name.lowercase().replaceFirstChar { it.uppercase() }} ${entry.quantity.clean()} ${entry.unit}", color = RadarMuted)
            }
        }
        SectionHeader("Recipe / Production")
        state.recipes.forEach { recipe ->
            SimpleCard {
                Text(recipe.name, fontWeight = FontWeight.SemiBold)
                Text("Makes ${recipe.outputQuantity.clean()} ${recipe.outputUnit}", color = RadarMuted)
                Text("Log production to record product usage and update forecasts indirectly.", color = RadarMuted)
            }
        }
    }
}

@Composable
private fun OrdersScreen(
    state: OrderRadarUiState,
    onVariance: (Product, Double, Double) -> Unit,
    onImportPhoto: () -> Unit,
    onUpdateLine: (OrderLine, Double) -> Unit,
    onMarkPlaced: (OrderDraft) -> Unit,
    onAddForecast: (ProductSnapshot, ForecastResult, TruckSchedule?) -> Unit,
    onUpdateDelivery: (DeliveryLine, Double) -> Unit
) {
    var selected by remember { mutableStateOf("Forecast") }
    Screen("Orders", "Build orders, check deliveries, and explain surprises.") {
        SegmentedButtonRow(selected, listOf("Forecast", "Builder", "Delivery", "Variance", "Trucks")) { selected = it }
        when (selected) {
            "Forecast" -> {
                state.forecasts.sortedByDescending { it.recommendedOrderQuantity }.forEach { forecast ->
                    val snapshot = state.snapshot(forecast.productId) ?: return@forEach
                    ForecastCard(snapshot, forecast)
                }
            }
            "Builder" -> OrderBuilderSection(state, onImportPhoto, onUpdateLine, onMarkPlaced, onAddForecast)
            "Delivery" -> DeliveryDaySection(state, onVariance, onUpdateDelivery)
            "Variance" -> state.variances.forEach { DeliveryVarianceCard(state.product(it.productId)?.name ?: "Product", it) }
            "Trucks" -> state.trucks.forEach { TruckCard(it) }
        }
    }
}

@Composable
private fun OrderBuilderSection(
    state: OrderRadarUiState,
    onImportPhoto: () -> Unit,
    onUpdateLine: (OrderLine, Double) -> Unit,
    onMarkPlaced: (OrderDraft) -> Unit,
    onAddForecast: (ProductSnapshot, ForecastResult, TruckSchedule?) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val lines = state.forecasts.filter { it.recommendedOrderQuantity > 0.0 }
    val latestDraft = state.orders.firstOrNull { it.status == OrderDraftStatus.DRAFT }
    val savedLines = latestDraft?.let { order -> state.orderLines.filter { it.orderDraftId == order.id } }.orEmpty()
    BigActionButton("Scan Order Photo", Icons.Default.PhotoCamera) { onImportPhoto() }
    WarningBanner("Photo import creates an editable draft only. Confirm it before copying or marking placed.")
    if (latestDraft != null) {
        SimpleCard {
            Text(latestDraft.title, fontWeight = FontWeight.Bold)
            Text(latestDraft.notes ?: "Draft order", color = RadarMuted)
        }
        savedLines.forEach { line ->
            val product = state.product(line.productId) ?: return@forEach
            SimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(product.name, fontWeight = FontWeight.SemiBold)
                        Text("Draft quantity: ${line.userQuantity.clean()} ${line.unit}", color = RadarText)
                        Text(line.reason, color = RadarMuted)
                    }
                    FilledIconButton(
                        onClick = { onUpdateLine(line, (line.userQuantity - 1.0).coerceAtLeast(0.0)) },
                        modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RadarPanel, contentColor = RadarText)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                    }
                    FilledIconButton(
                        onClick = { onUpdateLine(line, line.userQuantity + 1.0) },
                        modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RadarLime, contentColor = RadarCharcoal)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase")
                    }
                }
            }
        }
        if (savedLines.isNotEmpty()) {
            BigActionButton("Copy Draft Summary", Icons.Default.ContentCopy) {
                clipboard.setText(AnnotatedString(buildOrderDraftSummary(state, latestDraft, savedLines)))
            }
            OutlinedButton(onClick = { onMarkPlaced(latestDraft) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Done, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Mark Order Placed")
            }
        }
        SectionHeader("Forecast suggestions")
    }
    SimpleCard {
        Text("Box Meat Truck Order", fontWeight = FontWeight.Bold)
        Text("This app does not submit official orders. Copy this into your workplace ordering system.", color = RadarOrange)
    }
    lines.forEach { forecast ->
        val snapshot = state.snapshot(forecast.productId) ?: return@forEach
        val product = snapshot.product
        SimpleCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(product.name, fontWeight = FontWeight.SemiBold)
                    Text("Recommended: ${forecast.recommendedOrderQuantity.clean()} ${product.defaultUnit}", color = RadarMuted)
                    Text(forecast.reason, color = RadarMuted)
                }
                Button(
                    onClick = { onAddForecast(snapshot, forecast, state.trucks.firstOrNull()) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun DeliveryDaySection(
    state: OrderRadarUiState,
    onVariance: (Product, Double, Double) -> Unit,
    onUpdateDelivery: (DeliveryLine, Double) -> Unit
) {
    val linesByRecord = state.deliveryLines.groupBy { it.deliveryRecordId }
    state.deliveries.forEach { delivery ->
        val truck = state.trucks.firstOrNull { it.id == delivery.truckScheduleId }
        SectionHeader(truck?.name ?: "Delivery Check")
        Text("Expected: ${delivery.deliveryDate.shortDate()}", color = RadarMuted)
        linesByRecord[delivery.id].orEmpty().forEach { line ->
            DeliveryLineCard(state, line, onUpdateDelivery)
        }
    }
    if (state.deliveryLines.isEmpty()) {
        EmptyState("No delivery checks yet", "Mark an order draft placed to create expected delivery lines.")
    }
    state.snapshots.firstOrNull()?.product?.let { product ->
        OutlinedButton(onClick = { onVariance(product, 4.0, 3.0) }, modifier = Modifier.fillMaxWidth()) {
            Text("Add sample short variance")
        }
    }
}

@Composable
private fun DeliveryLineCard(state: OrderRadarUiState, line: DeliveryLine, onUpdateDelivery: (DeliveryLine, Double) -> Unit) {
    val product = state.product(line.productId) ?: return
    SimpleCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.SemiBold)
                Text("Ordered: ${line.orderedQuantity.clean()} ${line.unit}", color = RadarMuted)
                Text("Received: ${line.actualQuantity.clean()} ${line.unit}", color = RadarText)
                Text("Variance: ${line.variance.clean()} | ${line.notes ?: ""}", color = if (line.variance == 0.0) RadarLime else RadarOrange)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(line.status.name.readable(), if (line.variance == 0.0) ForecastStatus.GOOD else ForecastStatus.ORDER_NEEDED)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledIconButton(
                        onClick = { onUpdateDelivery(line, line.actualQuantity - 1.0) },
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RadarPanel, contentColor = RadarText)
                    ) { Icon(Icons.Default.Remove, contentDescription = "Decrease received") }
                    FilledIconButton(
                        onClick = { onUpdateDelivery(line, line.actualQuantity + 1.0) },
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RadarLime, contentColor = RadarCharcoal)
                    ) { Icon(Icons.Default.Add, contentDescription = "Increase received") }
                }
                TextButton(onClick = { onUpdateDelivery(line, line.expectedQuantity) }) {
                    Text("Match")
                }
            }
        }
    }
}

@Composable
private fun DisplaysScreen(state: OrderRadarUiState) {
    var selectedDisplay by remember { mutableStateOf<Long?>(null) }
    val detail = selectedDisplay?.let { id -> state.displays.firstOrNull { it.id == id } }
    if (detail != null) {
        DisplayDetailScreen(state, detail) { selectedDisplay = null }
        return
    }
    Screen("Display Forecast", "Track public displays through the ad week.") {
        state.displays.forEach { display ->
            val product = state.product(display.productId)
            val forecast = DisplayForecastEngine.forecast(display)
            DisplayCard(display, product?.name ?: "Product", forecast) { selectedDisplay = display.id }
        }
    }
}

@Composable
private fun DisplayDetailScreen(state: OrderRadarUiState, display: DisplayPlan, onBack: () -> Unit) {
    val product = state.product(display.productId)
    val forecast = DisplayForecastEngine.forecast(display)
    Screen(display.name, "Display detail and ad-week progress.", onBack = onBack) {
        SimpleCard {
            Text(product?.name ?: "Linked product", fontWeight = FontWeight.Bold)
            Text("Location: ${display.displayLocation}", color = RadarMuted)
            Text("Current: ${display.currentQuantity.clean()} boxes", color = RadarMuted)
            Text("Ad week remaining: ${forecast.daysLeft} days", color = RadarMuted)
            Text("Projected ending: ${forecast.projectedEnding.clean()} boxes", color = RadarMuted)
            Text(forecast.explanation, color = if (forecast.reorderNeeded) RadarOrange else RadarMuted)
        }
        BigActionButton("Update Display Count", Icons.Default.Edit) { }
        OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text("Add Order / Delivery") }
        OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) { Text("Copy Summary") }
    }
}

@Composable
private fun ReportsScreen(
    state: OrderRadarUiState,
    settings: AppSettings,
    onSetVisionProvider: (VisionProvider) -> Unit,
    onSaveAnthropicVisionSettings: (String, String) -> Unit,
    onSaveOpenAiVisionSettings: (String, String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val report = buildReport(state)
    Screen("Reports", "Copyable plain-text manager summaries.") {
        listOf("Recommended Order Report", "Delivery Variance Report", "Products Likely to Run Out", "Overstock Risk Report", "Movement Summary", "Active Display Summary", "Truck Day Summary", "Product History Summary").forEach {
            ReportCard(it)
        }
        SimpleCard {
            Text("Order Radar Summary", fontWeight = FontWeight.Bold)
            Text(report, color = RadarMuted)
        }
        BigActionButton("Copy Report", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString(report)) }
        SettingsSection(settings, onSetVisionProvider, onSaveAnthropicVisionSettings, onSaveOpenAiVisionSettings)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductListScreen(
    state: OrderRadarUiState,
    onBack: () -> Unit,
    onOpenProduct: (Long) -> Unit,
    onAddProduct: () -> Unit,
    onEditProduct: (Long) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val options = listOf("All", "Needs", "Watch", "Good", "Displays", "Box meat", "GGM", "Vendor", "Warehouse")
    val filtered = state.snapshots.filter { snapshot ->
        val product = snapshot.product
        val forecast = state.forecasts.firstOrNull { it.productId == product.id }
        val matchesSearch = search.isBlank() || product.name.contains(search, ignoreCase = true) || product.category.name.contains(search, ignoreCase = true) || product.vendor.orEmpty().contains(search, ignoreCase = true)
        val matchesFilter = when (filter) {
            "Needs" -> forecast?.status in setOf(ForecastStatus.ORDER_NEEDED, ForecastStatus.CRITICAL)
            "Watch" -> forecast?.status == ForecastStatus.WATCH
            "Good" -> forecast?.status == ForecastStatus.GOOD
            "Displays" -> product.category == ProductCategory.DISPLAY
            "Box meat" -> product.category == ProductCategory.BOX_MEAT
            "GGM" -> product.category == ProductCategory.GGM
            "Vendor" -> product.category == ProductCategory.VENDOR || product.vendor.orEmpty().contains("vendor", ignoreCase = true)
            "Warehouse" -> product.category == ProductCategory.WAREHOUSE || product.vendor.orEmpty().contains("warehouse", ignoreCase = true)
            else -> true
        }
        matchesSearch && matchesFilter
    }
    Screen("Products", "Search products, review forecast status, and edit setup.", onBack = onBack) {
        BigActionButton("Add Product", Icons.Default.Add) { onAddProduct() }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            label = { Text("Search products") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option ->
                FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) })
            }
        }
        if (filtered.isEmpty()) {
            SimpleCard {
                Text("No products match this view.", fontWeight = FontWeight.SemiBold)
                Text("Try All or add a product for this truck/order workflow.", color = RadarMuted)
            }
        }
        filtered.forEach { snapshot ->
            val forecast = state.forecasts.firstOrNull { it.productId == snapshot.product.id }
            SimpleCard(onClick = { onOpenProduct(snapshot.product.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductThumb(snapshot.product.category)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(snapshot.product.name, fontWeight = FontWeight.SemiBold)
                        Text("${snapshot.product.category.name.readable()} | ${snapshot.product.defaultUnit} | ${snapshot.product.vendor ?: "No vendor"}", color = RadarMuted)
                        Text("On hand: ${forecast?.currentOnHand?.clean() ?: "0"} | Recommended: ${forecast?.recommendedOrderQuantity?.clean() ?: "0"}", color = RadarMuted)
                    }
                    IconButton(onClick = { onEditProduct(snapshot.product.id) }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit product")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductEditorScreen(product: Product?, onBack: () -> Unit, onSave: (Product) -> Unit) {
    val isNew = product == null
    var name by remember(product) { mutableStateOf(product?.name ?: "") }
    var category by remember(product) { mutableStateOf(product?.category ?: ProductCategory.BOX_MEAT) }
    var vendor by remember(product) { mutableStateOf(product?.vendor ?: "") }
    var location by remember(product) { mutableStateOf(product?.storageLocation ?: "") }
    var unit by remember(product) { mutableStateOf(product?.defaultUnit ?: "boxes") }
    var caseSize by remember(product) { mutableStateOf(product?.caseSize ?: "") }
    var safetyStock by remember(product) { mutableStateOf(product?.safetyStock?.clean() ?: "1") }
    var reorderPoint by remember(product) { mutableStateOf(product?.reorderPoint?.clean() ?: "1") }
    var notes by remember(product) { mutableStateOf(product?.notes ?: "") }
    var visualIdentifiers by remember(product) { mutableStateOf(product?.visualIdentifiers ?: "") }
    Screen(if (isNew) "Add Product" else "Edit Product", "Set the product up once, then count and forecast it.", onBack = onBack) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Product name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        EnumPicker("Category", category, ProductCategory.entries) { category = it }
        OutlinedTextField(value = vendor, onValueChange = { vendor = it }, label = { Text("Vendor / warehouse") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Cooler / shelf location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OptionPicker("Unit", unit, listOf("boxes", "cases", "pounds", "eaches", "trays")) { unit = it }
        OutlinedTextField(value = caseSize, onValueChange = { caseSize = it }, label = { Text("Case size") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(value = safetyStock, onValueChange = { safetyStock = it }, label = { Text("Safety stock") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = reorderPoint, onValueChange = { reorderPoint = it }, label = { Text("Reorder point") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(
            value = visualIdentifiers,
            onValueChange = { visualIdentifiers = it },
            label = { Text("Visual ID for AI Shelf Count") },
            placeholder = { Text("e.g. Red lid = Original, Yellow lid = Lemon Pepper. Stacked 4 per shelf.") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        BigActionButton("Save Product", Icons.Default.Save) {
            if (name.isNotBlank()) {
                val now = System.currentTimeMillis()
                onSave(
                    Product(
                        id = product?.id ?: 0,
                        name = name.trim(),
                        category = category,
                        vendor = vendor.ifBlank { null },
                        storageLocation = location.ifBlank { null },
                        defaultUnit = unit,
                        caseSize = caseSize.ifBlank { null },
                        safetyStock = safetyStock.toDoubleOrNull() ?: 0.0,
                        reorderPoint = reorderPoint.toDoubleOrNull() ?: 0.0,
                        notes = notes.ifBlank { null },
                        visualIdentifiers = visualIdentifiers.ifBlank { null },
                        createdAt = product?.createdAt ?: now,
                        updatedAt = now,
                        itemNumber = product?.itemNumber,
                        upc = product?.upc,
                        department = product?.department ?: "Deli",
                        boxWeight = product?.boxWeight,
                        active = product?.active ?: true,
                        productPhotoUri = product?.productPhotoUri
                    )
                )
            }
        }
    }
}

@Composable
private fun ProductDetailScreen(snapshot: ProductSnapshot, forecast: ForecastResult?, onBack: () -> Unit, onCount: () -> Unit, onUsage: () -> Unit, onEdit: () -> Unit) {
    val product = snapshot.product
    Screen(product.name, product.category.name.readable(), onBack = onBack) {
        SimpleCard {
            Text("Overview", fontWeight = FontWeight.Bold)
            DetailLine("Vendor", product.vendor ?: "Not set")
            DetailLine("Location", product.storageLocation ?: "Not set")
            DetailLine("Unit", product.defaultUnit)
            DetailLine("Case size", product.caseSize ?: "Not set")
            DetailLine("Safety stock", "${product.safetyStock.clean()} ${product.defaultUnit}")
            DetailLine("Reorder point", "${product.reorderPoint.clean()} ${product.defaultUnit}")
            DetailLine("Truck", snapshot.truck?.name ?: "Assign truck")
        }
        forecast?.let {
            ForecastCard(snapshot, it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onCount, modifier = Modifier.weight(1f)) { Text("Update Count") }
            OutlinedButton(onClick = onUsage, modifier = Modifier.weight(1f)) { Text("Add Usage") }
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Edit Product")
        }
        listOf("Open order suggestions", "Recent deliveries", "Display links", "Recipe/production links", "Notes").forEach {
            SimpleCard { Text(it, fontWeight = FontWeight.SemiBold); Text(product.notes ?: "No saved notes yet.", color = RadarMuted) }
        }
    }
}

@Composable
private fun Screen(title: String, subtitle: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RadarCharcoal),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = if (title == "Order Radar") RadarLime else RadarText)
                    Text(subtitle, color = RadarMuted, style = MaterialTheme.typography.bodyMedium)
                }
                if (title == "Order Radar") Icon(Icons.Default.Notifications, contentDescription = null, tint = RadarOrange)
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    }
}

@Composable
private fun ProductCard(snapshot: ProductSnapshot, forecast: ForecastResult, action: String, onOpen: (Long) -> Unit) {
    SimpleCard(onClick = { onOpen(snapshot.product.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductThumb(snapshot.product.category)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(snapshot.product.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${snapshot.product.category.name.readable()} | On hand: ${forecast.currentOnHand.clean()} ${snapshot.product.defaultUnit}", color = RadarMuted)
                Text("Avg use: ${forecast.averageDailyUsage.clean()}/day | Next truck: ${if (forecast.daysUntilNextTruck > 0) "${forecast.daysUntilNextTruck} days" else "assign"}", color = RadarMuted)
            }
            StatusChip(action, forecast.status)
        }
    }
}

@Composable
private fun ForecastCard(snapshot: ProductSnapshot, forecast: ForecastResult) {
    SimpleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(snapshot.product.name, fontWeight = FontWeight.Bold)
                Text("On hand: ${forecast.currentOnHand.clean()} ${snapshot.product.defaultUnit}", color = RadarMuted)
                Text("Need until truck: ${forecast.neededUntilNextTruck.clean()}", color = RadarMuted)
                Text("Recommended: ${forecast.recommendedOrderQuantity.clean()} ${snapshot.product.defaultUnit}", color = if (forecast.recommendedOrderQuantity > 0) RadarOrange else RadarLime)
                Text(forecast.reason, color = RadarMuted)
            }
            StatusChip(forecast.status.name.readable(), forecast.status)
        }
    }
}

@Composable
private fun DisplayCard(display: DisplayPlan, productName: String, forecast: DisplayForecast, onOpen: () -> Unit) {
    SimpleCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(display.name, fontWeight = FontWeight.Bold)
                Text(productName, color = RadarMuted)
                Text("Current: ${display.currentQuantity.clean()} boxes | ${forecast.daysLeft} days left", color = RadarMuted)
                Text(forecast.explanation, color = RadarMuted)
            }
            StatusChip(if (forecast.reorderNeeded) "Reorder" else if (forecast.overstockRisk) "Overstock" else "Good", if (forecast.reorderNeeded) ForecastStatus.ORDER_NEEDED else ForecastStatus.GOOD)
        }
    }
}

@Composable
private fun DeliveryVarianceCard(productName: String, variance: VarianceLog) {
    SimpleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(productName, fontWeight = FontWeight.Bold)
                Text("Ordered: ${variance.orderedQuantity.clean()} | Received: ${variance.receivedQuantity.clean()}", color = RadarMuted)
                Text("Variance: ${variance.difference.clean()}", color = if (variance.difference == 0.0) RadarLime else RadarOrange)
                Text(variance.reason, color = RadarMuted)
            }
            StatusChip(if (variance.difference > 0) "Over" else "Short", ForecastStatus.ORDER_NEEDED)
        }
    }
}

@Composable
private fun TruckCard(truck: TruckSchedule) {
    SimpleCard {
        Text(truck.name, fontWeight = FontWeight.Bold)
        Text(truck.deliveryDays, color = RadarText)
        Text(truck.orderCutoffDays, color = RadarMuted)
        Text("Lead time: ${truck.leadTimeDays} days", color = RadarMuted)
    }
}

@Composable
private fun ReportCard(title: String) {
    SimpleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, contentDescription = null, tint = RadarMuted)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Ready as copyable plain text", color = RadarMuted)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = RadarMuted)
        }
    }
}

@Composable
private fun PhotoCaptureCard(onPhotoReview: () -> Unit, onVisionCount: () -> Unit) {
    SimpleCard {
        Text("Photo / OCR Assist", fontWeight = FontWeight.Bold)
        Text("Scan a label or order sheet, or enter manually. User confirmation is required before saving.", color = RadarMuted)
        OutlinedButton(onClick = onPhotoReview, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.TextSnippet, null); Spacer(Modifier.width(6.dp)); Text("Scan Label") }
        Spacer(Modifier.height(4.dp))
        Text("AI Shelf Count", fontWeight = FontWeight.Bold)
        Text("Photograph a shelf or cooler and let AI estimate how many of each product are visible. Needs an API key in Settings.", color = RadarMuted)
        Button(onClick = onVisionCount, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("AI Shelf Count")
        }
    }
}

@Composable
private fun SettingsSection(
    settings: AppSettings,
    onSetVisionProvider: (VisionProvider) -> Unit,
    onSaveAnthropicVisionSettings: (String, String) -> Unit,
    onSaveOpenAiVisionSettings: (String, String) -> Unit
) {
    SectionHeader("Settings / Privacy")
    SimpleCard {
        Text("Smithware Studios", fontWeight = FontWeight.Bold)
        Text("Default safety stock: 1", color = RadarMuted)
        Text("Movement average window: 14 days", color = RadarMuted)
        Text("Order Radar stores product counts, order drafts, delivery notes, photos, and usage history locally on this device. Smithware Studios does not receive or upload your workplace data in this MVP.", color = RadarMuted)
        Spacer(Modifier.height(6.dp))
        Text("Order Radar is a personal organization and forecasting tool. It does not replace your workplace's official inventory, ordering, food safety, or compliance systems. Always follow your workplace's official procedures.", color = RadarOrange)
    }
    SimpleCard {
        val provider = settings.visionProvider
        val savedKeyForProvider = if (provider == VisionProvider.OPENAI) settings.openAiVisionApiKey else settings.anthropicVisionApiKey
        val savedModelForProvider = if (provider == VisionProvider.OPENAI) settings.openAiVisionModel else settings.anthropicVisionModel
        // Keyed on the provider too, not just the saved value -- switching providers must show
        // that provider's own saved key/model instead of leftover draft text from the other.
        var apiKey by remember(provider, savedKeyForProvider) { mutableStateOf(savedKeyForProvider) }
        var model by remember(provider, savedModelForProvider) { mutableStateOf(savedModelForProvider) }

        Text("AI Shelf Count Setup", fontWeight = FontWeight.Bold)
        Text("Optional. Add your own OpenAI or Anthropic API key to enable AI Shelf Count. The key is stored only on this device and is used to send photos you capture directly to that provider's API for item counting. Standard API usage charges apply to your account. Leave blank to use manual or label-scan counting only.", color = RadarMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = provider == VisionProvider.OPENAI, onClick = { onSetVisionProvider(VisionProvider.OPENAI) }, label = { Text("OpenAI") })
            FilterChip(selected = provider == VisionProvider.ANTHROPIC, onClick = { onSetVisionProvider(VisionProvider.ANTHROPIC) }, label = { Text("Anthropic (Claude)") })
        }
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (provider == VisionProvider.OPENAI) "OpenAI API key" else "Anthropic API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        BigActionButton("Save AI Settings", Icons.Default.Save) {
            if (provider == VisionProvider.OPENAI) onSaveOpenAiVisionSettings(apiKey, model)
            else onSaveAnthropicVisionSettings(apiKey, model)
        }
        if (provider == VisionProvider.OPENAI && settings.anthropicVisionApiKey.isNotBlank() ||
            provider == VisionProvider.ANTHROPIC && settings.openAiVisionApiKey.isNotBlank()
        ) {
            Text(
                "A key is also saved for the other provider. Switch above to use it instead.",
                style = MaterialTheme.typography.bodySmall,
                color = RadarMuted
            )
        }
    }
}

@Composable
private fun SimpleCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = RadarCard)
    if (onClick == null) {
        Card(shape = RoundedCornerShape(8.dp), colors = colors, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    } else {
        Card(onClick = onClick, shape = RoundedCornerShape(8.dp), colors = colors, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun EmptyState(title: String, detail: String) {
    SimpleCard {
        Text(title, fontWeight = FontWeight.Bold)
        Text(detail, color = RadarMuted)
    }
}

@Composable
private fun StatusChip(label: String, status: ForecastStatus) {
    val color = when (status) {
        ForecastStatus.GOOD -> RadarLime
        ForecastStatus.WATCH -> RadarOrange
        ForecastStatus.ORDER_NEEDED, ForecastStatus.CRITICAL -> RadarRed
        ForecastStatus.OVERSTOCK_RISK -> Color(0xFFC9CED1)
        ForecastStatus.UNKNOWN -> RadarMuted
    }
    Surface(color = color, contentColor = if (status == ForecastStatus.ORDER_NEEDED || status == ForecastStatus.CRITICAL) Color.White else Color(0xFF12140F), shape = RoundedCornerShape(6.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProductThumb(category: ProductCategory) {
    val icon = when (category) {
        ProductCategory.DISPLAY -> Icons.Default.Storefront
        ProductCategory.GGM, ProductCategory.PREPARED_FOOD -> Icons.Default.Restaurant
        ProductCategory.BOX_MEAT, ProductCategory.DELI_MEAT -> Icons.Default.Inventory2
        else -> Icons.Default.Category
    }
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF34382F)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = RadarOrange)
    }
}

@Composable
private fun BigActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal), shape = RoundedCornerShape(8.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WarningBanner(text: String) {
    Surface(color = Color(0xFF3A2A0A), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = RadarOrange, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProductPicker(products: List<Product>, selectedId: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = products.firstOrNull { it.id == selectedId } ?: products.firstOrNull()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: "Select Product", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            products.forEach { product ->
                DropdownMenuItem(text = { Text(product.name) }, onClick = { onSelect(product.id); expanded = false })
            }
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumPicker(label: String, selected: T, options: List<T>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, color = RadarMuted, style = MaterialTheme.typography.labelSmall)
                Text(selected.name.readable())
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.name.readable()) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun OptionPicker(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, color = RadarMuted, style = MaterialTheme.typography.labelSmall)
                Text(selected)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

@Composable
private fun NumberEntry(label: String, value: String, suffix: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
private fun SegmentedButtonRow(selected: String, options: List<String>, onSelected: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            FilterChip(selected = selected == option, onClick = { onSelected(option) }, label = { Text(option, maxLines = 1) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = RadarMuted, modifier = Modifier.weight(1f))
        Text(value, color = RadarText, modifier = Modifier.weight(1f))
    }
}

private fun OrderRadarUiState.snapshot(productId: Long) = snapshots.firstOrNull { it.product.id == productId }
private fun OrderRadarUiState.product(productId: Long) = snapshots.firstOrNull { it.product.id == productId }?.product

private fun String.readable(): String = lowercase().split("_").joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
private fun Long.shortDate(): String = SimpleDateFormat("MMM d", Locale.US).format(Date(this))

private fun buildReport(state: OrderRadarUiState): String {
    val orderLines = state.forecasts.filter { it.recommendedOrderQuantity > 0.0 }.joinToString("\n") { forecast ->
        val product = state.product(forecast.productId)
        "- ${product?.name ?: "Product"}: Order ${forecast.recommendedOrderQuantity.clean()} ${product?.defaultUnit ?: "units"}\n  Reason: ${forecast.reason}"
    }
    val watch = state.forecasts.filter { it.status == ForecastStatus.WATCH }.joinToString("\n") { forecast ->
        "- ${state.product(forecast.productId)?.name ?: "Product"}: ${forecast.daysOfSupply.clean()} days remaining."
    }
    val variances = state.variances.joinToString("\n") {
        "- ${state.product(it.productId)?.name ?: "Product"}: Expected ${it.orderedQuantity.clean()}, received ${it.receivedQuantity.clean()}, variance ${it.difference.clean()}."
    }
    return """
        Order Radar Summary
        Truck: Box Meat Truck
        Next Delivery: Thursday

        Order Recommended:
        ${orderLines.ifBlank { "- No order recommended right now." }}

        Watch:
        ${watch.ifBlank { "- No watch items right now." }}

        Delivery Variance:
        ${variances.ifBlank { "- No delivery variance logged." }}
    """.trimIndent()
}

private fun buildOrderDraftSummary(state: OrderRadarUiState, order: OrderDraft, lines: List<OrderLine>): String {
    val body = lines.joinToString("\n") { line ->
        val product = state.product(line.productId)
        "- ${product?.name ?: "Product"}: ${line.userQuantity.clean()} ${line.unit}\n  Reason: ${line.reason}"
    }
    return """
        ${order.title}
        Expected delivery: ${order.expectedDeliveryDate.shortDate()}

        ${body.ifBlank { "- No products in this draft." }}

        Notes:
        ${order.notes ?: "Confirm in workplace ordering system."}
    """.trimIndent()
}
