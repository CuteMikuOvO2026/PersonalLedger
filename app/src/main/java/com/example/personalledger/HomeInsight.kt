package com.example.personalledger

/**
 * 首页洞察数据。
 *
 * 全部围绕「本月」与「上月」两个区间，用于在首页给出一句话级别的消费概览，
 * 让用户不打开报表页也能知道钱花在哪、花得比上个月多还是少。
 */
data class HomeInsight(
    /** 本月支出最高的分类；本月没有支出时为 null。 */
    val topCategoryName: String?,
    val topCategoryCents: Long,
    /** 本月日均支出（分）。 */
    val dailyAverageCents: Long,
    val monthExpenseCents: Long,
    val lastMonthExpenseCents: Long,
    /**
     * 与上月相比的变化百分比（正 = 比上月多花）。
     *
     * 上月没有支出时为 null —— 此时「增长百分比」没有意义（除零），界面应显示「暂无对比」
     * 而不是编造一个 100% 或无穷大。
     */
    val monthOverMonthPercent: Int?
) {
    /** 本月是否有可展示的支出数据。 */
    val hasExpense: Boolean get() = monthExpenseCents > 0

    val monthOverMonthUp: Boolean get() = (monthOverMonthPercent ?: 0) > 0
}

/**
 * 首页洞察的纯计算逻辑。
 *
 * 输入就是数据库已经聚合好的「本月 / 上月 按分类支出」，
 * 因此这里只做选择与换算，不碰数据库、不依赖 Android，便于单元测试。
 */
object HomeInsights {

    fun build(
        monthCategoryTotals: List<CategoryTotal>,
        lastMonthCategoryTotals: List<CategoryTotal>,
        nowMillis: Long = System.currentTimeMillis()
    ): HomeInsight {
        val monthExpenseCents = monthCategoryTotals.sumOf { it.totalCents }
        val lastMonthExpenseCents = lastMonthCategoryTotals.sumOf { it.totalCents }
        val top = monthCategoryTotals.maxByOrNull { it.totalCents }
        val elapsedDays = LedgerStats.elapsedDaysInMonth(nowMillis)

        return HomeInsight(
            topCategoryName = top?.takeIf { it.totalCents > 0 }?.categoryName,
            topCategoryCents = top?.totalCents ?: 0L,
            dailyAverageCents = if (elapsedDays > 0) monthExpenseCents / elapsedDays else 0L,
            monthExpenseCents = monthExpenseCents,
            lastMonthExpenseCents = lastMonthExpenseCents,
            monthOverMonthPercent = monthOverMonthPercent(monthExpenseCents, lastMonthExpenseCents)
        )
    }

    /**
     * 环比百分比（四舍五入到整数）。
     *
     * 上月为 0 时返回 null：既不能除以 0，也不该把一个「从无到有」的月份说成增长 100%。
     */
    fun monthOverMonthPercent(monthExpenseCents: Long, lastMonthExpenseCents: Long): Int? {
        if (lastMonthExpenseCents <= 0) return null
        val delta = (monthExpenseCents - lastMonthExpenseCents).toDouble()
        return Math.round(delta / lastMonthExpenseCents * 100).toInt()
    }
}
