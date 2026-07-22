package com.smithware.orderradar.data

import android.util.Base64
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
    val confidence: String,
    val notes: String
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

    fun countShelfPhoto(provider: VisionProvider, apiKey: String, model: String, photo: File, knownProductNames: List<String>): VisionCountResult {
        if (apiKey.isBlank()) return VisionCountResult.Failure("No API key set. Add one in Settings to use AI shelf counting.")
        return try {
            val imageBase64 = Base64.encodeToString(photo.readBytes(), Base64.NO_WRAP)
            val prompt = buildPrompt(knownProductNames)
            val responseText = when (provider) {
                VisionProvider.ANTHROPIC -> postToAnthropic(model, apiKey, imageBase64, prompt)
                VisionProvider.OPENAI -> postToOpenAi(model, apiKey, imageBase64, prompt)
            }
            parseSuggestions(responseText)
        } catch (e: Exception) {
            VisionCountResult.Failure("AI count failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun buildPrompt(knownProductNames: List<String>): String {
        val knownList = if (knownProductNames.isEmpty()) "none saved yet" else knownProductNames.joinToString(", ")
        return """
            You are helping a deli/grocery manager count physical inventory from a shelf or cooler photo.
            List every distinct food product you can see and estimate how many discrete units (boxes, chubs, tubs, packages, or trays) of each are visible. Do not estimate by weight.
            Known products already tracked in this app: $knownList.
            If an item matches one of the known products (even loosely, e.g. "Caesar Pasta Salad" for a tub labeled "Caesar Pasta Salad Base"), reuse that exact known product name. Otherwise invent a short, clear product name.
            Respond with ONLY a JSON object, no markdown fences, no commentary, in this exact shape:
            {"items":[{"name":"string","quantity":number,"unit":"string","confidence":"high|medium|low","notes":"string"}]}
            Use "unit" values like "boxes", "cases", "each", "tubs", or "trays" based on how the product is packaged. Keep "notes" under 15 words explaining what you counted or any uncertainty (e.g. partially hidden items).
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
                confidence = item.optString("confidence").ifBlank { "medium" },
                notes = item.optString("notes")
            )
        }
        return if (suggestions.isEmpty()) VisionCountResult.Failure("AI did not detect any items. Try a clearer photo or count manually.")
        else VisionCountResult.Success(suggestions)
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return text.substring(start, end + 1)
    }
}
