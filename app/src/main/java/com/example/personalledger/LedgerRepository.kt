package com.example.personalledger

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 数据仓库：账本条目的持久化走 Room，预算与自定义分类继续用 DataStore。
 * 负责实体映射、旧 DataStore JSON 一次性迁移到 Room、以及备份还原。
 */
class LedgerRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.ledgerEntryDao()
    private val dataStoreManager = DataStoreManager(context)
    private val gson = Gson()

    private val migrateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val historyList: Flow<List<LedgerItem>> =
        dao.observeAll().map { list -> list.map { LedgerItemMappers.entityToItem(it) } }

    val budget: Flow<Double> = dataStoreManager.budgetFlow

    val customCategories: Flow<List<CategoryItem>> = dataStoreManager.customCategoriesFlow

    val autoBookkeepingEnabled: Flow<Boolean> = dataStoreManager.autoBookkeepingFlow

    init {
        migrateLegacyDataIfNeeded()
    }

    private fun migrateLegacyDataIfNeeded() {
        migrateScope.launch {
            val legacy = dataStoreManager.readLegacyHistory()
            if (legacy.isNotEmpty()) {
                dao.upsertAll(legacy.map { LedgerItemMappers.itemToEntity(it) })
                dataStoreManager.clearLegacyHistory()
            }
        }
    }

    suspend fun add(item: LedgerItem) =
        dao.upsert(LedgerItemMappers.itemToEntity(item))

    suspend fun delete(item: LedgerItem) =
        dao.delete(LedgerItemMappers.itemToEntity(item))

    suspend fun update(old: LedgerItem, new: LedgerItem) =
        dao.upsert(LedgerItemMappers.itemToEntity(new.copy(id = old.id)))

    suspend fun getAll(): List<LedgerItem> =
        dao.getAll().map { LedgerItemMappers.entityToItem(it) }

    suspend fun saveBudget(budget: Double) = dataStoreManager.saveBudget(budget)

    suspend fun addCustomCategory(name: String, iconRes: Int, type: String) {
        val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
        current.add(CategoryItem(name, iconRes, type, isCustom = true))
        dataStoreManager.saveCustomCategories(current)
    }

    suspend fun removeCustomCategory(category: CategoryItem) {
        val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
        current.removeAll { it.name == category.name && it.type == category.type }
        dataStoreManager.saveCustomCategories(current)
    }

    suspend fun setAutoBookkeepingEnabled(enabled: Boolean) = dataStoreManager.setAutoBookkeepingEnabled(enabled)

    /** 判断在 [sinceMillis] 之后是否已存在同金额、同收支方向的记录，用于自动记账去重。 */
    suspend fun existsRecentEntry(amountCents: Long, isExpense: Boolean, sinceMillis: Long): Boolean =
        dao.countRecent(amountCents, isExpense, sinceMillis) > 0

    suspend fun resetAll() {
        dao.deleteAll()
        dataStoreManager.clearAllData()
    }

    // ---------- 备份 / 还原 ----------

    data class BackupData(
        val version: Int = 2,
        val items: List<LedgerItem>,
        val amount: Double,
        val budget: Double,
        val customCategories: List<CategoryItem> = emptyList()
    )

    suspend fun getBackupJson(): String {
        val items = getAll()
        val amount = items.sumOf { if (it.isExpense) amountToDouble(it) else -amountToDouble(it) }
        val budgetValue = dataStoreManager.budgetFlow.first()
        val customCats = dataStoreManager.customCategoriesFlow.first()
        return gson.toJson(
            BackupData(items = items, amount = amount, budget = budgetValue, customCategories = customCats)
        )
    }

    suspend fun restoreBackup(json: String): Boolean {
        return try {
            val backup = gson.fromJson(json, BackupData::class.java) ?: return false
            val fixedItems = backup.items.map {
                if (it.id.isEmpty()) it.copy(id = UUID.randomUUID().toString()) else it
            }
            dao.deleteAll()
            dao.upsertAll(fixedItems.map { LedgerItemMappers.itemToEntity(it) })
            dataStoreManager.saveBudget(backup.budget)
            dataStoreManager.saveCustomCategories(backup.customCategories)
            true
        } catch (e: Exception) {
            Log.e("LedgerRepository", "导入备份失败", e)
            false
        }
    }

    private fun amountToDouble(item: LedgerItem): Double = item.amount.toDoubleOrNull() ?: 0.0

    // 供导出 CSV 使用
    suspend fun getCsvString(): String {
        val sb = StringBuilder()
        sb.append("\uFEFF") // BOM for Excel Chinese compatibility
        sb.appendLine("时间,类型,分类,金额,备注")
        getAll().forEach { item ->
            val type = if (item.isExpense) "支出" else "收入"
            val escapedNote = item.note.replace("\"", "\"\"")
            sb.appendLine("${item.time},$type,${item.categoryName},\"${item.amount}\",\"$escapedNote\"")
        }
        return sb.toString()
    }
}
