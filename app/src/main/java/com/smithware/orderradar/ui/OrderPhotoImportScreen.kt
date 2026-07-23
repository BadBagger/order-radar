package com.smithware.orderradar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smithware.orderradar.data.OrderImportResult
import com.smithware.orderradar.data.OrderLineSuggestion
import com.smithware.orderradar.data.Product
import com.smithware.orderradar.data.TruckSchedule
import com.smithware.orderradar.data.VisionCountClient
import com.smithware.orderradar.data.VisionProvider
import com.smithware.orderradar.ui.theme.RadarCard
import com.smithware.orderradar.ui.theme.RadarCharcoal
import com.smithware.orderradar.ui.theme.RadarLime
import com.smithware.orderradar.ui.theme.RadarMuted
import com.smithware.orderradar.ui.theme.RadarOrange
import com.smithware.orderradar.ui.theme.RadarText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class OrderScanRow(
    val suggestion: OrderLineSuggestion,
    val matchedProductId: Long?,
    val quantity: String,
    val checked: Boolean = true
)

@Composable
fun OrderPhotoImportScreen(
    products: List<Product>,
    trucks: List<TruckSchedule>,
    provider: VisionProvider,
    apiKey: String,
    model: String,
    onImportOrder: (TruckSchedule, List<OrderImportRow>, String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()
    var selectedTruckId by remember(trucks) { mutableStateOf(trucks.firstOrNull()?.id ?: 0L) }
    val selectedTruck = trucks.firstOrNull { it.id == selectedTruckId } ?: trucks.firstOrNull()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Photograph a box meat / grocery order form, order guide, or handwritten order sheet, or choose one from your gallery.") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<OrderScanRow>>(emptyList()) }

    // Shared by both the camera-capture and gallery paths. OCR runs first purely as extra
    // context handed to the AI (matching label text is more reliable off raw OCR than a photo
    // alone) -- the AI still reads the actual image, so it can find lines OCR alone would miss.
    fun runOrderImport(file: File, ocrText: String) {
        capturedPath = file.absolutePath
        if (apiKey.isBlank()) {
            status = "No API key set. Add rows manually below or set a key in Settings."
            return
        }
        isLoading = true
        status = "Asking AI to read the order form..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                VisionCountClient.extractOrderForm(provider, apiKey, model, file, products.map { it.name }, ocrText)
            }
            isLoading = false
            when (result) {
                is OrderImportResult.Success -> {
                    rows = result.items.map { suggestion ->
                        val match = bestProductMatch(suggestion.itemName, products)
                        OrderScanRow(
                            suggestion = suggestion,
                            matchedProductId = match?.id,
                            quantity = suggestion.orderedQuantity.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                        )
                    }
                    status = "AI found ${rows.size} order line(s). Confirm product match and quantity for each -- low-confidence rows are flagged for a quick check."
                }
                is OrderImportResult.Failure -> {
                    status = result.message
                }
            }
        }
    }

    fun readTextThenImport(file: File) {
        status = "Reading text on the form..."
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            recognizer.process(image)
                .addOnSuccessListener { result -> runOrderImport(file, result.text) }
                .addOnFailureListener { runOrderImport(file, "") }
        } catch (e: Exception) {
            runOrderImport(file, "")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = createPhotoFile(context.filesDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            readTextThenImport(file)
        } catch (e: Exception) {
            status = "Could not read that photo: ${e.message ?: "unknown error"}"
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(RadarCharcoal),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = RadarText) }
                Column(Modifier.weight(1f)) {
                    Text("Import Order Photo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = RadarText)
                    Text("AI reads the form. Confirm every row before saving the draft.", color = RadarMuted)
                }
            }
        }
        item {
            val providerLabel = if (provider == VisionProvider.OPENAI) "OpenAI's" else "Anthropic's"
            WarningPanel("This sends the photo you capture to $providerLabel API and only creates an Order Radar draft. It never submits an official workplace order.")
        }
        if (apiKey.isBlank()) {
            item {
                AssistCard {
                    Text("AI order import needs an API key", fontWeight = FontWeight.Bold)
                    Text("Add your OpenAI or Anthropic API key in Settings to enable AI order import. You can still add rows manually without it.", color = RadarMuted)
                    Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
                        Text("Open Settings")
                    }
                }
            }
        }
        item {
            TruckPicker(trucks, selectedTruckId) { selectedTruckId = it }
        }
        item {
            if (!hasPermission) {
                AssistCard {
                    Text("Camera permission needed", fontWeight = FontWeight.Bold)
                    Text("Order Radar uses the camera locally to read order sheets.", color = RadarMuted)
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
                        Text("Allow Camera")
                    }
                }
            } else if (capturedPath == null) {
                val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
                LaunchedEffect(previewView) {
                    try {
                        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        imageCapture = capture
                        cameraError = null
                    } catch (_: Exception) {
                        cameraError = "Camera preview is unavailable. Manual entry is still available."
                    }
                }
                if (cameraError == null) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp)))
                } else {
                    AssistCard { Text(cameraError.orEmpty(), color = RadarOrange, fontWeight = FontWeight.SemiBold) }
                }
            } else {
                CapturedImage(capturedPath)
            }
        }
        item {
            if (capturedPath == null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val file = createPhotoFile(context.filesDir)
                            imageCapture?.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                mainExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        readTextThenImport(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        status = "Photo capture failed. Try again."
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Order Photo", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose from Gallery", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        capturedPath = null
                        rows = emptyList()
                        status = "Photograph a box meat / grocery order form, order guide, or handwritten order sheet."
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retake")
                    }
                    Button(
                        onClick = {
                            val truck = selectedTruck ?: return@Button
                            val confirmed = rows.filter { it.checked }.mapNotNull { row ->
                                val quantity = row.quantity.toDoubleOrNull() ?: return@mapNotNull null
                                val matchedProduct = row.matchedProductId?.let { id -> products.firstOrNull { it.id == id } }
                                OrderImportRow(row.suggestion, matchedProduct, quantity)
                            }
                            if (confirmed.isNotEmpty()) {
                                onImportOrder(truck, confirmed, "Photo: ${capturedPath ?: "order photo"}")
                                onBack()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save Draft")
                    }
                }
            }
        }
        item {
            AssistCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RadarLime)
                    Spacer(Modifier.width(8.dp))
                    Text("AI status", fontWeight = FontWeight.Bold)
                }
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = RadarLime)
                }
                Text(status, color = RadarMuted)
            }
        }
        if (rows.isNotEmpty()) {
            item {
                Text("Order Lines", color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEachIndexed { index, row ->
                        OrderScanRowView(products, row) { changed ->
                            rows = rows.toMutableList().also { it[index] = changed }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TruckPicker(trucks: List<TruckSchedule>, selectedId: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = trucks.firstOrNull { it.id == selectedId } ?: trucks.firstOrNull()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Truck / delivery", color = RadarMuted, style = MaterialTheme.typography.labelSmall)
                Text(selected?.name ?: "No truck schedules")
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            trucks.forEach { truck ->
                DropdownMenuItem(text = { Text(truck.name) }, onClick = { onSelect(truck.id); expanded = false })
            }
        }
    }
}

@Composable
private fun OrderScanRowView(products: List<Product>, row: OrderScanRow, onChange: (OrderScanRow) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matched = row.matchedProductId?.let { id -> products.firstOrNull { it.id == id } }
    val confidenceColor = when {
        row.suggestion.confidencePercent >= 80 -> RadarLime
        row.suggestion.confidencePercent >= 50 -> Color(0xFFE0A526)
        else -> RadarOrange
    }
    AssistCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.checked, onCheckedChange = { onChange(row.copy(checked = it)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI read: \"${row.suggestion.itemName}\"",
                        color = RadarMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(color = confidenceColor.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            "${row.suggestion.confidencePercent}%",
                            color = confidenceColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(matched?.name ?: "New product: ${row.suggestion.itemName}", modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("New product: ${row.suggestion.itemName}") }, onClick = { onChange(row.copy(matchedProductId = null)); expanded = false })
                        products.forEach { product ->
                            DropdownMenuItem(text = { Text(product.name) }, onClick = { onChange(row.copy(matchedProductId = product.id)); expanded = false })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalIconButton(onClick = {
                        val current = row.quantity.toDoubleOrNull() ?: 0.0
                        onChange(row.copy(quantity = (current - 1.0).coerceAtLeast(0.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }))
                    }) { Icon(Icons.Default.Remove, contentDescription = "Decrease") }
                    OutlinedTextField(
                        value = row.quantity,
                        onValueChange = { onChange(row.copy(quantity = it)) },
                        label = { Text("Order quantity") },
                        suffix = { Text(matched?.defaultUnit ?: row.suggestion.unit) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = {
                        val current = row.quantity.toDoubleOrNull() ?: 0.0
                        onChange(row.copy(quantity = (current + 1.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }))
                    }) { Icon(Icons.Default.Add, contentDescription = "Increase") }
                }
                if (row.suggestion.confidencePercent < 80) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onChange(row.copy(checked = false)) }, label = { Text("Not ordering this") })
                        AssistChip(onClick = { onChange(row.copy(checked = true)) }, label = { Text("Looks right") })
                    }
                }
                if (row.suggestion.notes.isNotBlank()) {
                    Text(row.suggestion.notes, color = RadarMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun CapturedImage(path: String?) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it) } }
    Box(
        modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(8.dp)).background(RadarCard),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Captured order photo", modifier = Modifier.fillMaxSize())
        } else {
            Text("Captured order photo", color = RadarMuted)
        }
    }
}

@Composable
private fun AssistCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = RadarCard), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun WarningPanel(text: String) {
    Surface(color = androidx.compose.ui.graphics.Color(0xFF3A2A0A), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = RadarOrange, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.SemiBold)
    }
}

private fun createPhotoFile(filesDir: File): File {
    val dir = File(filesDir, "order-radar-order-photos").apply { mkdirs() }
    return File(dir, "order-photo-${System.currentTimeMillis()}.jpg")
}

// Deliberately conservative, matching PhotoVisionCountScreen's bestProductMatch: a single shared
// word is not enough to confidently pre-select a product -- a wrong-but-plausible match silently
// creates the wrong order line, which is worse than proposing a new product.
private fun bestProductMatch(detectedName: String, products: List<Product>): Product? {
    val normalized = detectedName.lowercase().trim()
    return products
        .map { product ->
            val productName = product.name.lowercase()
            val words = productName.split(" ").filter { it.length >= 3 }
            val fullContainment = normalized.contains(productName) || productName.contains(normalized)
            val allWordsPresent = words.isNotEmpty() && words.all { normalized.contains(it) }
            val score = if (fullContainment) 2 else if (allWordsPresent) 1 else 0
            product to score
        }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}
