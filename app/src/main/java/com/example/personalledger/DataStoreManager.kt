package com.example.personalledger

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        val AMOUNT_KEY = doublePreferencesKey("ledger_amount_decimal")
        val LEGACY_AMOUNT_KEY = intPreferencesKey("ledger_amount")
        val HISTORY_LIST_KEY = stringPreferencesKey("ledger_history_list")
        val BUDGET_KEY = doublePreferencesKey("ledger_budget_decimal")
        val LEGACY_BUDGET_KEY = intPreferencesKey("ledger_budget")

    }

    val amountFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[AMOUNT_KEY] ?: preferences[LEGACY_AMOUNT_KEY]?.toDouble() ?: 0.0
    }

    val budgetFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[BUDGET_KEY] ?: preferences[LEGACY_BUDGET_KEY]?.toDouble() ?: 5000.0
    }

    val historyListFlow: Flow<List<LedgerItem>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[HISTORY_LIST_KEY]
        if (jsonString.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<LedgerItem>>() {}.type
                gson.fromJson<List<LedgerItem>>(jsonString, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun saveAmount(amount: Double) {
        context.dataStore.edit { preferences ->
            preferences[AMOUNT_KEY] = amount
            preferences.remove(LEGACY_AMOUNT_KEY)
        }
    }

    suspend fun saveBudget(budget: Double) {
        context.dataStore.edit { preferences ->
            preferences[BUDGET_KEY] = budget
            preferences.remove(LEGACY_BUDGET_KEY)
        }
    }

    suspend fun saveHistoryList(list: List<LedgerItem>) {
        val jsonString = gson.toJson(list)
        context.dataStore.edit { preferences ->
            preferences[HISTORY_LIST_KEY] = jsonString
        }
    }

    suspend fun clearAllData() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    data class BackupData(
        val version: Int = 1,
        val items: List<LedgerItem>,
        val amount: Double,
        val budget: Double
    )

    suspend fun getBackupJson(): String {
        val prefs = context.dataStore.data.first()
        val jsonString = prefs[HISTORY_LIST_KEY].orEmpty()
        val items: List<LedgerItem> = if (jsonString.isNotEmpty()) {
            try {
                val type = object : TypeToken<List<LedgerItem>>() {}.type
                gson.fromJson(jsonString, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val amountValue = prefs[AMOUNT_KEY] ?: prefs[LEGACY_AMOUNT_KEY]?.toDouble() ?: 0.0
        val budgetValue = prefs[BUDGET_KEY] ?: prefs[LEGACY_BUDGET_KEY]?.toDouble() ?: 5000.0

        val backup = BackupData(
            items = items,
            amount = amountValue,
            budget = budgetValue
        )
        return gson.toJson(backup)
    }

    suspend fun restoreFromBackup(json: String): Boolean {
        return try {
            val backup = gson.fromJson(json, BackupData::class.java)
                ?: return false

            context.dataStore.edit { preferences ->
                preferences[HISTORY_LIST_KEY] = gson.toJson(backup.items)
                preferences[AMOUNT_KEY] = backup.amount
                preferences[BUDGET_KEY] = backup.budget
                preferences.remove(LEGACY_AMOUNT_KEY)
                preferences.remove(LEGACY_BUDGET_KEY)
            }
            true
        } catch (e: Exception) {
            Log.e("DataStoreManager", "导入备份失败", e)
            false
        }
    }
}
