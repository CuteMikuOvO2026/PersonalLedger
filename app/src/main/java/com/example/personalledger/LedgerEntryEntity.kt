package com.example.personalledger

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 账本条目的 Room 实体。
 *
 * 与界面数据模型 [LedgerItem] 的区别：金额以“分”(Long) 存储避免浮点误差，
 * 时间以 epoch 毫秒存储，便于数据库索引与聚合计算，避免逐条解析字符串。
 *
 * 索引说明（原先只有主键，所有查询都要全表扫描 + 排序，记录变多后首页/报表都会变慢）：
 * - `timeMillis`：首页默认列表的 `ORDER BY timeMillis DESC` 翻页、日期区间筛选。
 * - `isExpense + timeMillis`：首页「今日/本月」收支聚合、报表「近 7 天每日支出」分桶。
 * - `categoryName + timeMillis`：分类筛选翻页、首页筛选弹窗的分类去重列表。
 * - `isExpense + categoryName + timeMillis`：报表饼图的 `isExpense=1 + 时间下界 + GROUP BY categoryName`，
 *   三列都在索引里，可做覆盖扫描且分组时无需临时排序。
 *
 * 注意：索引属于 schema 的一部分，改动后必须同步升 [AppDatabase] 的版本号并补对应 Migration。
 */
@Entity(
    tableName = "ledger_entries",
    indices = [
        Index(value = ["timeMillis"]),
        Index(value = ["isExpense", "timeMillis"]),
        Index(value = ["categoryName", "timeMillis"]),
        Index(value = ["isExpense", "categoryName", "timeMillis"])
    ]
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val amountCents: Long,
    val note: String,
    val timeMillis: Long,
    val isExpense: Boolean,
    val categoryName: String,
    val categoryIconRes: Int
)
