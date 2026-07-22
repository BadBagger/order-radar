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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
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
import com.smithware.orderradar.data.Product
import com.smithware.orderradar.data.VisionCountClient
import com.smithware.orderradar.data.VisionCountResult
import com.smithware.orderradar.data.VisionCountSuggestion
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

private data class VisionSuggestionRow(
    val suggestion: VisionCountSuggestion,
    val matchedProductId: Long?,
    val quantity: String,
    val checked: Boolean = true
)

@Composable
fun PhotoVisionCountScreen(
    products: List<Product>,
    provider: VisionProvider,
    apiKey: String,
    model: String,
    onSaveCounts: (List<VisionCountRow>, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturedPath by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Take a wide, well-lit photo of the shelf or cooler you want to count, or choose one from your gallery.") }
    var isLoading by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<VisionSuggestionRow>>(emptyList()) }

    // Shared by both the camera-capture and gallery paths, so a picked photo gets the exact
    // same AI-count treatment as a freshly-captured one.
    fun runVisionCount(file: File) {
        capturedPath = file.absolutePath
        if (apiKey.isBlank()) {
            status = "No API key set. Add rows manually below or set a key in Settings."
            return
        }
        isLoading = true
        status = "Asking AI to count what's visible..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                VisionCountClient.countShelfPhoto(provider, apiKey, model, file, products.map { it.name })
            }
            isLoading = false
            when (result) {
                is VisionCountResult.Success -> {
                    rows = result.items.map { suggestion ->
                        val match = bestProductMatch(suggestion.itemName, products)
                        VisionSuggestionRow(
                            suggestion = suggestion,
                            matchedProductId = match?.id,
                            quantity = suggestion.estimatedQuantity.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
                        )
                    }
                    status = "AI found ${rows.size} item(s). Confirm product match and quantity for each."
                }
                is VisionCountResult.Failure -> {
                    status = result.message
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val file = createPhotoFile(context.filesDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            runVisionCount(file)
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
                    Text("AI Shelf Count", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = RadarText)
                    Text("AI estimates what's visible in the photo. Confirm every row before saving.", color = RadarMuted)
                }
            }
        }
        item {
            val providerLabel = if (provider == VisionProvider.OPENAI) "OpenAI's" else "Anthropic's"
            WarningPanel("This sends the photo you capture to $providerLabel API for counting. Nothing is saved until you review and confirm each row.")
        }
        if (apiKey.isBlank()) {
            item {
                AssistCard {
                    Text("AI counting needs an API key", fontWeight = FontWeight.Bold)
                    Text("Add your Anthropic API key in Settings to enable AI shelf counting. You can still use manual or label-scan counting without it.", color = RadarMuted)
                    Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
                        Text("Open Settings")
                    }
                }
            }
        }
        item {
            if (!hasPermission) {
                AssistCard {
                    Text("Camera permission needed", fontWeight = FontWeight.Bold)
                    Text("Order Radar uses the camera locally to capture the shelf photo.", color = RadarMuted)
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
                                        runVisionCount(file)
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
                        Text("Capture Shelf Photo", fontWeight = FontWeight.Bold)
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
                        status = "Take a wide, well-lit photo of the shelf or cooler you want to count."
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retake")
                    }
                    Button(
                        onClick = {
                            val confirmed = rows.filter { it.checked }.mapNotNull { row ->
                                val quantity = row.quantity.toDoubleOrNull() ?: return@mapNotNull null
                                val matchedProduct = row.matchedProductId?.let { id -> products.firstOrNull { it.id == id } }
                                VisionCountRow(row.suggestion.copy(estimatedQuantity = quantity), matchedProduct)
                            }
                            if (confirmed.isNotEmpty()) {
                                onSaveCounts(confirmed, capturedPath)
                                onBack()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save Counts")
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
                Text("Detected Items", color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEachIndexed { index, row ->
                        VisionRow(products, row) { changed ->
                            rows = rows.toMutableList().also { it[index] = changed }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisionRow(products: List<Product>, row: VisionSuggestionRow, onChange: (VisionSuggestionRow) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val matched = row.matchedProductId?.let { id -> products.firstOrNull { it.id == id } }
    AssistCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.checked, onCheckedChange = { onChange(row.copy(checked = it)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI detected: \"${row.suggestion.itemName}\" (${row.suggestion.confidence} confidence)", color = RadarMuted, style = MaterialTheme.typography.labelSmall)
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
                OutlinedTextField(
                    value = row.quantity,
                    onValueChange = { onChange(row.copy(quantity = it)) },
                    label = { Text("Confirmed count") },
                    suffix = { Text(matched?.defaultUnit ?: row.suggestion.unit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Captured shelf photo", modifier = Modifier.fillMaxSize())
        } else {
            Text("Captured shelf photo", color = RadarMuted)
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
    val dir = File(filesDir, "order-radar-vision-photos").apply { mkdirs() }
    return File(dir, "vision-photo-${System.currentTimeMillis()}.jpg")
}

private fun bestProductMatch(detectedName: String, products: List<Product>): Product? {
    val normalized = detectedName.lowercase()
    return products
        .map { product ->
            val words = product.name.lowercase().split(" ").filter { it.length >= 3 }
            val score = words.count { normalized.contains(it) } + if (normalized.contains(product.name.lowercase()) || product.name.lowercase().contains(normalized)) 4 else 0
            product to score
        }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}
