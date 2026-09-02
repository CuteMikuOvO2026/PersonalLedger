package com.example.personalledger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/**
 * 仅负责轻量设置的持久化：月预算、自定义分类。
 *
 * 账本条目历史已迁移到 Room（见 [LedgerRepository]），这里仍保留旧的
 * DataStore 历史 key，仅用于一次性迁移读取。
 */
class DataStoreManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        val BUDGET_KEY = doublePreferencesKey("ledger_budget_decimal")
        val LEGACY_BUDGET_KEY = intPreferencesKey("ledger_budget")
        val CUSTOM_CATEGORIES_KEY = stringPreferencesKey("custom_categories")
        val AUTO_BOOKKEEPING_KEY = booleanPreferencesKey("auto_bookkeeping_enabled")

        // 旧的条目历史 key（仅迁移时读取）
        val LEGACY_HISTORY_LIST_KEY = stringPreferencesKey("ledger_history_list")
    }

    val budgetFlow: Flow<Double> = context.dataStore.data.map { preferences ->
        preferences[BUDGET_KEY] ?: preferences[LEGACY_BUDGET_KEY]?.toDouble() ?: 5000.0
    }

    val customCategoriesFlow: Flow<List<CategoryItem>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[CUSTOM_CATEGORIES_KEY]
        if (jsonString.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<CategoryItem>>() {}.type
                gson.fromJson<List<CategoryItem>>(jsonString, type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun saveBudget(budget: Double) {
        context.dataStore.edit { preferences ->
            preferences[BUDGET_KEY] = budget
            preferences.remove(LEGACY_BUDGET_KEY)
        }
    }

    suspend fun saveCustomCategories(categories: List<CategoryItem>) {
        val jsonString = gson.toJson(categories)
        context.dataStore.edit { preferences ->
            preferences[CUSTOM_CATEGORIES_KEY] = jsonString
        }
    }

    // ---------- 自动记账开关 ----------

    val autoBookkeepingFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_BOOKKEEPING_KEY] ?: false
    }

    suspend fun isAutoBookkeepingEnabled(): Boolean =
        context.dataStore.data.first()[AUTO_BOOKKEEPING_KEY] ?: false

    suspend fun setAutoBookkeepingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_BOOKKEEPING_KEY] = enabled
        }
    }

    suspend fun clearAllData() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    // ---------- 一次性迁移：读取旧 DataStore JSON 历史 ----------

    suspend fun readLegacyHistory(): List<LedgerItem> {
        val prefs = context.dataStore.data.first()
        val jsonString = prefs[LEGACY_HISTORY_LIST_KEY]
        if (jsonString.isNullOrEmpty()) return emptyList()
        return try {
            val type = object : TypeToken<List<LedgerItem>>() {}.type
            val rawList = gson.fromJson<List<LedgerItem>>(jsonString, type) ?: emptyList()
            rawList.map { if (it.id.isEmpty()) it.copy(id = java.util.UUID.randomUUID().toString()) else it }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun clearLegacyHistory() {
        context.dataStore.edit { preferences ->
            preferences.remove(LEGACY_HISTORY_LIST_KEY)
        }
    }
}
