package com.example.personalledger

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 首页概览统计的数据库聚合结果（金额单位为“分”）。
 *
 * 由 SQL 直接聚合得出，避免把整张表读进内存再逐条相加。
 */
data class HomeTotals(
    val todayIncomeCents: Long,
    val todayExpenseCents: Long,
    val monthIncomeCents: Long,
    val monthExpenseCents: Long
)

/**
 * 单个收支方向的「今日 / 本月」合计（金额单位为“分”）。
 *
 * 首页概览拆成收入、支出两条查询各自聚合，是为了让时间条件能进入 `WHERE`：
 * 时间条件若写在 `CASE WHEN` 里（见改造前的写法），SQLite 无法用它裁剪索引，
 * 只能整表扫描；放进 `WHERE` 后即可走 `isExpense + timeMillis` 复合索引做范围扫描。
 */
data class DirectionTotals(
    val todayCents: Long,
    val monthCents: Long
)

/** 报表饼图的聚合结果：某个分类的支出合计（金额单位为“分”）。 */
data class CategoryTotal(
    val categoryName: String,
    val totalCents: Long
)

/**
 * 报表柱状图的聚合结果：最近 7 天每天的支出合计（金额单位为“分”）。
 *
 * 固定 7 个分桶由 SQL 一次算出，避免把 7 天的记录整批读进内存再分组；
 * 分桶边界由 [LedgerStats.weeklyDayRanges] 按本地时区算好后作为参数传入。
 */
data class WeeklyDayTotals(
    val day0: Long,
    val day1: Long,
    val day2: Long,
    val day3: Long,
    val day4: Long,
    val day5: Long,
    val day6: Long
) {
    /** 按时间正序（最早 → 最新）取出 7 天的合计。 */
    fun toList(): List<Long> = listOf(day0, day1, day2, day3, day4, day5, day6)
}

/** 报表摘要的聚合结果：全表收入/支出合计与记录总数（金额单位为“分”）。 */
data class ReportTotals(
    val incomeCents: Long,
    val expenseCents: Long,
    val entryCount: Int
)

@Dao
interface LedgerEntryDao {

