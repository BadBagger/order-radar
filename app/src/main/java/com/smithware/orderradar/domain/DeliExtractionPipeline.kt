package com.smithware.orderradar.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class DeliExtractionStatus {
    QUEUED,
    RUNNING,
    NEEDS_VERIFICATION,
    COMPLETE,
    FAILED
}

data class DeliPhotoInput(
    val id: String,
    val uri: String,
    val location: InventoryLocation,
    val capturedAtMillis: Long
)

data class DeliExtractionProgress(
    val status: DeliExtractionStatus,
    val completedPhotos: Int,
    val totalPhotos: Int,
    val message: String
)

data class ExtractedDeliLabel(
    val itemName: String,
    val sku: String?,
    val packSize: String? = null,
    val caseWeightLbs: Double? = null,
    val packDate: LocalDate? = null,
    val useByDate: LocalDate? = null,
    val brandVendor: String? = null,
    val confidence: Double,
    val sourcePhotoId: String
)

data class DeliExtractionBatch(
    val inventoryItems: List<DeliInventoryItem>,
    val promoItems: List<PromoItem>,
    val orderLines: List<SupplierOrderLine>,
    val verifyLabels: List<ExtractedDeliLabel>,
    val stickyNotes: List<String>
)

interface DeliVisionTextExtractor {
    suspend fun extractText(photo: DeliPhotoInput): String
}

