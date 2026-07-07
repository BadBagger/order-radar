package com.smithware.orderradar.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("order_radar_settings")

class SettingsStore(private val context: Context) {
    private val safetyKey = doublePreferencesKey("default_safety_stock")
    private val windowKey = intPreferencesKey("movement_average_window")

    val settings = context.dataStore.data.map { prefs ->
        AppSettings(
            defaultSafetyStock = prefs[safetyKey] ?: 1.0,
            movementAverageWindow = prefs[windowKey] ?: 14
        )
    }

    suspend fun update(defaultSafetyStock: Double, movementAverageWindow: Int) {
        context.dataStore.edit {
            it[safetyKey] = defaultSafetyStock
            it[windowKey] = movementAverageWindow
        }
    }
}

data class AppSettings(val defaultSafetyStock: Double = 1.0, val movementAverageWindow: Int = 14)
