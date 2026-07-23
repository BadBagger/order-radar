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

enum class DeliTextSourceKind {
    INVENTORY,
    PROMO,
    ORDER_SCREEN,
    NOTE
}

data class DeliTextExtractionInput(
    val id: String,
    val text: String,
    val kind: DeliTextSourceKind,
    val location: InventoryLocation? = null
)

interface DeliVisionTextExtractor {
    suspend fun extractText(photo: DeliPhotoInput): String
}

object DeliExtractionBatchBuilder {
    fun build(
        inputs: List<DeliTextExtractionInput>,
        defaultAdStart: LocalDate,
        defaultAdEnd: LocalDate,
        confidenceThreshold: Double = 0.80
    ): DeliExtractionBatch {
        val inventory = inputs
            .filter { it.kind == DeliTextSourceKind.INVENTORY }
            .flatMap { input ->
                val location = input.location ?: InventoryLocation.COOLER
                DeliTextExtractionParser.parseInventoryLabels(input.text, input.id, location, confidenceThreshold) +
                    DeliTextExtractionParser.parseLooseBackstockItems(input.text, input.id, location)
            }
            .let(DeliTextExtractionParser::mergeDuplicateCases)

        val promos = inputs
            .filter { it.kind == DeliTextSourceKind.PROMO }
            .flatMap { DeliTextExtractionParser.parsePromoItems(it.text, defaultAdStart, defaultAdEnd) }

        val orderLines = inputs
            .filter { it.kind == DeliTextSourceKind.ORDER_SCREEN }
            .flatMap { DeliTextExtractionParser.parseOrderScreenLines(it.text) }
            .mapIndexed { index, line -> line.copy(orderIndex = index) }

        val stickyNotes = inputs.flatMap { DeliTextExtractionParser.parseStickyNotes(it.text) }
        val verifyLabels = inventory
            .filter { !it.verified || it.confidence < confidenceThreshold || it.sku.startsWith("UNKNOWN-") }
            .map { it.toVerifyLabel() }

        return DeliExtractionBatch(
            inventoryItems = inventory,
            promoItems = promos,
            orderLines = orderLines,
            verifyLabels = verifyLabels,
            stickyNotes = stickyNotes
        )
    }
}

