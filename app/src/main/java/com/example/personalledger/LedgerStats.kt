package com.example.personalledger

import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.PieEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** “今日 / 本月”的起止毫秒区间，用于数据库端的首页统计聚合。 */
data class StatsRanges(
    val todayStart: Long,
    val todayEnd: Long,
    val monthStart: Long,
    val monthEnd: Long
)

/** 一个统计 / 预算周期的起止毫秒，**两端都包含**（与首页统计的口径一致）。 */
data class PeriodRange(
    val startMillis: Long,
    val endMillis: Long
)

/**
 * 报表柱状图里的一天：日期标签 + 左闭右开的时间区间。
 *
 * 坐标轴标签、SQL 分桶边界、点击某天时的明细查询都共用同一份 [DayRange]，
 * 避免「图上显示的日期」与「点进去查的区间」出现口径偏差。
 */
data class DayRange(
    /** `yyyy-MM-dd`，用于精确定位某一天。 */
    val dateKey: String,
    /** `MM-dd`，用于坐标轴标签。 */
    val label: String,
    val startMillis: Long,
    /** 次日零点，**不含**在区间内。 */
    val endMillis: Long
)

/**
 * 报表页饼图的时间范围选项。
 *
 * [days] 为回溯天数：`null` 表示不限时间（全部数据），其余表示“包含今天在内的最近 N 天”。
 * 天数换算与边界对齐统一由 [LedgerStats.pieRangeMillis] 处理，保证与首页统计相同的本地时区口径。
 */
enum class PieTimeRange(val days: Int?) {
    ALL(null),
    LAST_7_DAYS(7),
    LAST_MONTH(30),
    LAST_3_MONTHS(90)
}

/**
 * 账本统计/报表的纯计算逻辑（不依赖 Android 组件），
 * 供 [MainViewModel] 与 [ReportViewModel] 复用，并便于 JVM 单元测试。
 *
 * 这里有两类函数，职责必须分清：
 * - **时间窗口计算**（[currentStatsRanges]、[weeklyDayRanges]、[rangeStartMillisOrNull] 等）：
 *   统一按本地时区对齐到自然日边界，是首页与报表共用的唯一口径来源。
 * - **聚合结果换算**（[buildHomeStats]、[buildPieEntries]、[buildWeeklyBarEntries]、
 *   [buildReportSummary]）：把数据库聚合出的「分」金额换算成界面数据。
 *
 * 另有若干 `getXxx(list)` 形式的**内存版实现**：它们只做逐条累加，用于和数据库聚合结果
 * 对拍（见 `LedgerStatsTest`），保证两条路径永远得出同样的数字；生产代码走数据库聚合。
 */
object LedgerStats {

    /** 报表柱状图固定展示的天数（含今天在内的最近 N 天）。 */
    const val WEEKLY_DAYS = 7

    /** 当前时刻对应的“今日 / 本月”区间（首页统计用，跨零点后重新取值即可刷新口径）。 */
    fun currentStatsRanges(): StatsRanges {
        val today = todayRange()
        val month = monthRange()
        return StatsRanges(
            todayStart = today.startMillis,
            todayEnd = today.endMillis,
            monthStart = month.startMillis,
            monthEnd = month.endMillis
        )
    }

    /**
     * 按 [period] 取「当期」区间（本地时区，两端都包含）。
     *
     * 预算进度、首页洞察都以它为准，保证「本月预算」与「本月支出」永远同一个区间。
     */
    fun currentPeriodRange(
        period: BudgetPeriod,
        nowMillis: Long = System.currentTimeMillis()
    ): PeriodRange = when (period) {
        BudgetPeriod.MONTH -> monthRange(nowMillis)
        BudgetPeriod.WEEK -> weekRange(nowMillis)
    }

