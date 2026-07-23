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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
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
import com.smithware.orderradar.data.Product
import com.smithware.orderradar.data.ProductHistoryHint
import com.smithware.orderradar.data.ProductSnapshot
import com.smithware.orderradar.data.VisionCorrection
import com.smithware.orderradar.data.VisionCountClient
import com.smithware.orderradar.data.VisionCountResult
import com.smithware.orderradar.data.VisionCountSuggestion
import com.smithware.orderradar.data.VisionProvider
import com.smithware.orderradar.domain.MovementAverageEngine
import com.smithware.orderradar.domain.VisionBias
import com.smithware.orderradar.domain.VisionLearningEngine
import com.smithware.orderradar.domain.clean
import com.smithware.orderradar.ui.theme.RadarCard
import com.smithware.orderradar.ui.theme.RadarCharcoal
import com.smithware.orderradar.ui.theme.RadarLime
import com.smithware.orderradar.ui.theme.RadarMuted
import com.smithware.orderradar.ui.theme.RadarOrange
import com.smithware.orderradar.ui.theme.RadarText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

private data class VisionSuggestionRow(
    val suggestion: VisionCountSuggestion,
    val matchedProductId: Long?,
    val displayIdentificationConfidencePercent: Int,
    val displayCountConfidencePercent: Int,
    val quantity: String,
    val checked: Boolean = true,
    // Set only after "Compare with Ollama" runs -- a second opinion from a different model on
    // the same photos, shown so a disagreement surfaces before saving instead of after.
    val compareNote: String? = null
)