object DeliTextExtractionParser {
    fun parseInventoryLabels(text: String, photoId: String, location: InventoryLocation, confidenceThreshold: Double = 0.80): List<DeliInventoryItem> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val normalized = line.normalizeOcrLabelText()
                val sku = findSku(normalized) ?: return@mapNotNull null
                val useBy = findDateAfter(normalized, "use by", "use-by", "useby", "best by", "sell by", "exp")
                val packDate = findDateAfter(normalized, "pack", "packed", "pkd")
                val weight = parseCaseWeightLbs(normalized)
                val name = normalized.cleanInventoryName(sku)
                val confidence = estimateLabelConfidence(normalized, sku, useBy, weight)
                DeliInventoryItem(
                    sku = sku,
                    name = name,
                    category = categorize(name),
                    casesOnHand = findExplicitCaseCount(normalized) ?: 1.0,
                    caseWeightLbs = weight,
                    useByDate = useBy,
                    location = location,
                    confidence = confidence,
                    photoRefs = listOf(photoId),
                    verified = confidence >= confidenceThreshold,
                    brandVendor = findVendor(normalized)
                )
            }

    fun parseLooseBackstockItems(text: String, photoId: String, location: InventoryLocation): List<DeliInventoryItem> =
        text.lines()
            .map { it.trim() }
            .map { it.normalizeOcrLabelText() }
            .filter { line -> line.isNotBlank() && findSku(line) == null && !line.looksLikeShelfNoteOnly() && line.looksLikeLooseDeliProduct() }
            .mapIndexed { index, line ->
                val count = findLooseProductCount(line)
                val name = line
                    .stripLooseCount()
                    .replace(Regex("""\s+"""), " ")
                    .trim(' ', '-', '|', ',')
                    .ifBlank { "Unknown loose deli item" }
                DeliInventoryItem(
                    sku = "UNKNOWN-${photoId.uppercase(Locale.US)}-${index + 1}",
                    name = name,
                    category = categorize(name),
                    casesOnHand = count,
                    location = location,
                    confidence = 0.55,
                    photoRefs = listOf(photoId),
                    verified = false
                )
            }

    fun mergeDuplicateCases(items: List<DeliInventoryItem>): List<DeliInventoryItem> =
        items.groupBy { duplicateKey(it) }.values.map { group ->
            val first = group.first()
            val casesByPhoto = group
                .flatMap { item -> item.photoRefs.map { it to item.casesOnHand } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, counts) -> counts.sum() }
            val caseTotal = if (casesByPhoto.size > 1 && group.flatMap { it.photoRefs }.looksLikeOverlappingPhotoSet()) {
                casesByPhoto.values.maxOrNull() ?: group.sumOf { it.casesOnHand }
            } else {
                group.sumOf { it.casesOnHand }
            }
            first.copy(
                casesOnHand = caseTotal,
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
            val normalized = line.normalizeOcrLabelText()
            parsePublixOrderReviewLine(normalized, index)?.let { return@mapIndexedNotNull it }
            val sku = findSku(normalized) ?: return@mapIndexedNotNull null
            val suggested = findLabeledQuantity(normalized) ?: 0.0
            SupplierOrderLine(
                sku = sku,
                name = normalized.cleanLineName(sku),
                packSize = Regex("""pack[: ]*([A-Za-z0-9 x./-]+)""", RegexOption.IGNORE_CASE).find(normalized)?.groupValues?.get(1)?.trim(),
                suggestedCases = suggested,
                forecastDemandCases = suggested,
                safetyStockCases = 1.0,
                orderIndex = index
            )
        }

    fun parseStickyNotes(text: String): List<String> =
        text.lines()
            .map { it.trim().normalizeOcrLabelText() }
            .filter { it.isNotBlank() }
            .fold(emptyList<String>()) { notes, line ->
                val lower = line.lowercase(Locale.US)
                val isNoteStarter = noteStarterRegex.containsMatchIn(lower)
                val hasExtraCount = extraCountRegex.containsMatchIn(lower)
                when {
                    isNoteStarter && hasExtraCount -> notes + line
                    isNoteStarter -> notes + line
                    hasExtraCount && notes.lastOrNull()?.let { noteStarterRegex.containsMatchIn(it.lowercase(Locale.US)) && !extraCountRegex.containsMatchIn(it.lowercase(Locale.US)) } == true ->
                        notes.dropLast(1) + "${notes.last()} $line"
                    hasExtraCount -> notes + line
                    else -> notes
                }
            }
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
    """^\s*(\d{4,8})\s*(?:-|:|\s)\s*(.+?)\s+((?:\d+(?:\.\d+)?\s*/\s*)?\d+(?:\.\d+)?\s*(?:LB|LBS|OZ|EA|EACH|SC|CT|PK|PACK|CASE)\b)\s*(.*)$""",
    RegexOption.IGNORE_CASE
)