    /** 本月区间：从 1 日零点到月末最后一毫秒。 */
    fun monthRange(nowMillis: Long = System.currentTimeMillis()): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return PeriodRange(start, cal.timeInMillis - 1)
    }

    /**
     * 本周区间：**周一**零点到周日最后一毫秒。
     *
     * 刻意固定以周一为一周之始，而不是跟随系统的「每周首日」设置——
     * 预算按周统计时，用户预期是自然周（周一至周日），与地区设置无关。
     * 用逐日回退而不是 `set(DAY_OF_WEEK)`，避免不同首日设置下的边界歧义。
     */
    fun weekRange(nowMillis: Long = System.currentTimeMillis()): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 7)
        return PeriodRange(start, cal.timeInMillis - 1)
    }

    fun computeHomeStats(list: List<LedgerItem>, budgetValue: Double): HomeStats {
        val today = todayRange()
        val month = monthRange()

        var todayIncomeCents = 0L
        var todayExpenseCents = 0L
        var monthIncomeCents = 0L
        var monthExpenseCents = 0L

        list.forEach { item ->
            if (item.timeMillis in today.startMillis..today.endMillis) {
                if (item.isExpense) todayExpenseCents += item.amountCents
                else todayIncomeCents += item.amountCents
            }
            if (item.timeMillis in month.startMillis..month.endMillis) {
                if (item.isExpense) monthExpenseCents += item.amountCents
                else monthIncomeCents += item.amountCents
            }
        }

        return buildHomeStats(
            todayIncomeCents = todayIncomeCents,
            todayExpenseCents = todayExpenseCents,
            monthIncomeCents = monthIncomeCents,
            monthExpenseCents = monthExpenseCents,
            budgetValue = budgetValue
        )
    }

    /**
     * 把两个收支方向的聚合结果组装成首页概览所需的 [HomeTotals]。
     *
     * 数据库对收入、支出各查一次（[LedgerEntryDao.observeDirectionTotals]），
     * 这里负责**方向到字段的映射**——收入进 `Income` 字段、支出进 `Expense` 字段。
     * 这层映射写反了界面数字会「收支颠倒」且不报错，因此单独抽成纯函数并由单测锁定。
     */
    fun buildHomeTotals(income: DirectionTotals, expense: DirectionTotals): HomeTotals =
        HomeTotals(
            todayIncomeCents = income.todayCents,
            todayExpenseCents = expense.todayCents,
            monthIncomeCents = income.monthCents,
            monthExpenseCents = expense.monthCents
        )

    /**
     * 用已经聚合好的“分”金额构建首页概览统计。
     *
     * 首页的今日/本月收入支出由数据库聚合得出（[LedgerEntryDao.observeDirectionTotals]），
     * 因此不必把整表读进内存；此函数只负责格式与预算进度的纯计算。
     */
    fun buildHomeStats(
        todayIncomeCents: Long,
        todayExpenseCents: Long,
        monthIncomeCents: Long,
        monthExpenseCents: Long,
        budgetValue: Double
    ): HomeStats {
        val monthExpenseValue = monthExpenseCents / 100.0
        val progressValue = if (budgetValue > 0) {
            ((monthExpenseValue / budgetValue) * 100).coerceAtMost(100.0).toFloat()
        } else 0f

        val balance = monthIncomeCents - monthExpenseCents
        return HomeStats(
            todayIncome = formatAmount(todayIncomeCents),
            todayExpense = formatAmount(todayExpenseCents),
            monthExpense = formatAmount(monthExpenseCents),
            budgetProgress = progressValue,
            monthIncome = formatAmount(monthIncomeCents),
            monthBalance = formatAmount(abs(balance)),
            monthPositive = balance >= 0
        )
    }

    /** 数据库聚合的分类支出 → 饼图数据（金额由「分」换算成元）。 */
    fun buildPieEntries(categoryTotals: List<CategoryTotal>): List<PieEntry> =
        categoryTotals.map { PieEntry((it.totalCents / 100.0).toFloat(), it.categoryName) }

    /**
     * 数据库聚合的 7 天分桶 → 柱状图数据。
     *
     * [ranges] 与分桶一一对应（都由 [weeklyDayRanges] 产出）；长度不一致时缺失的桶按 0 处理，
     * 保证图表始终有 [WEEKLY_DAYS] 根柱子，不会因为参数错位而少画。
     */
    fun buildWeeklyBarEntries(
        dayTotals: WeeklyDayTotals,
        ranges: List<DayRange>
    ): Pair<List<BarEntry>, List<String>> {
        val totals = dayTotals.toList()
        val entries = ranges.indices.map { index ->
            BarEntry(index.toFloat(), (totals.getOrElse(index) { 0L } / 100.0).toFloat())
        }
        return Pair(entries, ranges.map { it.label })
    }

    /** 数据库聚合的全表收支与条数 → 报表摘要文本（格式与 [getReportSummary] 保持一致）。 */
    fun buildReportSummary(
        totals: ReportTotals,
        budgetValue: Double,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val balance = totals.incomeCents - totals.expenseCents
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("报表生成时间: ${df.format(Date(nowMillis))}")
            appendLine("总收入: ¥${formatAmount(totals.incomeCents)}")
            appendLine("总支出: ¥${formatAmount(totals.expenseCents)}")
            appendLine("结余: ¥${formatAmount(balance)}")
            appendLine(
                if (budgetValue > 0) {
                    "当月预算: ¥${String.format(Locale.getDefault(), "%.2f", budgetValue)}"
                } else {
                    "当月预算: 未设置"
                }
            )
            appendLine("记录总数: ${totals.entryCount}")
        }
    }

    fun getExpenseCategoryPieEntries(list: List<LedgerItem>): List<PieEntry> {
        val categoryTotals = mutableMapOf<String, Long>()
        list.forEach { item ->
            if (item.isExpense) {
                val current = categoryTotals[item.categoryName] ?: 0L
                categoryTotals[item.categoryName] = current + item.amountCents
            }
        }
        return categoryTotals.map { (name, cents) -> PieEntry((cents / 100.0).toFloat(), name) }
    }

    /**
     * 按 [range] 过滤出参与饼图统计的支出记录（内存版，供对拍与测试使用）。
     *
     * - [PieTimeRange.ALL]：不做时间过滤，返回全部支出记录。
     * - 其余选项：取「包含今天在内的最近 `range.days` 天」，区间与首页统计一样按本地时区对齐到整日边界。
     */
    fun filterExpenseByRange(
        list: List<LedgerItem>,
        range: PieTimeRange,
        nowMillis: Long = System.currentTimeMillis()
    ): List<LedgerItem> {
        val expenses = list.filter { it.isExpense }
        val startMillis = rangeStartMillisOrNull(range, startOfDay(nowMillis)) ?: return expenses
        return expenses.filter { it.timeMillis >= startMillis }
    }

    /**
     * 饼图数据：[range] 指定时间范围，仅统计该范围内的支出分类占比。
     *
     * 默认 [PieTimeRange.ALL] 时与改造前行为完全一致（全部时间数据）。
     */
    fun getExpenseCategoryPieEntries(
        list: List<LedgerItem>,
        range: PieTimeRange,
        nowMillis: Long = System.currentTimeMillis()
    ): List<PieEntry> = getExpenseCategoryPieEntries(filterExpenseByRange(list, range, nowMillis))

    /**
     * [nowMillis] 所在自然日的零点（本地时区）。
     *
     * 所有「今天 / 最近 N 天」的窗口都以它为锚点；跨零点后重新取一次即可刷新口径，
     * 避免用「当前时刻」直接做减法而在夏令时或跨零点时算错边界。
     */
    fun startOfDay(nowMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 「包含今天在内的最近 [days] 天」的起始时刻，锚点为 [todayStart]（当天零点）。
     *
     * `days = 1` 即今天零点；`days = 7` 即 6 天前的零点，覆盖含今天在内的 7 个自然日。
     * 用 `Calendar.add(DAY_OF_MONTH, …)` 逐日回溯，跨月跨年自动正确。
     */
    fun rangeStartMillis(todayStart: Long, days: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = todayStart
        cal.add(Calendar.DAY_OF_MONTH, -(days.coerceAtLeast(1) - 1))
        return cal.timeInMillis
    }

    /**
     * 饼图时间范围对应的起始毫秒；[PieTimeRange.ALL] 返回 null 表示不限时间。
     *
     * 返回 null 会让 Room 走 `:startMillis IS NULL` 的「不过滤」分支，
     * 与内存版 [filterExpenseByRange] 的语义严格一致。
     */
    fun rangeStartMillisOrNull(range: PieTimeRange, todayStart: Long): Long? {
        val days = range.days ?: return null
        return rangeStartMillis(todayStart, days)
    }

    /**
     * 最近 [WEEKLY_DAYS] 天的分桶（含今天，按时间正序）。
     *
     * 柱状图的坐标轴标签、SQL 分桶边界、点击某天的明细查询全部取自这里，
     * 是「哪一天」这个概念的唯一定义，避免三处各算一遍导致口径不一致。
     */
    fun weeklyDayRanges(nowMillis: Long = System.currentTimeMillis()): List<DayRange> {
        val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startOfDay(nowMillis)
        // 先退到窗口最早一天的零点，再逐日推进
        calendar.add(Calendar.DAY_OF_MONTH, -(WEEKLY_DAYS - 1))
        return (0 until WEEKLY_DAYS).map {
            val start = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            DayRange(
                dateKey = dateKeyFormat.format(Date(start)),
                label = labelFormat.format(Date(start)),
                startMillis = start,
                endMillis = calendar.timeInMillis
            )
        }
    }

    /**
     * 最近 [WEEKLY_DAYS] 天每日支出的柱状图数据（内存版，供对拍与测试使用）。
     *
     * 分桶口径与数据库版 [buildWeeklyBarEntries] 严格一致：左闭右开、按本地时区对齐整日边界，
     * 因此同一批数据走哪条路径都必须得出相同的柱子高度。
     */
    fun getWeeklyExpenseBarEntries(
        list: List<LedgerItem>,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<List<BarEntry>, List<String>> {
        val ranges = weeklyDayRanges(nowMillis)
        val expenses = list.filter { it.isExpense }
        val entries = ranges.mapIndexed { index, dayRange ->
            val dayTotalCents = expenses
                .filter { it.timeMillis >= dayRange.startMillis && it.timeMillis < dayRange.endMillis }
                .sumOf { it.amountCents }
            BarEntry(index.toFloat(), (dayTotalCents / 100.0).toFloat())
        }
        return Pair(entries, ranges.map { it.label })
    }

    /**
     * 报表摘要文本（内存版，供对拍与测试使用）。
     *
     * 直接复用 [buildReportSummary]，保证内存版与数据库版的文案格式不会各自漂移。
     */
    fun getReportSummary(list: List<LedgerItem>, budgetValue: Double): String {
        var totalIncomeCents = 0L
        var totalExpenseCents = 0L
        list.forEach { item ->
            if (item.isExpense) totalExpenseCents += item.amountCents
            else totalIncomeCents += item.amountCents
        }
        return buildReportSummary(
            totals = ReportTotals(
                incomeCents = totalIncomeCents,
                expenseCents = totalExpenseCents,
                entryCount = list.size
            ),
            budgetValue = budgetValue
        )
    }

    fun formatAmount(cents: Long): String =
        String.format(Locale.getDefault(), "%.2f", cents / 100.0)

    /**
     * 当期区间的稳定标识（月为 `yyyy-MM`，周为起始日 `yyyy-MM-dd`）。
     *
     * 预算提醒用它做去重键：同一个周期内同一条规则只提醒一次，
     * 进入新周期后键变化，于是又能重新提醒。
     */
    fun periodKey(period: BudgetPeriod, nowMillis: Long = System.currentTimeMillis()): String {
        val range = currentPeriodRange(period, nowMillis)
        val pattern = if (period == BudgetPeriod.MONTH) "yyyy-MM" else "yyyy-MM-dd"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(range.startMillis))
    }

    /**
     * 上月区间，用于「与上月相比」这类环比。
     *
     * 与 [monthRange] 一样按本地时区对齐到整月边界，且不依赖「今天是几号」，
     * 因此月初（本月才过了 1 天）也能拿到完整的上月数据做对比。
     */
    fun lastMonthRange(nowMillis: Long = System.currentTimeMillis()): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        cal.add(Calendar.MONTH, -1)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return PeriodRange(start, cal.timeInMillis - 1)
    }

    /**
     * 本月已过的天数（含今天，1..31）。
     *
     * 日均支出用它做分母：月中查看时按「已过天数」摊，而不是按整月天数，
     * 否则月初看日均会明显偏低、失去参考意义。
     */
    fun elapsedDaysInMonth(nowMillis: Long = System.currentTimeMillis()): Int =
        Calendar.getInstance().apply { timeInMillis = nowMillis }.get(Calendar.DAY_OF_MONTH)

    /** 今日区间：当天零点到当天最后一毫秒。 */
    fun todayRange(nowMillis: Long = System.currentTimeMillis()): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return PeriodRange(start, cal.timeInMillis - 1)
    }
}
