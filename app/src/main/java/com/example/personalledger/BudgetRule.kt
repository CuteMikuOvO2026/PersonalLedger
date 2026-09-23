package com.example.personalledger

/** 预算周期。 */
enum class BudgetPeriod(val key: String) {
    MONTH("month"),
    WEEK("week");

    companion object {
        fun fromKey(key: String): BudgetPeriod = entries.firstOrNull { it.key == key } ?: MONTH
    }
}

/**
 * 预算规则（界面模型）。
 *
 * [categoryName] 为 `null` 表示「不限分类」的总预算——界面上用 null 表达，
 * 数据库里用空串表达（Room 列非空更好处理），两者的转换收敛在 [toEntity] / [toRule]。
 *
 * 没有单独的 id：`(period, categoryName)` 本身就是业务主键（见 [BudgetEntity]）。
 */
data class BudgetRule(
    val period: BudgetPeriod,
    val categoryName: String?,
    val limitCents: Long
) {
    /** 是否为「不限分类」的总预算。 */
    val isOverall: Boolean get() = categoryName.isNullOrEmpty()

    /** 界面列表用的稳定标识。 */
    val key: String get() = "${period.key}:${categoryName.orEmpty()}"

    fun toEntity(): BudgetEntity = BudgetEntity(
        periodType = period.key,
        categoryName = categoryName.orEmpty(),
        limitCents = limitCents
    )
}

fun BudgetEntity.toRule(): BudgetRule = BudgetRule(
    period = BudgetPeriod.fromKey(periodType),
    categoryName = categoryName.ifEmpty { null },
    limitCents = limitCents
)
