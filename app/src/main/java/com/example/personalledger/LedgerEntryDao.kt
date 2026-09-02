package com.example.personalledger

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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
     * 按类型/分类、备注搜索、金额区间、日期区间下推到数据库筛选。
     * 无对应筛选时传默认值：typeAll=true 表示“全部”，其余布尔为 false、category 为 null。
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
        ORDER BY timeMillis DESC
        """
    )
    fun queryFiltered(
        typeAll: Boolean,
        typeExpense: Boolean,
        typeIncome: Boolean,
        category: String?,
        search: String,
        minCents: Long?,
        maxCents: Long?,
        dateFrom: Long?,
        dateTo: Long?
    ): Flow<List<LedgerEntryEntity>>
}
