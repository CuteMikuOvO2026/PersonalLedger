package com.example.personalledger

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 使用 Context.dataStore 属性委托来创建 DataStore 实例
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        // 定义存储金额的 Key
        val AMOUNT_KEY = intPreferencesKey("ledger_amount")
        // 定义存储历史记录列表的 Key
        val HISTORY_LIST_KEY = stringPreferencesKey("ledger_history_list")
        // 定义存储月度预算的 Key
        val BUDGET_KEY = intPreferencesKey("ledger_budget")
    }

    // 从 DataStore 读取金额流
    val amountFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[AMOUNT_KEY] ?: 0
    }

    // 从 DataStore 读取预算流，默认 5000
    val budgetFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[BUDGET_KEY] ?: 5000
    }

    // 从 DataStore 读取历史记录列表流
    val historyListFlow: Flow<List<LedgerItem>> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[HISTORY_LIST_KEY]
        if (jsonString.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<LedgerItem>>() {}.type
                gson.fromJson<List<LedgerItem>>(jsonString, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList() // 解析失败时返回空列表防崩溃
            }
        }
    }

    // 异步保存金额到 DataStore
    suspend fun saveAmount(amount: Int) {
        context.dataStore.edit { preferences ->
            preferences[AMOUNT_KEY] = amount
        }
    }

    // 异步保存预算到 DataStore
    suspend fun saveBudget(budget: Int) {
        context.dataStore.edit { preferences ->
            preferences[BUDGET_KEY] = budget
        }
    }

    // 异步保存历史记录列表到 DataStore
    suspend fun saveHistoryList(list: List<LedgerItem>) {
        val jsonString = gson.toJson(list)
        context.dataStore.edit { preferences ->
            preferences[HISTORY_LIST_KEY] = jsonString
        }
    }

    // 清空所有数据
    suspend fun clearAllData() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
