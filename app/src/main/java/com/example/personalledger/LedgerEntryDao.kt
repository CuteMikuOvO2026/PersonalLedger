package com.example.personalledger

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