object DeliTextExtractionParser {
    fun parseInventoryLabels(text: String, photoId: String, location: InventoryLocation, confidenceThreshold: Double = 0.80): List<DeliInventoryItem> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val sku = findSku(line) ?: return@mapNotNull null
                val useBy = findDateAfter(line, "use by", "use-by", "useby", "exp")
                val packDate = findDateAfter(line, "pack", "packed")
                val weight = Regex("""(\d+(?:\.\d+)?)\s*(?:lb|lbs|pound)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                val name = line
                    .replace(Regex("""sku[:# ]*\w+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""item[:# ]*\w+""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""), "")
                    .replace(Regex("""\d+(?:\.\d+)?\s*(?:lb|lbs|pound)s?""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim(' ', '-', '|', ',')
                    .ifBlank { "Unknown item $sku" }
                val confidence = estimateLabelConfidence(line, sku, useBy, weight)
                DeliInventoryItem(
                    sku = sku,
                    name = name,
                    category = categorize(name),
                    casesOnHand = 1.0,
                    caseWeightLbs = weight,
                    useByDate = useBy,
                    location = location,
                    confidence = confidence,
                    photoRefs = listOf(photoId),
                    verified = confidence >= confidenceThreshold,
                    brandVendor = findVendor(line)
                )
            }

    fun mergeDuplicateCases(items: List<DeliInventoryItem>): List<DeliInventoryItem> =
        items.groupBy { duplicateKey(it) }.values.map { group ->
            val first = group.first()
            first.copy(
                casesOnHand = group.sumOf { it.casesOnHand },
                confidence = group.minOf { it.confidence },
                photoRefs = group.flatMap { it.photoRefs }.distinct(),
                verified = group.all { it.verified }
            )
        }

    fun parsePromoItems(text: String, defaultStart: LocalDate, defaultEnd: LocalDate): List<PromoItem> =
        text.lines().mapNotNull { line ->
            val sku = findSku(line) ?: return@mapNotNull null
            val dealType = when {
                line.contains("BOGO", true) -> PromoDealType.BOGO
                line.contains("B2G1", true) || line.contains("buy 2 get 1", true) -> PromoDealType.B2G1
                line.contains("B2G2", true) || line.contains("buy 2 get 2", true) -> PromoDealType.B2G2
                Regex("""\d+\s*/\s*\$?\d+""").containsMatchIn(line) -> PromoDealType.MULTI_BUY
                else -> PromoDealType.PRICE_POINT
            }
            val discount = Regex("""(\d+(?:\.\d+)?)\s*%""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()
            val prices = Regex("""\$?(\d+(?:\.\d{2})?)""").findAll(line).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
            PromoItem(
                sku = sku,
                name = line.cleanLineName(sku),
                retailPrice = prices.getOrNull(0),
                salePrice = prices.getOrNull(1),
                dealType = dealType,
                discountPct = discount,
                adStartDate = defaultStart,
                adEndDate = defaultEnd
            )
        }

    fun parseOrderScreenLines(text: String): List<SupplierOrderLine> =
        text.lines().mapIndexedNotNull { index, line ->
            parsePublixOrderReviewLine(line, index)?.let { return@mapIndexedNotNull it }
            val sku = findSku(line) ?: return@mapIndexedNotNull null
            val suggested = findLabeledQuantity(line) ?: 0.0
            SupplierOrderLine(
                sku = sku,
                name = line.cleanLineName(sku),
                packSize = Regex("""pack[: ]*([A-Za-z0-9 x./-]+)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim(),
                suggestedCases = suggested,
                forecastDemandCases = suggested,
                safetyStockCases = 1.0,
                orderIndex = index
            )
        }

    fun parseStickyNotes(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.contains("add", true) || it.contains("extra", true) || it.contains("note", true) || it.contains("please", true) }
}

private fun parsePublixOrderReviewLine(line: String, index: Int): SupplierOrderLine? {
    val match = publixOrderRowRegex.find(line.trim()) ?: return null
    val sku = match.groupValues[1]
    val name = match.groupValues[2].cleanOrderDescription()
    val packSize = match.groupValues[3].replace(Regex("""\s+"""), " ").trim()
    val numericTail = match.groupValues[4]
    val suggested = firstQuantityAfterPack(numericTail)
    return SupplierOrderLine(
        sku = sku,
        name = name,
        packSize = packSize,
        suggestedCases = suggested,
        forecastDemandCases = suggested,
        safetyStockCases = 1.0,
        orderIndex = index
    )
}

private val publixOrderRowRegex = Regex(
    """^\s*(\d{6,8})\s*[-–]\s*(.+?)\s+((?:\d+(?:\.\d+)?\s*/\s*)?\d+(?:\.\d+)?\s*(?:LB|LBS|OZ|EA|EACH|SC|CT)\b)\s*(.*)$""",
    RegexOption.IGNORE_CASE
)

private fun firstQuantityAfterPack(tail: String): Double =
    Regex("""(?<![\w.])\d+(?:\.\d+)?(?![\w.])""")
        .findAll(tail)
        .mapNotNull { it.value.toDoubleOrNull() }
        .firstOrNull()
        ?: 0.0

private fun findLabeledQuantity(line: String): Double? =
    Regex("""(?:suggested|sugg|sys|final|revised)\D*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: Regex("""\bqty\D*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toDoubleOrNull()

private fun findSku(line: String): String? =
    Regex("""(?:sku|item)\s*[:#]?\s*([A-Za-z0-9-]{3,})""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)
        ?: Regex("""\b(\d{4,8})\b""").find(line)?.groupValues?.get(1)

private fun findDateAfter(line: String, vararg labels: String): LocalDate? {
    val lower = line.lowercase(Locale.US)
    val matchedLabel = labels.firstOrNull { lower.contains(it) } ?: return null
    val start = lower.indexOf(matchedLabel).coerceAtLeast(0)
    val tail = line.substring(start)
    val dateText = Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{4}-\d{2}-\d{2}""").find(tail)?.value ?: return null
    return parseDate(dateText)
}

private fun parseDate(raw: String): LocalDate? =
    dateFormats.firstNotNullOfOrNull { formatter ->
        try {
            LocalDate.parse(raw, formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }

private val dateFormats = listOf(
    DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US),
    DateTimeFormatter.ofPattern("M-d-uuuu", Locale.US),
    DateTimeFormatter.ofPattern("M/d/uu", Locale.US),
    DateTimeFormatter.ISO_LOCAL_DATE
)

private fun estimateLabelConfidence(line: String, sku: String?, useBy: LocalDate?, weight: Double?): Double {
    var score = 0.45
    if (sku != null) score += 0.20
    if (useBy != null) score += 0.15
    if (weight != null) score += 0.10
    if (line.length >= 24) score += 0.10
    return score.coerceAtMost(0.98)
}

private fun categorize(name: String): DeliCategory {
    val lower = name.lowercase(Locale.US)
    return when {
        "wing" in lower || "tender" in lower -> DeliCategory.WINGS_TENDERS
        "chicken" in lower -> DeliCategory.RAW_CHICKEN
        "salad" in lower -> DeliCategory.SALADS
        "soup" in lower -> DeliCategory.SOUPS
        "pudding" in lower -> DeliCategory.PUDDING
        "turkey" in lower || "ham" in lower || "roast beef" in lower -> DeliCategory.DELI_MEAT
        "cheese" in lower -> DeliCategory.CHEESE
        "dip" in lower -> DeliCategory.DIPS
        "bread" in lower || "roll" in lower -> DeliCategory.BREADS
        else -> DeliCategory.OTHER
    }
}

private fun findVendor(line: String): String? =
    Regex("""(?:vendor|brand)[: ]+([A-Za-z0-9 &'-]+)""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.trim()

private fun duplicateKey(item: DeliInventoryItem): String =
    listOf(item.sku.trim().uppercase(Locale.US), item.useByDate?.toString().orEmpty(), item.location.name).joinToString("|")

private fun String.cleanLineName(sku: String): String =
    replace(sku, "")
        .replace(Regex("""(?:sku|item|suggested|sugg|sys|qty|pack)[:# ]*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ',')
        .ifBlank { "Unknown item $sku" }

private fun String.cleanOrderDescription(): String =
    replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ',')
        .ifBlank { "Unknown order item" }
