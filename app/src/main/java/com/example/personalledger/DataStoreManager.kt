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

class DataStoreManager(private val context: Context) {

    private val gson = Gson()

    companion object {
        val AMOUNT_KEY = doublePreferencesKey("ledger_amount_decimal")
        val LEGACY_AMOUNT_KEY = intPreferencesKey("ledger_amount")
        val HISTORY_LIST_KEY = stringPreferencesKey("ledger_history_list")
        val BUDGET_KEY = doublePreferencesKey("ledger_budget_decimal")
        val LEGACY_BUDGET_KEY = intPreferencesKey("ledger_budget")

        val USERNAME_KEY = stringPreferencesKey("auth_username")
        val PASSWORD_KEY = stringPreferencesKey("auth_password")
        val IS_LOGGED_IN_KEY = booleanPreferencesKey("auth_is_logged_in")
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

    val isLoggedInFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[IS_LOGGED_IN_KEY] ?: false
    }

    suspend fun isLoggedIn(): Boolean = isLoggedInFlow.first()

    suspend fun registerUser(username: String, password: String) {
        context.dataStore.edit { preferences ->
            preferences[USERNAME_KEY] = username
            preferences[PASSWORD_KEY] = password
            preferences[IS_LOGGED_IN_KEY] = true
        }
    }

    suspend fun loginUser(username: String, password: String): Boolean {
        val prefs = context.dataStore.data.first()
        val savedUsername = prefs[USERNAME_KEY]
        val savedPassword = prefs[PASSWORD_KEY]
        val success = savedUsername == username && savedPassword == password
        if (success) {
            context.dataStore.edit { preferences ->
                preferences[IS_LOGGED_IN_KEY] = true
            }
        }
        return success
    }

    suspend fun logoutUser() {
        context.dataStore.edit { preferences ->
            preferences[IS_LOGGED_IN_KEY] = false
        }
    }

    suspend fun hasRegisteredUser(): Boolean {
        val prefs = context.dataStore.data.first()
        return !prefs[USERNAME_KEY].isNullOrBlank() && !prefs[PASSWORD_KEY].isNullOrBlank()
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
}
