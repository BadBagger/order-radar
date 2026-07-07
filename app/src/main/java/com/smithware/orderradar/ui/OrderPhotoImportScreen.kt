package com.smithware.orderradar.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.smithware.orderradar.data.Product
import com.smithware.orderradar.data.TruckSchedule
import com.smithware.orderradar.ui.theme.RadarCard
import com.smithware.orderradar.ui.theme.RadarCharcoal
import com.smithware.orderradar.ui.theme.RadarLime
import com.smithware.orderradar.ui.theme.RadarMuted
import com.smithware.orderradar.ui.theme.RadarOrange
import com.smithware.orderradar.ui.theme.RadarText
import java.io.File

private data class OrderScanLine(
    val productId: Long,
    val quantity: String,
    val checked: Boolean = true
)

@Composable
fun OrderPhotoImportScreen(
    products: List<Product>,
    trucks: List<TruckSchedule>,
    onSaveOrder: (TruckSchedule, List<Pair<Product, Double>>, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
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
    var ocrText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Scan an order sheet, handwritten order list, or delivery/order printout.") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var lines by remember(products) {
        mutableStateOf(products.take(3).map { OrderScanLine(it.id, "1") })
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
                    Text("OCR can suggest order lines. Confirm before saving the draft.", color = RadarMuted)
                }
            }
        }
        item {
            WarningPanel("This only creates an Order Radar draft. It never submits an official workplace order.")
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
                        val provider = ProcessCameraProvider.getInstance(context).get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        imageCapture = capture
                        cameraError = null
                    } catch (_: Exception) {
                        cameraError = "Camera preview is unavailable. Add manual order rows below."
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
                Button(
                    onClick = {
                        val file = createPhotoFile(context.filesDir)
                        imageCapture?.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            mainExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    capturedPath = file.absolutePath
                                    status = "Reading order text..."
                                    runOrderOcr(context, file, products) { text, suggestions, message ->
                                        ocrText = text
                                        lines = suggestions
                                        status = message
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    status = "Photo capture failed. Manual order rows are still available."
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
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        capturedPath = null
                        ocrText = ""
                        status = "Scan an order sheet, handwritten order list, or delivery/order printout."
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retake")
                    }
                    Button(
                        onClick = {
                            val truck = selectedTruck ?: return@Button
                            val confirmed = lines.filter { it.checked }.mapNotNull { line ->
                                val product = products.firstOrNull { it.id == line.productId }
                                val quantity = line.quantity.toDoubleOrNull()
                                if (product != null && quantity != null && quantity > 0.0) product to quantity else null
                            }
                            if (confirmed.isNotEmpty()) {
                                onSaveOrder(truck, confirmed, "Photo: ${capturedPath ?: "order photo"}")
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
                Text("OCR status", fontWeight = FontWeight.Bold)
                Text(status, color = RadarMuted)
                if (ocrText.isNotBlank()) {
                    Text("Detected text", fontWeight = FontWeight.SemiBold)
                    Text(ocrText.take(700), color = RadarMuted)
                }
            }
        }
        item {
            Text("Confirmed Order Lines", color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lines.forEachIndexed { index, line ->
                    OrderScanLineRow(products, line) { changed ->
                        lines = lines.toMutableList().also { it[index] = changed }
                    }
                }
                OutlinedButton(
                    onClick = { products.firstOrNull()?.let { lines = lines + OrderScanLine(it.id, "1") } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Manual Order Row")
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
private fun OrderScanLineRow(products: List<Product>, line: OrderScanLine, onChange: (OrderScanLine) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = products.firstOrNull { it.id == line.productId } ?: products.firstOrNull()
    AssistCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = line.checked, onCheckedChange = { onChange(line.copy(checked = it)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.name ?: "Select product", modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        products.forEach { product ->
                            DropdownMenuItem(text = { Text(product.name) }, onClick = { onChange(line.copy(productId = product.id)); expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = line.quantity,
                    onValueChange = { onChange(line.copy(quantity = it)) },
                    label = { Text("Order quantity") },
                    suffix = { Text(selected?.defaultUnit ?: "units") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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

private fun runOrderOcr(context: Context, file: File, products: List<Product>, onResult: (String, List<OrderScanLine>, String) -> Unit) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = InputImage.fromFilePath(context, Uri.fromFile(file))
    recognizer.process(image)
        .addOnSuccessListener { result ->
            val text = result.text
            val suggestions = parseOrderLines(text, products).ifEmpty {
                products.take(3).map { OrderScanLine(it.id, "1") }
            }
            val message = if (text.isBlank()) {
                "No order text found. Add rows manually."
            } else {
                "OCR complete. Confirm every product and quantity before saving the order draft."
            }
            onResult(text, suggestions, message)
        }
        .addOnFailureListener {
            onResult("", products.take(3).map { OrderScanLine(it.id, "1") }, "OCR failed. Manual order rows are ready.")
        }
}

private fun parseOrderLines(text: String, products: List<Product>): List<OrderScanLine> {
    val textLines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
    val used = mutableSetOf<Long>()
    val parsed = mutableListOf<OrderScanLine>()
    textLines.forEach { line ->
        val product = bestProductMatch(line, products.filterNot { it.id in used }) ?: return@forEach
        val quantity = findOrderQuantity(line) ?: findNearbyQuantity(line)
        parsed += OrderScanLine(product.id, quantity ?: "1")
        used += product.id
    }
    if (parsed.isNotEmpty()) return parsed.take(12)

    val wholeText = text.lowercase()
    products.forEach { product ->
        if (product.name.lowercase() in wholeText) {
            parsed += OrderScanLine(product.id, "1")
        }
    }
    return parsed.take(12)
}

private fun bestProductMatch(line: String, products: List<Product>): Product? {
    val normalized = line.lowercase()
    return products
        .map { product ->
            val words = product.name.lowercase().split(" ").filter { it.length >= 3 }
            val score = words.count { it in normalized } + if (product.name.lowercase() in normalized) 4 else 0
            product to score
        }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}

private fun findOrderQuantity(line: String): String? {
    val explicit = Regex("""(?:order|qty|quantity|case|cases|box|boxes|ct)\D{0,8}(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(line)
        ?.groupValues
        ?.getOrNull(1)
    if (explicit != null) return explicit
    val leading = Regex("""^\s*(\d+(?:\.\d+)?)\s+(?:x\s+)?[A-Za-z]""").find(line)?.groupValues?.getOrNull(1)
    if (leading != null) return leading
    return null
}

private fun findNearbyQuantity(line: String): String? {
    return Regex("""\b(\d+(?:\.\d+)?)\s*(boxes?|cases?|lbs?|pounds?|eaches?|trays?|ct)?\b""", RegexOption.IGNORE_CASE)
        .findAll(line)
        .map { it.groupValues[1] }
        .firstOrNull()
}
