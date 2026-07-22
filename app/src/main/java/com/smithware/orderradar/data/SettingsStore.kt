package com.smithware.orderradar.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("order_radar_settings")

/**
 * Which cloud AI service powers AI Shelf Count. Neither is favored -- both are
 * first-class, equally-supported options, so a manager with only an OpenAI key
 * (or only an Anthropic key) gets the exact same feature either way.
 */
enum class VisionProvider(val storageValue: String) {
    OPENAI("openai"),
    ANTHROPIC("anthropic");

    companion object {
        fun fromStorage(value: String): VisionProvider = entries.firstOrNull { it.storageValue == value } ?: OPENAI
    }
}

class SettingsStore(private val context: Context) {
    private val safetyKey = doublePreferencesKey("default_safety_stock")
    private val windowKey = intPreferencesKey("movement_average_window")
    private val visionProviderKey = stringPreferencesKey("vision_provider")
    // "vision_api_key"/"vision_model" predate multi-provider support and were always the
    // Anthropic key/model -- kept as-is so an existing saved Anthropic key isn't lost.
    private val anthropicVisionApiKeyKey = stringPreferencesKey("vision_api_key")
    private val anthropicVisionModelKey = stringPreferencesKey("vision_model")
    private val openAiVisionApiKeyKey = stringPreferencesKey("openai_vision_api_key")
    private val openAiVisionModelKey = stringPreferencesKey("openai_vision_model")

    val settings = context.dataStore.data.map { prefs ->
        AppSettings(
            defaultSafetyStock = prefs[safetyKey] ?: 1.0,
            movementAverageWindow = prefs[windowKey] ?: 14,
            visionProvider = VisionProvider.fromStorage(prefs[visionProviderKey] ?: VisionProvider.OPENAI.storageValue),
            anthropicVisionApiKey = prefs[anthropicVisionApiKeyKey] ?: "",
            anthropicVisionModel = prefs[anthropicVisionModelKey] ?: DEFAULT_ANTHROPIC_VISION_MODEL,
            openAiVisionApiKey = prefs[openAiVisionApiKeyKey] ?: "",
            openAiVisionModel = prefs[openAiVisionModelKey] ?: DEFAULT_OPENAI_VISION_MODEL
        )
    }

    suspend fun update(defaultSafetyStock: Double, movementAverageWindow: Int) {
        context.dataStore.edit {
            it[safetyKey] = defaultSafetyStock
            it[windowKey] = movementAverageWindow
        }
    }

    suspend fun setVisionProvider(provider: VisionProvider) {
        context.dataStore.edit { it[visionProviderKey] = provider.storageValue }
    }

    suspend fun updateAnthropicVisionSettings(apiKey: String, model: String) {
        context.dataStore.edit {
            it[anthropicVisionApiKeyKey] = apiKey.trim()
            it[anthropicVisionModelKey] = model.ifBlank { DEFAULT_ANTHROPIC_VISION_MODEL }
        }
    }

    suspend fun updateOpenAiVisionSettings(apiKey: String, model: String) {
        context.dataStore.edit {
            it[openAiVisionApiKeyKey] = apiKey.trim()
            it[openAiVisionModelKey] = model.ifBlank { DEFAULT_OPENAI_VISION_MODEL }
        }
    }

    companion object {
        const val DEFAULT_ANTHROPIC_VISION_MODEL = "claude-sonnet-5"
        const val DEFAULT_OPENAI_VISION_MODEL = "gpt-4o-mini"
    }
}

data class AppSettings(
    val defaultSafetyStock: Double = 1.0,
    val movementAverageWindow: Int = 14,
    val visionProvider: VisionProvider = VisionProvider.OPENAI,
    val anthropicVisionApiKey: String = "",
    val anthropicVisionModel: String = SettingsStore.DEFAULT_ANTHROPIC_VISION_MODEL,
    val openAiVisionApiKey: String = "",
    val openAiVisionModel: String = SettingsStore.DEFAULT_OPENAI_VISION_MODEL
) {
    val activeVisionApiKey: String get() = if (visionProvider == VisionProvider.OPENAI) openAiVisionApiKey else anthropicVisionApiKey
    val activeVisionModel: String get() = if (visionProvider == VisionProvider.OPENAI) openAiVisionModel else anthropicVisionModel
}
