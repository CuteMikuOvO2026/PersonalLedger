package com.example.personalledger

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 账本条目的 Room 实体。
 *
 * 与界面数据模型 [LedgerItem] 的区别：金额以“分”(Long) 存储避免浮点误差，
 * 时间以 epoch 毫秒存储，便于数据库索引与聚合计算，避免逐条解析字符串。
 */
@Entity(tableName = "ledger_entries")
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val amountCents: Long,
    val note: String,
    val timeMillis: Long,
    val isExpense: Boolean,
    val categoryName: String,
    val categoryIconRes: Int
)
