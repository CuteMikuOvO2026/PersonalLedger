package com.example.personalledger

import java.util.Locale

/**
 * 账本历史记录数据类。
 *
 * 金额与时间以类型化字段存储（金额单位：分；时间：epoch 毫秒），
 * 避免在统计/筛选/图表中反复做字符串解析。[amount]/[time] 仅作为展示用的便捷属性。
 */
data class LedgerItem(
    val id: String = "",  // 唯一标识（空串表示旧数据，加载时自动补齐 UUID）
    val amountCents: Long = 0L, // 金额（单位：分），避免浮点误差
    val note: String = "",     // 备注
    val timeMillis: Long = 0L, // 时间（epoch 毫秒）
    val isExpense: Boolean = true, // 是否为支出（true=支出，false=收入），默认支出
    val categoryName: String = "其他", // 分类名称
    val categoryIconRes: Int = 0 // 分类图标资源ID
) {
    /** 展示用金额字符串，如 "12.50"。 */
    val amount: String get() = String.format(Locale.getDefault(), "%.2f", amountCents / 100.0)

    /** 展示用时间字符串，如 "2026-05-01 12:30"。 */
    val time: String get() = LedgerItemMappers.formatMillis(timeMillis)
}