    @Query("SELECT * FROM ledger_entries ORDER BY timeMillis ASC")
    suspend fun getAll(): List<LedgerEntryEntity>

    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LedgerEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<LedgerEntryEntity>)

    @Delete
    suspend fun delete(entry: LedgerEntryEntity)

    @Query("DELETE FROM ledger_entries")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM ledger_entries WHERE amountCents = :amountCents AND isExpense = :isExpense AND timeMillis > :sinceMillis")
    suspend fun countRecent(amountCents: Long, isExpense: Boolean, sinceMillis: Long): Int

    /** 在单个事务内清空并重写全部条目，避免还原备份时中途失败导致数据丢失。 */
    @Transaction
    suspend fun replaceAll(entries: List<LedgerEntryEntity>) {
        deleteAll()
        upsertAll(entries)
    }

    /**
     * 按类型/分类、备注搜索、金额区间、日期区间下推到数据库筛选，并只取一页数据。
     *
     * 无对应筛选时传默认值：typeAll=true 表示“全部”，其余布尔为 false、category 为 null。
     * `timeMillis` 相同时以 `id` 兜底排序，保证翻页时记录不会在页与页之间跳动。
     */
    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE (
            :typeAll = 1
            OR (:typeExpense = 1 AND isExpense = 1)
            OR (:typeIncome = 1 AND isExpense = 0)
            OR (:category IS NOT NULL AND categoryName = :category)
        )
        AND (:search = '' OR note LIKE '%' || :search || '%')
        AND (:minCents IS NULL OR amountCents >= :minCents)
        AND (:maxCents IS NULL OR amountCents <= :maxCents)
        AND (:dateFrom IS NULL OR timeMillis >= :dateFrom)
        AND (:dateTo IS NULL OR timeMillis <= :dateTo)
        ORDER BY timeMillis DESC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    fun queryFilteredPage(
        typeAll: Boolean,
        typeExpense: Boolean,
        typeIncome: Boolean,
        category: String?,
        search: String,
        minCents: Long?,
        maxCents: Long?,
        dateFrom: Long?,
        dateTo: Long?,
        limit: Int,
        offset: Int
    ): Flow<List<LedgerEntryEntity>>

    /** [queryFilteredPage] 相同筛选条件下的总条数，用于计算总页数。 */
    @Query(
        """
        SELECT COUNT(*) FROM ledger_entries
        WHERE (
            :typeAll = 1
            OR (:typeExpense = 1 AND isExpense = 1)
            OR (:typeIncome = 1 AND isExpense = 0)
            OR (:category IS NOT NULL AND categoryName = :category)
        )
        AND (:search = '' OR note LIKE '%' || :search || '%')
        AND (:minCents IS NULL OR amountCents >= :minCents)
        AND (:maxCents IS NULL OR amountCents <= :maxCents)
        AND (:dateFrom IS NULL OR timeMillis >= :dateFrom)
        AND (:dateTo IS NULL OR timeMillis <= :dateTo)
        """
    )
    fun countFiltered(
        typeAll: Boolean,
        typeExpense: Boolean,
        typeIncome: Boolean,
        category: String?,
        search: String,
        minCents: Long?,
        maxCents: Long?,
        dateFrom: Long?,
        dateTo: Long?
    ): Flow<Int>

    /** 首页筛选弹窗的分类候选（按最近使用时间倒序，等价于历史记录的“分类去重”）。 */
    @Query(
        """
        SELECT categoryName FROM ledger_entries
        WHERE categoryName != ''
        GROUP BY categoryName
        ORDER BY MAX(timeMillis) DESC
        """
    )
    fun observeCategoryNames(): Flow<List<String>>

    /**
     * 首页概览：**单个收支方向**的「今日 / 本月」合计。
     *
     * 关键在时间条件的位置——`timeMillis BETWEEN :monthStart AND :monthEnd` 放在 `WHERE` 里，
     * 让 SQLite 能用 `isExpense + timeMillis` 复合索引直接定位到「该方向 + 本月」这一段，
     * 只扫本月的几十条记录；今天的那部分再用 `CASE` 从中挑出来（今天必然落在本月区间内）。
     *
     * 改造前把 4 个方向×区间的条件全写在 `SUM(CASE WHEN …)` 里且没有 `WHERE`，
     * 索引完全用不上，每次都要整表扫描。
     *
     * 两个方向各查一次、再由 [LedgerStats.buildHomeTotals] 组装成 [HomeTotals]。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN timeMillis BETWEEN :todayStart AND :todayEnd THEN amountCents ELSE 0 END), 0) AS todayCents,
            COALESCE(SUM(amountCents), 0) AS monthCents
        FROM ledger_entries
        WHERE isExpense = :isExpense
          AND timeMillis BETWEEN :monthStart AND :monthEnd
        """
    )
    fun observeDirectionTotals(
        isExpense: Boolean,
        todayStart: Long,
        todayEnd: Long,
        monthStart: Long,
        monthEnd: Long
    ): Flow<DirectionTotals>

    // ---------- 报表页聚合（全部下推到数据库，不再读取整表） ----------

    /**
     * 按分类聚合支出，供报表饼图与预算进度共用。
     *
     * [startMillis] / [endMillis] 传 null 表示该侧不限（报表饼图的「全部时间」即两端都传 null）；
     * 预算进度会同时传入当期起止，保证「当期已花多少」与统计口径一致。
     *
     * 由 `isExpense + categoryName + timeMillis` 复合索引覆盖：时间区间在索引内过滤，
     * 且索引本身已按分类有序，分组时不需要临时排序。
     */
    @Query(
        """
        SELECT categoryName AS categoryName, SUM(amountCents) AS totalCents
        FROM ledger_entries
        WHERE isExpense = 1
          AND (:startMillis IS NULL OR timeMillis >= :startMillis)
          AND (:endMillis IS NULL OR timeMillis <= :endMillis)
        GROUP BY categoryName
        ORDER BY totalCents DESC
        """
    )
    fun observeExpenseByCategory(startMillis: Long?, endMillis: Long?): Flow<List<CategoryTotal>>

    /**
     * 报表柱状图：最近 7 天每天的支出合计，一次查询得到固定 7 个分桶。
     *
     * 分桶区间左闭右开（`>= 当日起点` 且 `< 次日起点`），避免边界时刻被两天重复统计；
     * 桶边界由 [LedgerStats.weeklyDayRanges] 在 Kotlin 侧按本地时区算好传入，
     * 与首页「今日 / 本月」保持同一套时区口径。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN timeMillis >= :start0 AND timeMillis < :start1 THEN amountCents ELSE 0 END), 0) AS day0,
            COALESCE(SUM(CASE WHEN timeMillis >= :start1 AND timeMillis < :start2 THEN amountCents ELSE 0 END), 0) AS day1,
            COALESCE(SUM(CASE WHEN timeMillis >= :start2 AND timeMillis < :start3 THEN amountCents ELSE 0 END), 0) AS day2,
            COALESCE(SUM(CASE WHEN timeMillis >= :start3 AND timeMillis < :start4 THEN amountCents ELSE 0 END), 0) AS day3,
            COALESCE(SUM(CASE WHEN timeMillis >= :start4 AND timeMillis < :start5 THEN amountCents ELSE 0 END), 0) AS day4,
            COALESCE(SUM(CASE WHEN timeMillis >= :start5 AND timeMillis < :start6 THEN amountCents ELSE 0 END), 0) AS day5,
            COALESCE(SUM(CASE WHEN timeMillis >= :start6 AND timeMillis < :endMillis THEN amountCents ELSE 0 END), 0) AS day6
        FROM ledger_entries
        WHERE isExpense = 1 AND timeMillis >= :start0 AND timeMillis < :endMillis
        """
    )
    fun observeWeeklyExpenseTotals(
        start0: Long,
        start1: Long,
        start2: Long,
        start3: Long,
        start4: Long,
        start5: Long,
        start6: Long,
        endMillis: Long
    ): Flow<WeeklyDayTotals>

    /** 报表摘要所需的「全表收入 / 支出合计 + 记录总数」，一次聚合得出。 */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN isExpense = 0 THEN amountCents ELSE 0 END), 0) AS incomeCents,
            COALESCE(SUM(CASE WHEN isExpense = 1 THEN amountCents ELSE 0 END), 0) AS expenseCents,
            COUNT(*) AS entryCount
        FROM ledger_entries
        """
    )
    fun observeReportTotals(): Flow<ReportTotals>

    // ---------- 报表页下钻（点击某天 / 某分类时按需查询，不再预读整表） ----------

    /** 点击柱状图某一天：该日（左闭右开区间）的支出明细。 */
    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE isExpense = 1 AND timeMillis >= :startMillis AND timeMillis < :endMillis
        ORDER BY timeMillis DESC, id ASC
        """
    )
    suspend fun getExpenseEntriesBetween(startMillis: Long, endMillis: Long): List<LedgerEntryEntity>

    /** 点击饼图某分类：该分类在指定时间范围内（[startMillis] 为 null 表示全部）的支出明细。 */
    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE isExpense = 1 AND categoryName = :categoryName
          AND (:startMillis IS NULL OR timeMillis >= :startMillis)
        ORDER BY timeMillis DESC, id ASC
        """
    )
    suspend fun getExpenseEntriesByCategory(
        categoryName: String,
        startMillis: Long?
    ): List<LedgerEntryEntity>
}
