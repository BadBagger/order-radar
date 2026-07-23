package com.smithware.orderradar.data

import android.util.Base64
import com.smithware.orderradar.domain.clean
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class VisionCountSuggestion(
    val itemName: String,
    val estimatedQuantity: Double,
    val unit: String,
    // Two independent scores: how sure the AI is this is genuinely the named product (based on
    // cross-checking shape, color, OCR text, and shelf position), versus how sure it is of the
    // NUMBER specifically (based on photo quality/occlusion). A shaky count on a correctly
    // identified item, or a solid count on a shaky guess at what the item even is, are both
    // real and different kinds of uncertainty -- one score can't represent both.
    val identificationConfidencePercent: Int,
    val countConfidencePercent: Int,
    val notes: String
)

// Recent count/usage history for one known product, folded into the vision prompt so the AI
// can sanity-check a raw photo estimate against what this product's supply actually looks like
// (e.g. a count of 12 is probably a misread if this product has never had more than 2 on hand).
data class ProductHistoryHint(
    val name: String,
    val category: String,
    val itemNumber: String?,
    val lastCountQuantity: Double?,
    val lastCountUnit: String?,
    val daysSinceLastCount: Int?,
    val averageDailyUsage: Double?,
    val storageLocation: String?,
    val visualIdentifiers: String?
)

sealed class VisionCountResult {
    data class Success(val items: List<VisionCountSuggestion>) : VisionCountResult()
    data class Failure(val message: String) : VisionCountResult()
}

data class OrderLineSuggestion(
    val itemName: String,
    val orderedQuantity: Double,
    val unit: String,
    val confidencePercent: Int,
    val notes: String
) {
    val confidenceLabel: String get() = when {
        confidencePercent >= 80 -> "high"
        confidencePercent >= 50 -> "medium"
        else -> "low"
    }
}

sealed class OrderImportResult {
    data class Success(val items: List<OrderLineSuggestion>) : OrderImportResult()
    data class Failure(val message: String) : OrderImportResult()
}

/**
 * Sends a captured shelf/cooler photo to the user's own cloud AI provider (OpenAI or
 * Anthropic, whichever they've configured -- both equally first-class, same prompt/
 * response schema, only the HTTP request/response envelope differs) and asks it to
 * estimate a visible count per product. This is a manager-review assist only: results
 * are never saved until the user confirms each row in the UI.
 */
object VisionCountClient {
    private const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"

