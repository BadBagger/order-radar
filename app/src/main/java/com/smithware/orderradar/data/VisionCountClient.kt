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
    val confidencePercent: Int,
    val notes: String
) {
    val confidenceLabel: String get() = when {
        confidencePercent >= 80 -> "high"
        confidencePercent >= 50 -> "medium"
        else -> "low"
    }
}

// Recent count/usage history for one known product, folded into the vision prompt so the AI
// can sanity-check a raw photo estimate against what this product's supply actually looks like
// (e.g. a count of 12 is probably a misread if this product has never had more than 2 on hand).
data class ProductHistoryHint(
    val name: String,
    val category: String,
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

    fun countShelfPhoto(
        provider: VisionProvider,
        apiKey: String,
        model: String,
        photo: File,
        knownProductNames: List<String>,
        ocrText: String = "",
        historyHints: List<ProductHistoryHint> = emptyList()
    ): VisionCountResult {
        if (apiKey.isBlank()) return VisionCountResult.Failure("No API key set. Add one in Settings to use AI shelf counting.")
        return try {
            val imageBase64 = Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP)
            val prompt = buildPrompt(knownProductNames, ocrText, historyHints)
            val responseText = when (provider) {
                VisionProvider.ANTHROPIC -> postToAnthropic(model, apiKey, imageBase64, prompt)
                VisionProvider.OPENAI -> postToOpenAi(model, apiKey, imageBase64, prompt)
            }
            parseSuggestions(responseText)
        } catch (e: Exception) {
            VisionCountResult.Failure("AI count failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildPrompt(knownProductNames: List<String>, ocrText: String, historyHints: List<ProductHistoryHint>): String {
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

        return """
            You are helping a deli/grocery manager count physical inventory from a shelf or cooler photo.
            Real shelf photos are often imperfect: blurry, tilted, cropped, partially blocked by other items, or dim. Do not skip an item just because it isn't perfectly legible -- use packaging shape, color, stacking pattern, partial text, and the context below to make your best estimate, and reflect any uncertainty in the confidence score instead of leaving the item out.
            List every distinct food product you can see and estimate how many discrete units (boxes, chubs, tubs, packages, or trays) of each are visible. Do not estimate by weight.

            Counting method: for products stored as identical stacked boxes/cases, you do not need to read the printed label on every box. Count distinct stacked units by their visible edges, seams, or shadow lines between boxes of the same size and color -- if you can see a stack of 4 same-size boxes at a known product's usual spot, that is a count of 4 even if only the top box's text is legible. Products are usually restocked in a consistent spot and order, so a box's position on the shelf (matched against "usually found at" below) is itself evidence of which product it is.
            Some products are distinguished by a color-coding system on the lid or wrap rather than by printed text -- see each product's "visual ID" hint below and match by color first when one is given, since color is often more reliable than text on a blurry photo.

            Known products already tracked in this app: $knownList.
            If an item matches one of the known products (even loosely, e.g. "Caesar Pasta Salad" for a tub labeled "Caesar Pasta Salad Base"), reuse that exact known product name. Otherwise invent a short, clear product name.

            Recent count/usage history for known products, grouped by category (use it to sanity-check an estimate that seems off -- e.g. if a product rarely has more than 2 on hand, a photo estimate of 12 is probably a miscount, not a restock). Products in the same category are often visually similar, so use one item's distinguishing clue (a handle, a color swatch, a box shape) to rule out its near-lookalikes in that same group, even if the photo only shows one of them -- this is a reference list, not a claim that every item in a group appears in every photo:
            $historyBlock

            $ocrBlock

            Respond with ONLY a JSON object, no markdown fences, no commentary, in this exact shape:
            {"items":[{"name":"string","quantity":number,"unit":"string","confidence":number,"notes":"string"}]}
            "confidence" must be an integer from 0 to 100 reflecting how sure you are of that specific count, given photo quality and how well it matches the history above. Use "unit" values like "boxes", "cases", "each", "tubs", or "trays" based on how the product is packaged. Keep "notes" under 15 words explaining what you counted or any uncertainty (e.g. partially hidden items, a blurry region, or that you cross-checked against history).
        """.trimIndent()
    }

    // ---- Anthropic -------------------------------------------------------

    private fun postToAnthropic(model: String, apiKey: String, imageBase64: String, prompt: String): String {
        val content = JSONArray()
            .put(
                JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", imageBase64))
            )
            .put(JSONObject().put("type", "text").put("text", prompt))
        val requestBody = JSONObject()
            .put("model", model)
            .put("max_tokens", 1536)
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

    private fun postToOpenAi(model: String, apiKey: String, imageBase64: String, prompt: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$imageBase64")))
            .put(JSONObject().put("type", "text").put("text", prompt))
        val requestBody = JSONObject()
            .put("model", model)
            .put("max_tokens", 1536)
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
                confidencePercent = parseConfidence(item.opt("confidence")),
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
