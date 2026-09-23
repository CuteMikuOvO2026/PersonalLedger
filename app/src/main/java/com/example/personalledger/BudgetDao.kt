package com.example.personalledger

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 预算规则的读写。
 *
 * 预算条数很少（通常个位数），因此这里不做分页/筛选，直接整体读写；
 * 真正的重活是「当期已花多少」，那部分由 [LedgerEntryDao] 聚合。
 */
@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets ORDER BY periodType ASC, categoryName ASC")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets ORDER BY periodType ASC, categoryName ASC")
    suspend fun getAll(): List<BudgetEntity>

    @Query("SELECT COUNT(*) FROM budgets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(budget: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(budgets: List<BudgetEntity>)

    @Delete
    suspend fun delete(budget: BudgetEntity)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    /** 在单个事务内清空并重写全部预算，供还原备份使用（与账目还原同样的做法）。 */
    @Transaction
    suspend fun replaceAll(budgets: List<BudgetEntity>) {
        deleteAll()
        upsertAll(budgets)
    }
}
