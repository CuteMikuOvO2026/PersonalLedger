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

@Dao
interface LedgerEntryDao {

    @Query("SELECT * FROM ledger_entries ORDER BY timeMillis DESC")
    fun observeAll(): Flow<List<LedgerEntryEntity>>

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
     * 首页概览所需的「今日收入/支出 + 本月收入/支出」一次聚合得出。
     * 用 SQL 聚合替代“整表读入内存再求和”，记录很多时首页依然能快速打开。
     */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN isExpense = 0 AND timeMillis BETWEEN :todayStart AND :todayEnd THEN amountCents ELSE 0 END), 0) AS todayIncomeCents,
            COALESCE(SUM(CASE WHEN isExpense = 1 AND timeMillis BETWEEN :todayStart AND :todayEnd THEN amountCents ELSE 0 END), 0) AS todayExpenseCents,
            COALESCE(SUM(CASE WHEN isExpense = 0 AND timeMillis BETWEEN :monthStart AND :monthEnd THEN amountCents ELSE 0 END), 0) AS monthIncomeCents,
            COALESCE(SUM(CASE WHEN isExpense = 1 AND timeMillis BETWEEN :monthStart AND :monthEnd THEN amountCents ELSE 0 END), 0) AS monthExpenseCents
        FROM ledger_entries
        """
    )
    fun observeHomeTotals(
        todayStart: Long,
        todayEnd: Long,
        monthStart: Long,
        monthEnd: Long
    ): Flow<HomeTotals>
}
