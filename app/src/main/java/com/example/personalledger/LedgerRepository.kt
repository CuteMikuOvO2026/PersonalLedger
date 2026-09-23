package com.example.personalledger

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToLong

/**
 * 数据仓库：账本条目与预算规则走 Room，自定义分类 / 自动记账开关 / 主题模式走 DataStore。
 * 负责实体映射、旧存储的一次性搬迁（DataStore JSON 历史 → Room；旧月度总预算 → budgets 表）、
 * 以及备份还原。
 */
class LedgerRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.ledgerEntryDao()
    private val budgetDao = db.budgetDao()
    private val dataStoreManager = DataStoreManager(context)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LedgerItem::class.java, LedgerItemJsonAdapter())
        .create()

    private val migrateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 注意：这里**故意不提供「整表响应式读取」的入口**。
     *
     * 首页列表走分页查询、首页统计走数据库聚合、报表页的图表与下钻也全部下推到 SQL，
     * 因此任何界面都不需要把 `ledger_entries` 整表读进内存。需要全量数据的场景只有
     * 备份导出与 CSV 导出（[getAll]，一次性读取），用挂起函数即可。
     */
    val categoryNames: Flow<List<String>> = dao.observeCategoryNames()

    /** 全部预算规则（含总预算与分类预算、月/周周期）。条数很少，整体读取即可。 */
    val budgets: Flow<List<BudgetRule>> =
        budgetDao.observeAll().map { list -> list.map { it.toRule() } }

    val customCategories: Flow<List<CategoryItem>> = dataStoreManager.customCategoriesFlow

    val autoBookkeepingEnabled: Flow<Boolean> = dataStoreManager.autoBookkeepingFlow

    val themeMode: Flow<String> = dataStoreManager.themeModeFlow

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
            migrateLegacyBudgetIfNeeded()
        }
    }

    /**
     * 一次性把旧版「DataStore 里的单个月度总预算」搬进 `budgets` 表。
     *
     * 触发条件：`budgets` 表为空 **且** 旧预算确实被设置过（[DataStoreManager.readLegacyBudget]
     * 返回非 null 且大于 0）。搬迁后清掉旧值，这样用户以后把预算全删了也不会被旧值「复活」。
     *
     * 这条逻辑放在 Kotlin 而不是 SQL 迁移里，是因为它跨了两个存储（DataStore → Room）。
     */
    private suspend fun migrateLegacyBudgetIfNeeded() {
        if (budgetDao.count() > 0) return
        val legacyBudget = dataStoreManager.readLegacyBudget() ?: return
        if (legacyBudget <= 0) return

        budgetDao.upsert(
            BudgetRule(
                period = BudgetPeriod.MONTH,
                categoryName = null,
                limitCents = (legacyBudget * 100).roundToLong()
            ).toEntity()
        )
        dataStoreManager.clearLegacyBudget()
    }

    suspend fun add(item: LedgerItem) =
        dao.upsert(LedgerItemMappers.itemToEntity(item))

    suspend fun delete(item: LedgerItem) =
        dao.delete(LedgerItemMappers.itemToEntity(item))

    suspend fun update(old: LedgerItem, new: LedgerItem) =
        dao.upsert(LedgerItemMappers.itemToEntity(new.copy(id = old.id)))

    suspend fun getAll(): List<LedgerItem> =
        dao.getAll().map { LedgerItemMappers.entityToItem(it) }

    /**
     * 账本里是否有任何记录。
     *
     * 供「账本为空时不要导出」这类判断使用——比 [getAll] 少一次整表读取与映射。
     */
    suspend fun hasAnyEntry(): Boolean = dao.countAll() > 0

    /**
     * 批量追加账目（CSV 导入用）。
     *
     * 刻意与 [restoreBackup] 区分开：导入 CSV 是**合并**，不会清空现有记录；
     * 还原备份才是整体覆盖。
     */
    suspend fun addAll(items: List<LedgerItem>) {
        if (items.isEmpty()) return
        dao.upsertAll(items.map { LedgerItemMappers.itemToEntity(it) })
    }

    // ---------- 预算 ----------

    /**
     * 保存一条预算规则。
     *
     * `(周期, 分类)` 是复合主键，因此「同一周期同一分类」重复保存就是覆盖更新，
     * 不会产生重复规则。
     */
    suspend fun saveBudget(rule: BudgetRule) = budgetDao.upsert(rule.toEntity())

    suspend fun deleteBudget(rule: BudgetRule) = budgetDao.delete(rule.toEntity())

    /**
     * 当期按分类聚合的支出，供预算进度使用。
     *
     * 与报表饼图共用同一条 SQL，只是这里会传入完整区间（两端都包含），
     * 保证「当期已花多少」与「当期」这个定义严格一致。
     */
    fun periodCategoryTotals(range: PeriodRange): Flow<List<CategoryTotal>> =
        dao.observeExpenseByCategory(range.startMillis, range.endMillis)

    suspend fun addCustomCategory(name: String, iconRes: Int, type: String, color: Int) {
        val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
        current.add(CategoryItem(name, iconRes, type, isCustom = true, color = color))
        dataStoreManager.saveCustomCategories(current)
    }

    suspend fun removeCustomCategory(category: CategoryItem) {
        val current = dataStoreManager.customCategoriesFlow.first().toMutableList()
        current.removeAll { it.name == category.name && it.type == category.type }
        dataStoreManager.saveCustomCategories(current)
    }

    suspend fun setAutoBookkeepingEnabled(enabled: Boolean) = dataStoreManager.setAutoBookkeepingEnabled(enabled)

    suspend fun saveThemeMode(mode: String) = dataStoreManager.saveThemeMode(mode)

    /** 判断在 [sinceMillis] 之后是否已存在同金额、同收支方向的记录，用于自动记账去重。 */
    suspend fun existsRecentEntry(amountCents: Long, isExpense: Boolean, sinceMillis: Long): Boolean =
        dao.countRecent(amountCents, isExpense, sinceMillis) > 0

    /**
     * 首页记录列表：只读取当前页（[limit] 条，从 [offset] 开始）。
     * 筛选、排序、分页全部下推到 Room，记录再多也只加载一页数据。
     */
    fun queryFilteredPage(
        query: LedgerQuery,
        limit: Int,
        offset: Int
    ): Flow<List<LedgerItem>> =
        dao.queryFilteredPage(
            typeAll = query.typeAll,
            typeExpense = query.typeExpense,
            typeIncome = query.typeIncome,
            category = query.category,
            search = query.search,
            minCents = query.minCents,
            maxCents = query.maxCents,
            dateFrom = query.dateFrom,
            dateTo = query.dateTo,
            limit = limit,
            offset = offset
        ).map { list -> list.map { LedgerItemMappers.entityToItem(it) } }

    /** [queryFilteredPage] 相同筛选条件下的总条数，用于计算总页数。 */
    fun countFiltered(query: LedgerQuery): Flow<Int> =
        dao.countFiltered(
            typeAll = query.typeAll,
            typeExpense = query.typeExpense,
            typeIncome = query.typeIncome,
            category = query.category,
            search = query.search,
            minCents = query.minCents,
            maxCents = query.maxCents,
            dateFrom = query.dateFrom,
            dateTo = query.dateTo
        )

    /**
     * 首页概览的今日/本月收支聚合。
     *
     * 收入、支出各发一条查询（时间条件在 `WHERE` 里，可走 `isExpense + timeMillis` 索引做范围扫描），
     * 再组装成界面需要的 [HomeTotals]；比原先「整表扫描 + 4 个 CASE」快得多。
     */
    fun homeTotals(
        todayStart: Long,
        todayEnd: Long,
        monthStart: Long,
        monthEnd: Long
    ): Flow<HomeTotals> =
        combine(
            dao.observeDirectionTotals(
                isExpense = false,
                todayStart = todayStart,
                todayEnd = todayEnd,
                monthStart = monthStart,
                monthEnd = monthEnd
            ),
            dao.observeDirectionTotals(
                isExpense = true,
                todayStart = todayStart,
                todayEnd = todayEnd,
                monthStart = monthStart,
                monthEnd = monthEnd
            )
        ) { income, expense ->
            LedgerStats.buildHomeTotals(income = income, expense = expense)
        }

    // ---------- 报表页聚合（全部由数据库算出，不读取整表） ----------

    /** 报表摘要：全表收入/支出合计与记录总数。 */
    fun reportTotals(): Flow<ReportTotals> = dao.observeReportTotals()

    /** 报表饼图：按分类聚合支出；两端传 null 表示不限时间（「全部」范围）。 */
    fun expenseByCategory(startMillis: Long?, endMillis: Long?): Flow<List<CategoryTotal>> =
        dao.observeExpenseByCategory(startMillis, endMillis)

    /**
     * 报表柱状图：最近 [LedgerStats.WEEKLY_DAYS] 天每日支出合计。
     *
     * [ranges] 必须由 [LedgerStats.weeklyDayRanges] 产出（恰好 7 天、左闭右开），
     * 这样图上的标签与 SQL 的分桶边界必然同源。
     */
    fun weeklyExpenseTotals(ranges: List<DayRange>): Flow<WeeklyDayTotals> {
        require(ranges.size == LedgerStats.WEEKLY_DAYS) {
            "柱状图分桶需要恰好 ${LedgerStats.WEEKLY_DAYS} 天，实际 ${ranges.size} 天"
        }
        return dao.observeWeeklyExpenseTotals(
            start0 = ranges[0].startMillis,
            start1 = ranges[1].startMillis,
            start2 = ranges[2].startMillis,
            start3 = ranges[3].startMillis,
            start4 = ranges[4].startMillis,
            start5 = ranges[5].startMillis,
            start6 = ranges[6].startMillis,
            endMillis = ranges[6].endMillis
        )
    }

    // ---------- 报表页下钻（点击某天 / 某分类时按需查询） ----------

    /** 点击柱状图某一天：该日的支出明细（区间左闭右开）。 */
    suspend fun expenseEntriesBetween(startMillis: Long, endMillis: Long): List<LedgerItem> =
        dao.getExpenseEntriesBetween(startMillis, endMillis).map { LedgerItemMappers.entityToItem(it) }

    /** 点击饼图某分类：该分类在指定时间范围内（[startMillis] 为 null 表示全部）的支出明细。 */
    suspend fun expenseEntriesByCategory(
        categoryName: String,
        startMillis: Long?
    ): List<LedgerItem> =
        dao.getExpenseEntriesByCategory(categoryName, startMillis)
            .map { LedgerItemMappers.entityToItem(it) }

    suspend fun resetAll() {
        dao.deleteAll()
        budgetDao.deleteAll()
        dataStoreManager.clearAllData()
    }

    // ---------- 备份 / 还原 ----------

    /**
     * 备份文件结构。
     *
     * 字段刻意声明为**可空**：Gson 反序列化 Kotlin data class 时不会执行构造器与默认值
     * （它是绕过构造器直接建实例的），因此旧备份里缺失的字段会是 `null` / `0`，
     * 而不是声明处的默认值。用可空类型 + `?:` 兜底，才能安全读取历史备份。
     */
    data class BackupData(
        val version: Int = CURRENT_BACKUP_VERSION,
        val items: List<LedgerItem>? = null,
        val amount: Double = 0.0,
        /** v3 起：结构化的预算规则（含分类预算与月 / 周周期）。 */
        val budgets: List<BudgetEntity>? = null,
        /** v2 及更早的「单个月度总预算」；读取旧备份时用它补一条总预算规则。 */
        val budget: Double = 0.0,
        val customCategories: List<CategoryItem>? = null
    )

    suspend fun getBackupJson(): String {
        val items = getAll()
        val amount = items.sumOf { if (it.isExpense) amountToDouble(it) else -amountToDouble(it) }
        val customCats = dataStoreManager.customCategoriesFlow.first()
        return gson.toJson(
            BackupData(
                items = items,
                amount = amount,
                budgets = budgetDao.getAll(),
                customCategories = customCats
            )
        )
    }

    suspend fun restoreBackup(json: String): Boolean {
        return try {
            val backup = gson.fromJson(json, BackupData::class.java) ?: return false
            val backupItems = backup.items ?: emptyList()
            val fixedItems = backupItems.map {
                if (it.id.isEmpty()) it.copy(id = UUID.randomUUID().toString()) else it
            }
            dao.replaceAll(fixedItems.map { LedgerItemMappers.itemToEntity(it) })
            budgetDao.replaceAll(resolveBackupBudgets(backup))
            dataStoreManager.saveCustomCategories(backup.customCategories ?: emptyList())
            true
        } catch (e: Exception) {
            Log.e("LedgerRepository", "导入备份失败", e)
            false
        }
    }

    /**
     * 兼容 v2 及更早的备份：那时预算只是 DataStore 里的单个 Double。
     *
     * 新版备份带 `budgets` 列表就直接采用；否则用旧的 `budget` 值补一条
     * 「月度总预算」规则，保证老备份导入后预算不丢。
     */
    private fun resolveBackupBudgets(backup: BackupData): List<BudgetEntity> {
        val rules = backup.budgets
        if (!rules.isNullOrEmpty()) return rules
        if (backup.budget <= 0) return emptyList()
        return listOf(
            BudgetRule(
                period = BudgetPeriod.MONTH,
                categoryName = null,
                limitCents = (backup.budget * 100).roundToLong()
            ).toEntity()
        )
    }

    private fun amountToDouble(item: LedgerItem): Double = item.amountCents / 100.0

    // 供导出 CSV 使用
    suspend fun getCsvString(): String {
        val sb = StringBuilder()
        sb.append("\uFEFF") // BOM for Excel Chinese compatibility
        sb.appendLine("时间,类型,分类,金额,备注")
        getAll().forEach { item ->
            val type = if (item.isExpense) "支出" else "收入"
            // 每个单元格都过一遍转义：分类名由用户自定义、可能含逗号；
            // 备注可能以 = / + / - / @ 开头（会被 Excel / WPS 当公式执行），必须中和。
            sb.appendLine(
                listOf(item.time, type, item.categoryName, item.amount, item.note)
                    .joinToString(",") { CsvLedgerParser.escapeCell(it) }
            )
        }
        return sb.toString()
    }

    companion object {
        /**
         * 当前备份格式版本。
         *
         * v2：条目 + 单个月度总预算 + 自定义分类
         * v3：预算升级为结构化规则列表（含分类预算与月 / 周周期）
         */
        const val CURRENT_BACKUP_VERSION = 3
    }
}