@Composable
fun PhotoVisionCountScreen(
    snapshots: List<ProductSnapshot>,
    provider: VisionProvider,
    apiKey: String,
    model: String,
    ollamaBaseUrl: String,
    ollamaModel: String,
    corrections: List<VisionCorrection>,
    onSaveCounts: (List<VisionCountRow>, String?) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()

    val products = remember(snapshots) { snapshots.map { it.product } }
    // Recent count/usage per known product, handed to the AI so it can weigh a shaky photo
    // estimate against what this product's supply actually tends to look like.
    val historyHints = remember(snapshots) {
        snapshots.map { snapshot ->
            val daysSince = snapshot.latestCount?.let { ((System.currentTimeMillis() - it.countDate) / 86_400_000L).toInt() }
            ProductHistoryHint(
                name = snapshot.product.name,
                category = snapshot.product.category.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) },
                itemNumber = snapshot.product.itemNumber,
                lastCountQuantity = snapshot.latestCount?.quantity,
                lastCountUnit = snapshot.latestCount?.unit,
                daysSinceLastCount = daysSince,
                averageDailyUsage = MovementAverageEngine.averageDailyUsage(snapshot.movements).takeIf { it > 0.0 },
                storageLocation = snapshot.product.storageLocation,
                visualIdentifiers = snapshot.product.visualIdentifiers
            )
        }
    }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // Photos captured/picked for this count, in the order they were taken -- lets a wide shelf
    // be covered by several shots instead of one, and are sent together in a single AI call.
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("Take a wide, well-lit photo of the shelf or cooler you want to count, or choose one or more from your gallery. If it doesn't fit in one frame, add more photos -- the AI reasons about them together and won't double-count overlapping edges.") }
    var isLoading by remember { mutableStateOf(false) }
    var isComparing by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf<List<VisionSuggestionRow>>(emptyList()) }

    suspend fun ocrText(file: File): String = suspendCancellableCoroutine { cont ->
        try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            recognizer.process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resumeWith(Result.success(result.text)) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.success("")) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWith(Result.success(""))
        }
    }

    // Runs once the manager taps "Run AI Count" on the whole batch of photos. OCR runs per photo
    // first purely as extra context handed to the AI alongside the images themselves.
    fun runVisionCount() {
        if (photos.isEmpty()) return
        if (apiKey.isBlank()) {
            status = "No API key set. Add rows manually below or set a key in Settings."
            return
        }
        isLoading = true
        status = if (photos.size > 1) "Reading text and asking AI to count across ${photos.size} photos..." else "Asking AI to count what's visible..."
        scope.launch {
            val files = photos.map { File(it) }
            val combinedOcr = files.mapIndexed { index, file ->
                val text = withContext(Dispatchers.IO) { ocrText(file) }
                "Photo ${index + 1}: ${text.ifBlank { "(no legible text)" }}"
            }.joinToString("\n\n")
            val result = withContext(Dispatchers.IO) {
                VisionCountClient.countShelfPhoto(provider, apiKey, model, files, products.map { it.name }, combinedOcr, historyHints)
            }
            isLoading = false
            when (result) {
                is VisionCountResult.Success -> {
                    rows = result.items.map { suggestion ->
                        val match = bestProductMatch(suggestion.itemName, products)
                        val bias = match?.let { VisionLearningEngine.biasFor(corrections, it.id) } ?: VisionBias(1.0, 1.0, 0)
                        // Bias only ever adjusts the count side -- corrections measure quantity
                        // accuracy, not whether the AI named the right product.
                        val (adjustedQuantity, adjustedCountConfidence) = VisionLearningEngine.applyBias(
                            suggestion.estimatedQuantity, suggestion.countConfidencePercent, bias
                        )
                        VisionSuggestionRow(
                            suggestion = suggestion,
                            matchedProductId = match?.id,
                            displayIdentificationConfidencePercent = suggestion.identificationConfidencePercent,
                            displayCountConfidencePercent = adjustedCountConfidence,
                            quantity = adjustedQuantity.clean()
                        )
                    }
                    status = "AI found ${rows.size} item(s). Confirm product match and quantity for each -- low-confidence rows are flagged for a quick check."
                }
                is VisionCountResult.Failure -> {
                    status = result.message
                }
            }
        }
    }

    // Optional second opinion: runs the exact same photos through the manager's own local
    // Ollama model and annotates each row instead of overwriting anything -- the primary
    // provider's result stays what gets saved, this is purely a cross-check surfaced to the
    // human before they confirm.
    fun runOllamaCompare() {
        if (photos.isEmpty() || ollamaBaseUrl.isBlank()) return
        isComparing = true
        status = "Running the same photos through Ollama for a second opinion..."
        scope.launch {
            val files = photos.map { File(it) }
            val combinedOcr = files.mapIndexed { index, file ->
                val text = withContext(Dispatchers.IO) { ocrText(file) }
                "Photo ${index + 1}: ${text.ifBlank { "(no legible text)" }}"
            }.joinToString("\n\n")
            val result = withContext(Dispatchers.IO) {
                VisionCountClient.countShelfPhotoViaOllama(ollamaBaseUrl, ollamaModel, files, products.map { it.name }, combinedOcr, historyHints)
            }
            isComparing = false
            when (result) {
                is VisionCountResult.Success -> {
                    val usedOllama = mutableSetOf<Int>()
                    val annotated = rows.map { row ->
                        val matchIndex = result.items.indexOfFirst { candidate ->
                            candidate.itemName.lowercase().let { it == row.suggestion.itemName.lowercase() || it.contains(row.suggestion.itemName.lowercase()) || row.suggestion.itemName.lowercase().contains(it) }
                        }
                        if (matchIndex == -1) {
                            row.copy(compareNote = "Ollama: didn't detect this item.")
                        } else {
                            usedOllama += matchIndex
                            val ollamaItem = result.items[matchIndex]
                            val primaryQuantity = row.quantity.toDoubleOrNull() ?: row.suggestion.estimatedQuantity
                            val agrees = kotlin.math.abs(ollamaItem.estimatedQuantity - primaryQuantity) < 0.6
                            row.copy(
                                compareNote = if (agrees) "Ollama agrees (~${ollamaItem.estimatedQuantity.clean()})."
                                else "Ollama disagrees: says ${ollamaItem.estimatedQuantity.clean()}, not ${primaryQuantity.clean()}."
                            )
                        }
                    }
                    val extras = result.items.filterIndexed { index, _ -> index !in usedOllama }.map { ollamaOnly ->
                        val match = bestProductMatch(ollamaOnly.itemName, products)
                        VisionSuggestionRow(
                            suggestion = ollamaOnly,
                            matchedProductId = match?.id,
                            displayIdentificationConfidencePercent = ollamaOnly.identificationConfidencePercent,
                            displayCountConfidencePercent = ollamaOnly.countConfidencePercent,
                            quantity = ollamaOnly.estimatedQuantity.clean(),
                            checked = false,
                            compareNote = "Ollama only: your primary provider didn't detect this. Review before including."
                        )
                    }
                    rows = annotated + extras
                    status = "Compare complete. ${extras.size} item(s) only Ollama saw are added unchecked below -- review before saving."
                }
                is VisionCountResult.Failure -> {
                    status = "Ollama compare failed: ${result.message}"
                }
            }
        }
    }

    fun addPhoto(file: File) {
        photos = photos + file.absolutePath
        status = "Added photo ${photos.size}. Add another to cover more of the shelf, or run AI Count."
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        try {
            uris.forEach { uri ->
                val file = createPhotoFile(context.filesDir)
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                addPhoto(file)
            }
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
            WarningPanel("This sends the photo(s) you capture to $providerLabel API for counting. Nothing is saved until you review and confirm each row.")
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
        if (rows.isEmpty()) {
            item {
                if (!hasPermission) {
                    AssistCard {
                        Text("Camera permission needed", fontWeight = FontWeight.Bold)
                        Text("Order Radar uses the camera locally to capture the shelf photo.", color = RadarMuted)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal)) {
                            Text("Allow Camera")
                        }
                    }
                } else {
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
                }
            }
        }
        if (photos.isNotEmpty() && rows.isEmpty()) {
            item {
                Text("Photos (${photos.size})", color = RadarLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(photos) { index, path ->
                        PhotoThumbnail(path) { photos = photos.toMutableList().also { it.removeAt(index) } }
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val file = createPhotoFile(context.filesDir)
                            imageCapture?.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                mainExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        addPhoto(file)
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
                        Text(if (photos.isEmpty()) "Capture Shelf Photo" else "Capture Another Photo", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose from Gallery (pick multiple)", fontWeight = FontWeight.Bold)
                    }
                    if (photos.isNotEmpty()) {
                        Button(
                            onClick = { runVisionCount() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = RadarLime, contentColor = RadarCharcoal),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Run AI Count (${photos.size} photo${if (photos.size > 1) "s" else ""})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = {
                        photos = emptyList()
                        rows = emptyList()
                        status = "Take a wide, well-lit photo of the shelf or cooler you want to count, or choose one or more from your gallery."
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
                                VisionCountRow(row.suggestion, matchedProduct, quantity)
                            }
                            if (confirmed.isNotEmpty()) {
                                onSaveCounts(confirmed, photos.firstOrNull())
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
            if (ollamaBaseUrl.isNotBlank()) {
                item {
                    OutlinedButton(
                        onClick = { runOllamaCompare() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        enabled = !isComparing && !isLoading,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Compare with Ollama", fontWeight = FontWeight.Bold)
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
                if (isLoading || isComparing) {
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
    fun colorFor(percent: Int) = when {
        percent >= 80 -> RadarLime
        percent >= 50 -> Color(0xFFE0A526)
        else -> RadarOrange
    }
    val lowestConfidence = minOf(row.displayIdentificationConfidencePercent, row.displayCountConfidencePercent)
    AssistCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = row.checked, onCheckedChange = { onChange(row.copy(checked = it)) })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "AI detected: \"${row.suggestion.itemName}\"",
                        color = RadarMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    ConfidenceBadge("ID", row.displayIdentificationConfidencePercent, colorFor(row.displayIdentificationConfidencePercent))
                    Spacer(Modifier.width(4.dp))
                    ConfidenceBadge("Count", row.displayCountConfidencePercent, colorFor(row.displayCountConfidencePercent))
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
                        onChange(row.copy(quantity = (current - 1.0).coerceAtLeast(0.0).clean()))
                    }) { Icon(Icons.Default.Remove, contentDescription = "Decrease count") }
                    OutlinedTextField(
                        value = row.quantity,
                        onValueChange = { onChange(row.copy(quantity = it)) },
                        label = { Text("Confirmed count") },
                        suffix = { Text(matched?.defaultUnit ?: row.suggestion.unit) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = {
                        val current = row.quantity.toDoubleOrNull() ?: 0.0
                        onChange(row.copy(quantity = (current + 1.0).clean()))
                    }) { Icon(Icons.Default.Add, contentDescription = "Increase count") }
                }
                if (lowestConfidence < 80) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onChange(row.copy(quantity = "0")) }, label = { Text("Not here") })
                        AssistChip(onClick = { onChange(row.copy(checked = true)) }, label = { Text("Looks right") })
                    }
                }
                if (row.suggestion.notes.isNotBlank()) {
                    Text(row.suggestion.notes, color = RadarMuted, style = MaterialTheme.typography.labelSmall)
                }
                row.compareNote?.let { note ->
                    Text(note, color = if (note.startsWith("Ollama agrees")) RadarLime else RadarOrange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(label: String, percent: Int, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            "$label $percent%",
            color = color,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun PhotoThumbnail(path: String, onRemove: () -> Unit) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    Box(modifier = Modifier.size(90.dp)) {
        Box(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(RadarCard),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Photo", modifier = Modifier.fillMaxSize())
            } else {
                Text("Photo", color = RadarMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp).background(RadarCharcoal.copy(alpha = 0.7f), RoundedCornerShape(50))
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove photo", tint = RadarText, modifier = Modifier.size(16.dp))
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
    return File(dir, "vision-photo-${System.currentTimeMillis()}-${(0..9999).random()}.jpg")
}

// Deliberately conservative: a single shared word (e.g. "chicken") is not enough to confidently
// pre-select a product, since a wrong-but-plausible-sounding match is worse than no match --
// it silently mislabels real inventory. Require either the full name to contain/be contained by
// the detection, or every one of the known product's significant words to show up in it.
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
