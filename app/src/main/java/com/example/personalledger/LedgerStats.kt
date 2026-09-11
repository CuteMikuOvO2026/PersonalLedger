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

/**
 * 账本统计/报表的纯计算逻辑（不依赖 Android 组件），
 * 供 [MainViewModel] 与 [ReportViewModel] 复用，并便于 JVM 单元测试。
 */
object LedgerStats {

    /** 当前时刻对应的“今日 / 本月”区间（首页统计用，跨零点后重新取值即可刷新口径）。 */
    fun currentStatsRanges(): StatsRanges {
        val today = todayRangeMillis()
        val month = monthRangeMillis()
        return StatsRanges(
            todayStart = today.first,
            todayEnd = today.second,
            monthStart = month.first,
            monthEnd = month.second
        )
    }

    fun computeHomeStats(list: List<LedgerItem>, budgetValue: Double): HomeStats {
        val today = todayRangeMillis()
        val month = monthRangeMillis()

        var todayIncomeCents = 0L
        var todayExpenseCents = 0L
        var monthIncomeCents = 0L
        var monthExpenseCents = 0L

        list.forEach { item ->
            if (item.timeMillis in today.first..today.second) {
                if (item.isExpense) todayExpenseCents += item.amountCents
                else todayIncomeCents += item.amountCents
            }
            if (item.timeMillis in month.first..month.second) {
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
     * 用已经聚合好的“分”金额构建首页概览统计。
     *
     * 首页的今日/本月收入支出由数据库聚合得出（[LedgerEntryDao.observeHomeTotals]），
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

    fun buildReportData(list: List<LedgerItem>, budgetValue: Double): ReportData {
        val pie = getExpenseCategoryPieEntries(list)
        val weekly = getWeeklyExpenseBarEntries(list)
        return ReportData(
            pieEntries = pie,
            weeklyBar = weekly,
            weeklyDates = getWeeklyExpenseDates(),
            summary = getReportSummary(list, budgetValue),
            hasData = list.isNotEmpty()
        )
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

    fun getWeeklyExpenseBarEntries(list: List<LedgerItem>): Pair<List<BarEntry>, List<String>> {
        val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        val entries = mutableListOf<BarEntry>()
        val labels = mutableListOf<String>()
        val dateRange = lastSevenDays()

        val expenseList = list.filter { it.isExpense }
        val groupedExpenses = expenseList.groupBy { item ->
            dayFormat.format(Date(item.timeMillis))
        }.filterKeys { it.isNotEmpty() }

        dateRange.forEachIndexed { index, dateStr ->
            val dayTotal = groupedExpenses[dateStr]?.sumOf { it.amountCents } ?: 0L
            entries.add(BarEntry(index.toFloat(), (dayTotal / 100.0).toFloat()))
            try {
                val date = dayFormat.parse(dateStr)
                labels.add(if (date != null) dateFormat.format(date) else dateStr.substring(5))
            } catch (_: Exception) {
                labels.add(dateStr.substring(5))
            }
        }
        return Pair(entries, labels)
    }

    /** 最近 7 天的完整日期（yyyy-MM-dd，按时间正序），用于点击柱状图定位具体某天。 */
    fun getWeeklyExpenseDates(): List<String> = lastSevenDays()

    private fun lastSevenDays(): List<String> {
        val calendar = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val list = (0 until 7).map { i ->
            calendar.time = Date()
            calendar.add(Calendar.DAY_OF_MONTH, -i)
            dayFormat.format(calendar.time)
        }
        return list.reversed()
    }

    fun getReportSummary(list: List<LedgerItem>, budgetValue: Double): String {
        var totalIncomeCents = 0L
        var totalExpenseCents = 0L
        list.forEach { item ->
            if (item.isExpense) totalExpenseCents += item.amountCents
            else totalIncomeCents += item.amountCents
        }
        val balance = totalIncomeCents - totalExpenseCents
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("报表生成时间: ${df.format(Date())}")
            appendLine("总收入: ¥${formatAmount(totalIncomeCents)}")
            appendLine("总支出: ¥${formatAmount(totalExpenseCents)}")
            appendLine("结余: ¥${formatAmount(balance)}")
            appendLine("当月预算: ¥${String.format(Locale.getDefault(), "%.2f", budgetValue)}")
            appendLine("记录总数: ${list.size}")
        }
    }

    fun formatAmount(cents: Long): String =
        String.format(Locale.getDefault(), "%.2f", cents / 100.0)

    private fun todayRangeMillis(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return start to (cal.timeInMillis - 1)
    }

    private fun monthRangeMillis(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to (cal.timeInMillis - 1)
    }
}