private fun parseCaseWeightLbs(line: String): Double? {
    val packMatch = Regex("""\b(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*(?:LB|LBS|POUND|POUNDS)\b""", RegexOption.IGNORE_CASE)
        .find(line)
    if (packMatch != null) {
        val packCount = packMatch.groupValues[1].toDoubleOrNull()
        val packWeight = packMatch.groupValues[2].toDoubleOrNull()
        if (packCount != null && packWeight != null) return packCount * packWeight
    }
    return Regex("""(\d+(?:\.\d+)?)\s*(?:LB|LBS|POUND|POUNDS)\b""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
}

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
    Regex("""(?:sku|item|it[e3]m|code)\s*[:#]?\s*([A-Za-z0-9-]{3,})""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.normalizeSkuCandidate()
        ?: Regex("""\b([0-9OQDISZB]{4,8})\b""", RegexOption.IGNORE_CASE)
            .findAll(line)
            .mapNotNull { it.groupValues[1].normalizeSkuCandidate() }
            .firstOrNull()

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
        "soup" in lower || "bisque" in lower || "chowder" in lower -> DeliCategory.SOUPS
        "salad" in lower || "slaw" in lower -> DeliCategory.SALADS
        "chicken" in lower -> DeliCategory.RAW_CHICKEN
        "pudding" in lower -> DeliCategory.PUDDING
        "cheese" in lower || "cheddar" in lower || "swiss" in lower || "provolone" in lower || "muenster" in lower || "american" in lower || "pepper jack" in lower -> DeliCategory.CHEESE
        "turkey" in lower || "ham" in lower || "roast beef" in lower || "salami" in lower || "bologna" in lower || "pastrami" in lower -> DeliCategory.DELI_MEAT
        "dip" in lower -> DeliCategory.DIPS
        "bread" in lower || "roll" in lower -> DeliCategory.BREADS
        else -> DeliCategory.OTHER
    }
}

private fun findVendor(line: String): String? =
    Regex("""(?:vendor|brand)\s*[: ]\s*([A-Za-z0-9 &'-]+)""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.trim()
        ?: when {
            line.contains("Blount", ignoreCase = true) -> "Blount"
            Regex("""grandma'?s?\s+kitchen""", RegexOption.IGNORE_CASE).containsMatchIn(line) -> "Grandma's Kitchen"
            line.contains("Publix", ignoreCase = true) || line.contains("PBX", ignoreCase = true) -> "Publix"
            else -> null
        }

private fun DeliInventoryItem.toVerifyLabel(): ExtractedDeliLabel =
    ExtractedDeliLabel(
        itemName = name,
        sku = sku.takeUnless { it.startsWith("UNKNOWN-") },
        caseWeightLbs = caseWeightLbs,
        useByDate = useByDate,
        brandVendor = brandVendor,
        confidence = confidence,
        sourcePhotoId = photoRefs.firstOrNull().orEmpty()
    )

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

private fun String.cleanInventoryName(sku: String): String =
    removeOcrSkuTokens(sku)
        .replace(Regex("""\b${Regex.escape(sku)}\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""(?:sku|item)[:# ]*\w+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""(?:use\s*-?\s*by|useby|exp|pack|packed)[: ]*\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""), "")
        .replace(Regex("""\d+(?:\.\d+)?\s*/\s*\d+(?:\.\d+)?\s*(?:LB|LBS|OZ|EA|EACH|SC|CT|POUND|POUNDS)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\d+(?:\.\d+)?\s*(?:LB|LBS|POUND|POUNDS)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\b(?:vendor|brand)\s*[: ]\s*[A-Za-z0-9 &'-]+""", RegexOption.IGNORE_CASE), "")
        .replace(explicitCaseCountRegex, "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', ',')
        .ifBlank { "Unknown item $sku" }

private fun String.looksLikeLooseDeliProduct(): Boolean {
    val lower = lowercase(Locale.US)
    return listOf(
        "cheddar",
        "swiss",
        "provolone",
        "muenster",
        "american",
        "pepper jack",
        "salami",
        "turkey",
        "ham",
        "roast beef",
        "bologna",
        "pastrami",
        "chicken breast",
        "cheese"
    ).any { it in lower }
}

private val explicitCaseCountRegex = Regex(
    """(?:\b(?:case|cases|count|cnt|marker|mark)\s*[:#]?\s*(\d+(?:\.\d+)?)\b|\b(\d+(?:\.\d+)?)\s*(?:cases?|cs)\b|\bx\s*(\d+(?:\.\d+)?)\b)""",
    RegexOption.IGNORE_CASE
)

private val noteStarterRegex = Regex("""\b(?:please|pls|plz|note|add)\b""", RegexOption.IGNORE_CASE)
private val extraCountRegex = Regex("""\b(?:add\s*)?(\d+(?:\.\d+)?)\s*(?:extra|xtra|more|addl|additional)\b|\b(?:extra|xtra|more|addl|additional)\s*(\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)

private fun findExplicitCaseCount(line: String): Double? =
    explicitCaseCountRegex.find(line)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        ?.toDoubleOrNull()

private fun findLooseProductCount(line: String): Double =
    Regex("""\b(\d+(?:\.\d+)?)\s*(?:x|ct|count|blocks?|logs?|loaves?|pieces?)\b""", RegexOption.IGNORE_CASE)
        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()
        ?: Regex("""^\s*[#x*]?\s*(\d+(?:\.\d+)?)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(line)
            ?.takeIf { it.groupValues[2].looksLikeLooseDeliProduct() }
            ?.groupValues?.get(1)?.toDoubleOrNull()
        ?: Regex("""^(.+?)\s*[#x*]?\s*(\d+(?:\.\d+)?)\s*$""", RegexOption.IGNORE_CASE)
            .find(line)
            ?.takeIf { it.groupValues[1].looksLikeLooseDeliProduct() }
            ?.groupValues?.get(2)?.toDoubleOrNull()
        ?: 1.0

private fun String.stripLooseCount(): String =
    replace(Regex("""\b\d+(?:\.\d+)?\s*(?:x|ct|count|blocks?|logs?|loaves?|pieces?)\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""^\s*[#x*]?\s*\d+(?:\.\d+)?\s+"""), "")
        .replace(Regex("""\s*[#x*]?\s*\d+(?:\.\d+)?\s*$"""), "")
        .replace(Regex("""\s+\b(?:blocks?|logs?|loaves?|pieces?)\b\s*$""", RegexOption.IGNORE_CASE), "")

private fun String.looksLikeShelfNoteOnly(): Boolean {
    val lower = lowercase(Locale.US)
    return ("shelf" in lower || "marker" in lower || "note" in lower || "please" in lower || "extra" in lower) && !looksLikeLooseDeliProduct()
}

private fun String.normalizeSkuCandidate(): String? {
    val normalized = uppercase(Locale.US)
        .replace('O', '0')
        .replace('Q', '0')
        .replace('D', '0')
        .replace('I', '1')
        .replace('S', '5')
        .replace('Z', '2')
        .replace('B', '8')
        .filter { it.isDigit() }
    return normalized.takeIf { it.length in 4..8 }
}

private fun String.normalizeOcrLabelText(): String =
    replace(Regex("""[|]"""), " ")
        .replace(Regex("""\bPBX\b""", RegexOption.IGNORE_CASE), "PBX")
        .replace(Regex("""\bPUBLlX\b""", RegexOption.IGNORE_CASE), "Publix")
        .replace(Regex("""\bBL0UNT\b""", RegexOption.IGNORE_CASE), "Blount")
        .replace(Regex("""\bGRANDMA[’` ]?S\s+KITCHEN\b""", RegexOption.IGNORE_CASE), "Grandma's Kitchen")
        .replace(Regex("""\bU5E\s*BY\b""", RegexOption.IGNORE_CASE), "Use By")
        .replace(Regex("""\bEXP[.:]?\b""", RegexOption.IGNORE_CASE), "Exp ")
        .replace(Regex("""\s+"""), " ")
        .trim()

private fun String.removeOcrSkuTokens(sku: String): String =
    splitToSequence(' ')
        .filterNot { token -> token.trim('-', ':', '#').normalizeSkuCandidate() == sku }
        .joinToString(" ")

private fun List<String>.looksLikeOverlappingPhotoSet(): Boolean {
    val lower = distinct().map { it.lowercase(Locale.US) }
    return lower.any { ref ->
        listOf("angle", "overlap", "nearby", "left", "right", "wide", "page").any { it in ref }
    }
}
