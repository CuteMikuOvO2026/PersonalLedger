package com.example.personalledger

import androidx.room.Entity

/**
 * 预算规则的 Room 实体。
 *
 * 存的是一条**长期生效的规则**，而不是「每个周期一条记录」：
 * 「餐饮每月 1500」就是一条规则，每个月的进度都用当月支出重新计算，
 * 因此不需要为每个月建行，也不需要定期生成数据。
 *
 * 用 `(periodType, categoryName)` 做**复合主键**而不是自增 id：
 * 「同一周期 + 同一分类」在语义上就只能有一条规则，
 * 让主键直接表达这个约束，就不可能出现重复规则。
 */
@Entity(
    tableName = "budgets",
    primaryKeys = ["periodType", "categoryName"]
)
data class BudgetEntity(
    /** 周期类型，取值见 [BudgetPeriod.key]。 */
    val periodType: String,
    /** 分类名；**空串**表示「不限分类」的总预算。 */
    val categoryName: String,
    /** 限额，单位「分」（与账目金额同单位，避免浮点误差）。 */
    val limitCents: Long
)
