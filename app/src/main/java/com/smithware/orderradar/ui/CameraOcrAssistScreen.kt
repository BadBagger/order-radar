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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.smithware.orderradar.ui.theme.RadarCard
import com.smithware.orderradar.ui.theme.RadarCharcoal
import com.smithware.orderradar.ui.theme.RadarLime
import com.smithware.orderradar.ui.theme.RadarMuted
import com.smithware.orderradar.ui.theme.RadarOrange
import com.smithware.orderradar.ui.theme.RadarText
import java.io.File

private data class CountSuggestion(val productId: Long, val quantity: String, val checked: Boolean = true)

@Composable
fun CameraOcrAssistScreen(
    products: List<Product>,
    onSaveCount: (Product, Double, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var ocrText by remember { mutableStateOf("") }
    var ocrStatus by remember { mutableStateOf("Capture a cooler, label, order sheet, or delivery sheet.") }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var suggestions by remember(products) {
        mutableStateOf(products.take(3).mapIndexed { index, product -> CountSuggestion(product.id, (index + 1).toString()) })
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
                    Text("Photo Review", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = RadarText)
                    Text("Photo detection is only a helper. Confirm counts before saving.", color = RadarMuted)
                }
            }
        }
        item {
            WarningPanel("OCR suggestions are never saved automatically. Confirm product and quantity first.")
        }
        item {
            if (!hasPermission) {
                PermissionPanel { permissionLauncher.launch(Manifest.permission.CAMERA) }
            } else if (capturedPath == null) {
                val previewView = remember {
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                }
                LaunchedEffect(previewView) {
                    try {
                        val provider = ProcessCameraProvider.getInstance(context).get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        imageCapture = capture
                        cameraError = null
                    } catch (_: Exception) {
                        cameraError = "Camera preview is unavailable on this device. Manual entry is still available."
                    }
                }
                if (cameraError == null) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    AssistCard {
                        Text(cameraError.orEmpty(), color = RadarOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                CapturedImage(path = capturedPath)
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
                                    ocrStatus = "Reading text from photo..."
                                    runOcr(context, file, products) { text, found, status ->
                                        ocrText = text
                                        suggestions = found
                                        ocrStatus = status
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    ocrStatus = "Photo capture failed. Manual entry is still available."
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
                    Text("Capture Photo", fontWeight = FontWeight.Bold)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        capturedPath = null
                        ocrText = ""
                        ocrStatus = "Capture a cooler, label, order sheet, or delivery sheet."
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retake")
                    }
                    Button(onClick = {
                        suggestions.filter { it.checked }.forEach { suggestion ->
                            val product = products.firstOrNull { it.id == suggestion.productId }
                            val quantity = suggestion.quantity.toDoubleOrNull()
                            if (product != null && quantity != null) {
                                onSaveCount(product, quantity, "Photo/OCR assist count. Source: ${capturedPath ?: "photo"}")
                            }
                        }
                        onBack()
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save Count")
                    }
                }
            }
        }
        item {
            AssistCard {
                Text("OCR status", fontWeight = FontWeight.Bold)
                Text(ocrStatus, color = RadarMuted)
                if (ocrText.isNotBlank()) {
                    Text("Detected text", fontWeight = FontWeight.SemiBold)
                    Text(ocrText.take(600), color = RadarMuted)
                }
            }
        }
        item {
            Text("Suggested Count Rows", color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                suggestions.forEachIndexed { index, suggestion ->
                    SuggestionRow(
                        products = products,
                        suggestion = suggestion,
                        onChange = { changed ->
                            suggestions = suggestions.toMutableList().also { it[index] = changed }
                        }
                    )
                }
                OutlinedButton(
                    onClick = {
                        products.firstOrNull()?.let { product ->
                            suggestions = suggestions + CountSuggestion(product.id, "1", checked = true)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Manual Row")
                }
            }
        }
    }
}

@Composable
private fun PermissionPanel(onRequest: () -> Unit) {
    AssistCard {
        Text("Camera permission needed", fontWeight = FontWeight.Bold)
        Text("Order Radar only uses the camera for local photo attachments and OCR assist.", color = RadarMuted)
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
            Text("Allow Camera")
        }
    }
}

@Composable
private fun CapturedImage(path: String?) {
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it) } }
    Box(
        modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(8.dp)).background(RadarCard),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Captured product photo", modifier = Modifier.fillMaxSize())
        } else {
            Text("Captured photo", color = RadarMuted)
        }
    }
}

@Composable
private fun SuggestionRow(products: List<Product>, suggestion: CountSuggestion, onChange: (CountSuggestion) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = products.firstOrNull { it.id == suggestion.productId } ?: products.firstOrNull()
    AssistCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = suggestion.checked, onCheckedChange = { onChange(suggestion.copy(checked = it)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.name ?: "Select Product", modifier = Modifier.weight(1f))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        products.forEach { product ->
                            DropdownMenuItem(
                                text = { Text(product.name) },
                                onClick = {
                                    onChange(suggestion.copy(productId = product.id))
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = suggestion.quantity,
                    onValueChange = { onChange(suggestion.copy(quantity = it)) },
                    label = { Text("Confirmed count") },
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
    val dir = File(filesDir, "order-radar-photos").apply { mkdirs() }
    return File(dir, "photo-${System.currentTimeMillis()}.jpg")
}

private fun runOcr(context: Context, file: File, products: List<Product>, onResult: (String, List<CountSuggestion>, String) -> Unit) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = InputImage.fromFilePath(context, Uri.fromFile(file))
    recognizer.process(image)
        .addOnSuccessListener { result ->
            val text = result.text
            val matches = matchProducts(text, products)
            val quantities = findQuantities(text)
            val suggestions = matches.ifEmpty { products.take(3) }.mapIndexed { index, product ->
                CountSuggestion(product.id, quantities.getOrNull(index) ?: "1", checked = true)
            }
            onResult(text, suggestions, if (text.isBlank()) "No text found. Enter counts manually." else "OCR complete. Confirm every row before saving.")
        }
        .addOnFailureListener {
            onResult("", products.take(3).map { CountSuggestion(it.id, "1") }, "OCR failed. Manual count rows are ready.")
        }
}

private fun matchProducts(text: String, products: List<Product>): List<Product> {
    val normalized = text.lowercase()
    return products.filter { product ->
        val words = product.name.lowercase().split(" ").filter { it.length >= 4 }
        product.name.lowercase() in normalized || words.any { it in normalized }
    }.take(5)
}

private fun findQuantities(text: String): List<String> {
    val regex = Regex("""\b(\d+(?:\.\d+)?)\s*(boxes?|cases?|lbs?|pounds?|eaches?|trays?)?\b""", RegexOption.IGNORE_CASE)
    return regex.findAll(text).map { it.groupValues[1] }.take(5).toList()
}
