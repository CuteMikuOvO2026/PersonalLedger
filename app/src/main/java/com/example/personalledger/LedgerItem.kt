package com.example.personalledger

/**
 * 账本历史记录数据类
 */
data class LedgerItem(
    val amount: String,   // 金额（格式化字符串）
    val note: String,     // 备注
    val time: String,     // 时间（格式化后的字符串）
    val isExpense: Boolean = true, // 是否为支出（true=支出，false=收入），默认支出
    val categoryName: String = "其他", // 分类名称
    val categoryIconRes: Int = android.R.drawable.ic_menu_agenda // 分类图标资源ID
)
