package com.smithware.orderradar.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("order_radar_settings")

class SettingsStore(private val context: Context) {
    private val safetyKey = doublePreferencesKey("default_safety_stock")
    private val windowKey = intPreferencesKey("movement_average_window")
    private val visionApiKeyKey = stringPreferencesKey("vision_api_key")
    private val visionModelKey = stringPreferencesKey("vision_model")

    val settings = context.dataStore.data.map { prefs ->
        AppSettings(
            defaultSafetyStock = prefs[safetyKey] ?: 1.0,
            movementAverageWindow = prefs[windowKey] ?: 14,
            visionApiKey = prefs[visionApiKeyKey] ?: "",
            visionModel = prefs[visionModelKey] ?: DEFAULT_VISION_MODEL
        )
    }

    suspend fun update(defaultSafetyStock: Double, movementAverageWindow: Int) {
        context.dataStore.edit {
            it[safetyKey] = defaultSafetyStock
            it[windowKey] = movementAverageWindow
        }
    }

    suspend fun updateVisionSettings(apiKey: String, model: String) {
        context.dataStore.edit {
            it[visionApiKeyKey] = apiKey.trim()
            it[visionModelKey] = model.ifBlank { DEFAULT_VISION_MODEL }
        }
    }

    companion object {
        const val DEFAULT_VISION_MODEL = "claude-sonnet-5"
    }
}

data class AppSettings(
    val defaultSafetyStock: Double = 1.0,
    val movementAverageWindow: Int = 14,
    val visionApiKey: String = "",
    val visionModel: String = SettingsStore.DEFAULT_VISION_MODEL
)
