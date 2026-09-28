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
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 仅负责轻量设置的持久化：自定义分类、自动记账开关。
 *
 * 账本条目已迁移到 Room（见 [LedgerRepository]），预算也已迁移到 Room 的 `budgets` 表
 * （见 [BudgetEntity]）；这里保留旧的预算 key，仅用于一次性搬迁读取。
 *
 * 注意：**主题模式不在这里**，它只存一份在 [ThemeSettings] 的 SharedPreferences——
 * 主题需要在 Application.onCreate 同步读取，异步的 DataStore 做不到，
 * 存两份也会让界面回显与实际应用的值不一致。
 */
class DataStoreManager(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LedgerItem::class.java, LedgerItemJsonAdapter())
        .create()

    companion object {
        val BUDGET_KEY = doublePreferencesKey("ledger_budget_decimal")
        val LEGACY_BUDGET_KEY = intPreferencesKey("ledger_budget")
        val CUSTOM_CATEGORIES_KEY = stringPreferencesKey("custom_categories")
        val AUTO_BOOKKEEPING_KEY = booleanPreferencesKey("auto_bookkeeping_enabled")

        // 旧的条目历史 key（仅迁移时读取）
        val LEGACY_HISTORY_LIST_KEY = stringPreferencesKey("ledger_history_list")
    }

    /**
     * 读取旧版「月度总预算」的原始值，**未设置过时返回 null**。
     *
     * 这里刻意不给兜底默认值：预算已经迁移到 Room 的 `budgets` 表
     * （见 [BudgetEntity]），本方法只在一次性搬迁时用来判断
     * 「用户当年是否真的设置过预算」，不能凭默认值凭空造出一条预算。
     */
    suspend fun readLegacyBudget(): Double? {
        val preferences = context.dataStore.data.first()
        return preferences[BUDGET_KEY] ?: preferences[LEGACY_BUDGET_KEY]?.toDouble()
    }

    /** 搬迁完成后清掉旧值，避免用户删光预算后又被旧值「复活」。 */
    suspend fun clearLegacyBudget() {
        context.dataStore.edit { preferences ->
            preferences.remove(BUDGET_KEY)
            preferences.remove(LEGACY_BUDGET_KEY)
        }
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