    // photos supports covering a shelf too wide for one frame: pass them in the order they were
    // taken (left-to-right or however the manager walked the shelf) and the model reasons about
    // all of them together in one call, so it can recognize a box repeated across two overlapping
    // photos instead of counting it twice.
    fun countShelfPhoto(
        provider: VisionProvider,
        apiKey: String,
        model: String,
        photos: List<File>,
        knownProductNames: List<String>,
        ocrText: String = "",
        historyHints: List<ProductHistoryHint> = emptyList()
    ): VisionCountResult {
        if (apiKey.isBlank()) return VisionCountResult.Failure("No API key set. Add one in Settings to use AI shelf counting.")
        if (photos.isEmpty()) return VisionCountResult.Failure("No photo to analyze.")
        return try {
            val imagesBase64 = photos.map { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
            val prompt = buildPrompt(knownProductNames, ocrText, historyHints, imagesBase64.size)
            val responseText = when (provider) {
                VisionProvider.ANTHROPIC -> postToAnthropic(model, apiKey, imagesBase64, prompt)
                VisionProvider.OPENAI -> postToOpenAi(model, apiKey, imagesBase64, prompt)
            }
            parseSuggestions(responseText)
        } catch (e: Exception) {
            VisionCountResult.Failure("AI count failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildPrompt(knownProductNames: List<String>, ocrText: String, historyHints: List<ProductHistoryHint>, photoCount: Int): String {
        val knownList = if (knownProductNames.isEmpty()) "none saved yet" else knownProductNames.joinToString(", ")
        // Grouped by category so visually-similar products (e.g. a store's whole wings/tenders
        // lineup) sit next to each other -- this is a comparison reference, not a claim that
        // every item in a group will appear in the same photo. Seeing the distinguishing clue
        // for one item (a handle, a color swatch, a box shape) next to its near-lookalikes is
        // what lets the model rule those lookalikes out even when only one item is in frame.
        val historyBlock = if (historyHints.isEmpty()) "No count history yet." else
            historyHints.groupBy { it.category }.entries.joinToString("\n\n") { (category, hints) ->
                val lines = hints.joinToString("\n") { hint ->
                    val parts = mutableListOf<String>()
                    if (hint.itemNumber != null) parts += "item # ${hint.itemNumber}"
                    if (hint.lastCountQuantity != null) parts += "last counted ${hint.lastCountQuantity.clean()} ${hint.lastCountUnit.orEmpty()}".trim()
                    if (hint.daysSinceLastCount != null) parts += "${hint.daysSinceLastCount} day(s) ago"
                    if (hint.averageDailyUsage != null) parts += "averages ${hint.averageDailyUsage.clean()}/day"
                    if (hint.storageLocation != null) parts += "usually found at ${hint.storageLocation}"
                    if (hint.visualIdentifiers != null) parts += "visual ID: ${hint.visualIdentifiers}"
                    "- ${hint.name}: ${if (parts.isEmpty()) "no prior data" else parts.joinToString(", ")}"
                }
                "$category:\n$lines"
            }
        val ocrBlock = if (ocrText.isBlank()) "No text was legible on the photo." else
            "Text an on-device OCR pass found on labels/signage in this photo (may be partial, out of order, or noisy): \"${ocrText.take(800)}\""

        val multiPhotoBlock = if (photoCount > 1) """

            Multiple photos: you were given $photoCount photos of this shelf/cooler, taken in order to cover an area too wide for one frame -- treat them as one continuous space, not $photoCount separate counts to add up. Consecutive photos likely overlap at their edges, so the same physical box can appear in more than one photo. Before finalizing a product's total, check whether items near the edge of one photo reappear near the edge of the next (matching item #, position, or arrangement) -- if so, that is the SAME box and must be counted only once in the total, not once per photo it appears in.
        """.trimIndent() else ""

        return """
            You are helping a deli/grocery manager count physical inventory from a shelf or cooler photo.
            Real shelf photos are often imperfect: blurry, tilted, cropped, partially blocked by other items, or dim. Do not skip an item just because it isn't perfectly legible -- use packaging shape, color, stacking pattern, partial text, and the context below to make your best estimate, and reflect any uncertainty in the confidence scores instead of leaving the item out.
            List every DISTINCT product you can see -- one row per product, with the TOTAL count of every unit of that product anywhere in view. This means: scan the entire visible area first (every shelf, every stack, every box scattered around, not just the first one you notice), then add up every instance of the same product into a single row. Never emit more than one row for the same product just because you can read its label on several separate boxes -- if you see the same item # or name on 5 different boxes across the shelf, that is one row with quantity 5, not five rows of quantity 1 each.$multiPhotoBlock

            Identification method -- do this deliberately, not as a guess: before naming an item, cross-check as many of these signals as are available and let them corroborate or contradict each other:
            1. A printed "item #" or case code on the box, if legible (even partially) -- match it against the "item #" listed for each known product below. This is usually the single most reliable signal on a case of otherwise-identical-looking boxes, since many vendors print an exact code but not a full readable product name.
            2. Box/case shape and size (e.g. narrow boxes with front handles vs. boxes with a band around the top and no handle are different product families).
            3. The color-coding "visual ID" hint for each known product below, if one exists -- match by color first, since color reads more reliably than small print on a blurry photo.
            4. Any OCR text below that matches a product name or code, even partially.
            5. Shelf position -- products are usually restocked to a consistent spot, so a box's location matched against "usually found at" below is itself evidence of which product it is.
            The more of these signals agree, the higher identificationConfidence should be. If you can read an item # clearly and it matches a known product, that alone should give high confidence even without the other signals. If you're naming something off one weak signal alone (e.g. only a vague box shape, nothing else corroborating), keep identificationConfidence low even if you're sure about the count. If you can read a code but it does NOT match any known product's item #, say so in "notes" and lean toward proposing a new product named after that code rather than force-fitting a known one.

            Counting method: for products stored as identical boxes/cases -- whether stacked, side by side, or scattered across a shelf -- you do not need to read the printed label on every single box. Count every distinct unit by its visible edges, seams, or shadow lines between boxes of the same size and color, then sum them into that product's one total. If you can see 4 same-size boxes of the same product anywhere in the photo(s), that is a count of 4 even if only one box's text is fully legible. countConfidence is about the NUMBER specifically -- how cleanly you could see and count every instance given photo quality and occlusion -- and is independent of identificationConfidence: you can be very sure something is Original Rotisserie Chicken but unsure if there are 3 or 4 boxes, or sure there are 4 boxes of something but unsure which flavor.

            Known products already tracked in this app: $knownList.
            If an item genuinely IS one of the known products under a slightly different label (e.g. "Caesar Pasta Salad" for a tub printed "Caesar Pasta Salad Base" -- same product, just a shorter name), reuse that exact known product name. But accuracy comes first: do not force-fit to a known name just because it shares one word or a general category (e.g. do not call a whole rotisserie chicken "Chicken Breast Box" just because both involve chicken -- those are different products). If none of the known products are actually what you're looking at, invent a new, specific, accurate name instead -- a new product is far better than a mislabeled one.

            Recent count/usage history for known products, grouped by category (use it to sanity-check an estimate that seems off -- e.g. if a product rarely has more than 2 on hand, a photo estimate of 12 is probably a miscount, not a restock). Products in the same category are often visually similar, so use one item's distinguishing clue (an item #, a handle, a color swatch, a box shape) to rule out its near-lookalikes in that same group, even if the photo only shows one of them -- this is a reference list, not a claim that every item in a group appears in every photo:
            $historyBlock

            $ocrBlock

            Respond with ONLY a JSON object, no markdown fences, no commentary, in this exact shape:
            {"items":[{"name":"string","quantity":number,"unit":"string","identificationConfidence":number,"countConfidence":number,"notes":"string"}]}
            Both confidence fields must be integers from 0 to 100, scored independently per the method above. Use "unit" values like "boxes", "cases", "each", "tubs", or "trays" based on how the product is packaged. Keep "notes" under 15 words explaining what you counted or any uncertainty (e.g. partially hidden items, a blurry region, an identification based on color alone, or that you cross-checked against history).
        """.trimIndent()
    }

    // Reads a box meat / grocery order form, order guide, or handwritten order sheet and pulls
    // out every line item + quantity being ordered -- a document-reading task, not a shelf
    // count, so it can introduce brand-new products the catalog doesn't have yet instead of
    // only matching what's already known (which is all the old plain-OCR importer could do).
    fun extractOrderForm(
        provider: VisionProvider,
        apiKey: String,
        model: String,
        photo: File,
        knownProductNames: List<String>,
        ocrText: String = ""
    ): OrderImportResult {
        if (apiKey.isBlank()) return OrderImportResult.Failure("No API key set. Add one in Settings to use AI order import.")
        return try {
            val imageBase64 = Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP)
            val prompt = buildOrderFormPrompt(knownProductNames, ocrText)
            val responseText = when (provider) {
                VisionProvider.ANTHROPIC -> postToAnthropic(model, apiKey, listOf(imageBase64), prompt)
                VisionProvider.OPENAI -> postToOpenAi(model, apiKey, listOf(imageBase64), prompt)
            }
            parseOrderLines(responseText)
        } catch (e: Exception) {
            OrderImportResult.Failure("AI order import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildOrderFormPrompt(knownProductNames: List<String>, ocrText: String): String {
        val knownList = if (knownProductNames.isEmpty()) "none saved yet" else knownProductNames.joinToString(", ")
        val ocrBlock = if (ocrText.isBlank()) "No text was legible on the photo." else
            "Text an on-device OCR pass found on this form (may be partial, out of order, or noisy): \"${ocrText.take(1200)}\""

        return """
            You are helping a deli/grocery manager read a box meat / grocery order form, order guide, or order sheet photo and extract every line item they're ordering.
            This is a printed or handwritten list of items and quantities to order (e.g. a supplier order guide, a box meat order sheet, a handwritten order list) -- NOT a shelf photo of physical inventory. Read it like a document: find each row or line, its item name or code, and its order quantity.
            Real order forms are often imperfect: blurry, tilted, cropped, partially handwritten over printed text, or marked with checkmarks or circles instead of clean numbers. Use context (nearby printed item codes, typical case sizes, column alignment) to make your best reading, and reflect uncertainty in the confidence score instead of skipping a line.

            Known products already tracked in this app: $knownList.
            If a line genuinely IS one of the known products under a slightly different label, reuse that exact known product name. But accuracy comes first: do not force-fit to a known name just because it shares a word or category. If a line isn't one of the known products, invent a new, specific, accurate name instead -- a new product is far better than a mislabeled one.

            $ocrBlock

            Respond with ONLY a JSON object, no markdown fences, no commentary, in this exact shape:
            {"items":[{"name":"string","quantity":number,"unit":"string","confidence":number,"notes":"string"}]}
            "quantity" is the amount being ORDERED on this line, not a count of what's currently on hand. "confidence" must be an integer from 0 to 100. Use "unit" values like "boxes", "cases", "each", or "lbs" based on what the form itself specifies. Keep "notes" under 15 words explaining what you read or any uncertainty (e.g. handwriting was unclear, or you matched by item code rather than name).
        """.trimIndent()
    }

    private fun parseOrderLines(responseText: String): OrderImportResult {
        val jsonText = extractJsonObject(responseText) ?: return OrderImportResult.Failure("Could not parse AI response as JSON.")
        val items = JSONObject(jsonText).optJSONArray("items") ?: JSONArray()
        val suggestions = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").ifBlank { return@mapNotNull null }
            OrderLineSuggestion(
                itemName = name,
                orderedQuantity = item.optDouble("quantity", 1.0).let { if (it.isNaN()) 1.0 else it },
                unit = item.optString("unit").ifBlank { "each" },
                confidencePercent = parseConfidence(item.opt("confidence")),
                notes = item.optString("notes")
            )
        }
        return if (suggestions.isEmpty()) OrderImportResult.Failure("AI did not detect any order lines. Try a clearer photo or add rows manually.")
        else OrderImportResult.Success(suggestions)
    }

    // ---- Anthropic -------------------------------------------------------

    private fun postToAnthropic(model: String, apiKey: String, images: List<String>, prompt: String): String {
        val content = JSONArray()
        images.forEachIndexed { index, imageBase64 ->
            if (images.size > 1) content.put(JSONObject().put("type", "text").put("text", "Photo ${index + 1} of ${images.size}:"))
            content.put(
                JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageBase64))
            )
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val requestBody = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()

        val connection = URL(ANTHROPIC_ENDPOINT).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("content-type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        connection.outputStream.use { stream: OutputStream -> stream.write(requestBody.toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException(message ?: "HTTP $status from Anthropic API")
        }

        val contentArray = JSONObject(body).optJSONArray("content") ?: throw IllegalStateException("No content in Anthropic response")
        return (0 until contentArray.length())
            .mapNotNull { contentArray.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "text" }
            ?.optString("text")
            ?: throw IllegalStateException("No text content in Anthropic response")
    }

    // ---- OpenAI -----------------------------------------------------------

    private fun postToOpenAi(model: String, apiKey: String, images: List<String>, prompt: String): String {
        val content = JSONArray()
        images.forEachIndexed { index, imageBase64 ->
            if (images.size > 1) content.put(JSONObject().put("type", "text").put("text", "Photo ${index + 1} of ${images.size}:"))
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val requestBody = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()

        val connection = URL(OPENAI_ENDPOINT).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("content-type", "application/json")
        connection.setRequestProperty("authorization", "Bearer $apiKey")
        connection.outputStream.use { stream: OutputStream -> stream.write(requestBody.toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException(message ?: "HTTP $status from OpenAI API")
        }

        val choices = JSONObject(body).optJSONArray("choices") ?: throw IllegalStateException("No choices in OpenAI response")
        if (choices.length() == 0) throw IllegalStateException("Empty choices array in OpenAI response")
        return choices.getJSONObject(0).optJSONObject("message")?.optString("content")
            ?: throw IllegalStateException("No message content in OpenAI response")
    }

    // ---- shared response parsing -------------------------------------------

    private fun parseSuggestions(responseText: String): VisionCountResult {
        val jsonText = extractJsonObject(responseText) ?: return VisionCountResult.Failure("Could not parse AI response as JSON.")
        val items = JSONObject(jsonText).optJSONArray("items") ?: JSONArray()
        val suggestions = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").ifBlank { return@mapNotNull null }
            VisionCountSuggestion(
                itemName = name,
                estimatedQuantity = item.optDouble("quantity", 1.0).let { if (it.isNaN()) 1.0 else it },
                unit = item.optString("unit").ifBlank { "each" },
                // Falls back to the old single "confidence" field if a model ignores the new
                // split schema, so a stale response shape still parses instead of dropping the item.
                identificationConfidencePercent = if (item.has("identificationConfidence")) parseConfidence(item.opt("identificationConfidence")) else parseConfidence(item.opt("confidence")),
                countConfidencePercent = if (item.has("countConfidence")) parseConfidence(item.opt("countConfidence")) else parseConfidence(item.opt("confidence")),
                notes = item.optString("notes")
            )
        }
        return if (suggestions.isEmpty()) VisionCountResult.Failure("AI did not detect any items. Try a clearer photo or count manually.")
        else VisionCountResult.Success(suggestions)
    }

    // Accepts either the numeric 0-100 we ask for, or a stray "high|medium|low" string, in case
    // a model ignores the schema -- never let a parsing mismatch drop an otherwise-good item.
    private fun parseConfidence(raw: Any?): Int = when (raw) {
        is Number -> raw.toInt()
        is String -> when (raw.trim().lowercase()) {
            "high" -> 90
            "medium" -> 60
            "low" -> 30
            else -> raw.toDoubleOrNull()?.toInt() ?: 50
        }
        else -> 50
    }.coerceIn(0, 100)

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return text.substring(start, end + 1)
    }
}
