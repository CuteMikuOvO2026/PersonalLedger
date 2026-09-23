package com.example.personalledger

/** 预算执行状态。 */
enum class BudgetState {
    /** 正常。 */
    NORMAL,

    /** 接近上限（达到 [BudgetStats.WARNING_PERCENT]）。 */
    WARNING,

    /** 已超出限额。 */
    OVER
}

/** 某条预算规则在当期的执行情况。 */
data class BudgetProgress(
    val rule: BudgetRule,
    /** 当期已花费（分）。 */
    val spentCents: Long,
    /** 使用率（整数百分比，**可能超过 100**，便于展示真实超支幅度）。 */
    val percent: Int,
    val state: BudgetState
) {
    /** 剩余可用额度（分）；已超支时为 0。 */
    val remainingCents: Long get() = (rule.limitCents - spentCents).coerceAtLeast(0L)

    /** 超支金额（分）；未超支时为 0。 */
    val overspentCents: Long get() = (spentCents - rule.limitCents).coerceAtLeast(0L)
}

/**
 * 预算的纯计算逻辑：把「当期按分类聚合的支出」换算成每条预算规则的执行情况。
 *
 * 与数据库、界面都解耦，便于单元测试；首页进度条配色与预算提醒都以这里的
 * [BudgetState] 为唯一判据，避免两处各写一套阈值。
 */
object BudgetStats {

    /** 达到该使用率即进入「预警」状态（与首页进度条变色的阈值一致）。 */
    const val WARNING_PERCENT = 80

    /** 取「不限分类」的某周期总预算（分）；未设置时返回 0。 */
    fun overallLimitCents(rules: List<BudgetRule>, period: BudgetPeriod): Long =
        rules.firstOrNull { it.period == period && it.isOverall }?.limitCents ?: 0L

    /**
     * 由使用率（整数百分比）判断状态。
     *
     * 供只有百分比、拿不到分值的场景（如首页进度条）复用同一套阈值，
     * 避免阈值在两处各写一份而慢慢漂移。
     */
    fun stateOfPercent(percent: Int): BudgetState = when {
        percent >= 100 -> BudgetState.OVER
        percent >= WARNING_PERCENT -> BudgetState.WARNING
        else -> BudgetState.NORMAL
    }

    /**
     * 按分类聚合结果计算某条规则当期已花费。
     *
     * 总预算（不限分类）取**所有分类支出的合计**；分类预算取该分类的合计，
     * 该分类当期没有记录时视为 0。
     */
    fun spentOf(rule: BudgetRule, categoryTotals: List<CategoryTotal>): Long =
        if (rule.isOverall) {
            categoryTotals.sumOf { it.totalCents }
        } else {
            categoryTotals.firstOrNull { it.categoryName == rule.categoryName }?.totalCents ?: 0L
        }

    fun buildProgress(rule: BudgetRule, categoryTotals: List<CategoryTotal>): BudgetProgress =
        buildProgress(rule, spentOf(rule, categoryTotals))

    /**
     * 用已知的已花金额构建执行情况。
     *
     * 限额为 0（异常数据）时按 0% 处理，避免除零。
     */
    fun buildProgress(rule: BudgetRule, spentCents: Long): BudgetProgress {
        val percent = if (rule.limitCents > 0) {
            ((spentCents.toDouble() / rule.limitCents) * 100).toInt()
        } else {
            0
        }
        val state = when {
            rule.limitCents > 0 && spentCents >= rule.limitCents -> BudgetState.OVER
            percent >= WARNING_PERCENT -> BudgetState.WARNING
            else -> BudgetState.NORMAL
        }
        return BudgetProgress(
            rule = rule,
            spentCents = spentCents,
            percent = percent,
            state = state
        )
    }

    /** 批量计算，保持传入顺序，便于界面按稳定顺序展示。 */
    fun buildAllProgress(
        rules: List<BudgetRule>,
        categoryTotals: List<CategoryTotal>
    ): List<BudgetProgress> = rules.map { buildProgress(it, categoryTotals) }
}
