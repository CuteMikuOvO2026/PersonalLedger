package com.example.personalledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * [LedgerStats] 纯计算逻辑的 JVM 单元测试。
 * 构造 [LedgerItem] 时显式提供金额/时间/方向/分类，避免依赖 android.R。
 */
class LedgerStatsTest {

    private var previousLocale: Locale? = null

    @Before
    fun setUp() {
        // 固定小数分隔符，避免在不同系统语言环境下断言失败。
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    private fun item(
        cents: Long,
        isExpense: Boolean,
        category: String = "其他",
        timeMillis: Long = System.currentTimeMillis(),
        note: String = ""
    ) = LedgerItem(
        id = java.util.UUID.randomUUID().toString(),
        amountCents = cents,
        note = note,
        timeMillis = timeMillis,
        isExpense = isExpense,
        categoryName = category
    )

    @Test
    fun budgetProgress_capsAt100_whenSpendExceedsBudget() {
        val list = listOf(item(cents = 12000, isExpense = true))
        val stats = LedgerStats.computeHomeStats(list, budgetValue = 100.0)
        assertEquals(100f, stats.budgetProgress, 0.001f)
        assertEquals("120.00", stats.monthExpense)
    }

    @Test
    fun budgetProgress_halfOfBudget() {
        val list = listOf(item(cents = 5000, isExpense = true))
        val stats = LedgerStats.computeHomeStats(list, budgetValue = 100.0)
        assertEquals(50f, stats.budgetProgress, 0.001f)
        assertFalse(stats.monthPositive)
        assertEquals("50.00", stats.monthBalance)
    }

    @Test
    fun today_incomeAndExpenseAreTrackedSeparately() {
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 3000, isExpense = false, timeMillis = now),
            item(cents = 800, isExpense = true, timeMillis = now)
        )
        val stats = LedgerStats.computeHomeStats(list, budgetValue = 0.0)
        assertEquals("30.00", stats.todayIncome)
        assertEquals("8.00", stats.todayExpense)
    }

    @Test
    fun buildHomeStats_fromDatabaseTotals_matchesListComputation() {
        // 首页统计已改为数据库聚合（只拿“分”的合计值），结果必须与逐条求和完全一致
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 3000, isExpense = false, timeMillis = now),
            item(cents = 800, isExpense = true, timeMillis = now),
            item(cents = 1200, isExpense = true, timeMillis = now)
        )
        val expected = LedgerStats.computeHomeStats(list, budgetValue = 100.0)

        val fromDatabaseTotals = LedgerStats.buildHomeStats(
            todayIncomeCents = 3000,
            todayExpenseCents = 2000,
            monthIncomeCents = 3000,
            monthExpenseCents = 2000,
            budgetValue = 100.0
        )

        assertEquals(expected, fromDatabaseTotals)
        assertEquals(20f, fromDatabaseTotals.budgetProgress, 0.001f)
        assertEquals("20.00", fromDatabaseTotals.monthExpense)
    }

    @Test
    fun buildHomeStats_ignoresRecordsOutsideCurrentMonth() {
        // 40 天前的记录必然落在上个月，因此不应计入本月支出
        val now = System.currentTimeMillis()
        val lastMonth = now - 40L * 24 * 60 * 60 * 1000
        val stats = LedgerStats.computeHomeStats(
            listOf(
                item(cents = 5000, isExpense = true, timeMillis = lastMonth),
                item(cents = 1500, isExpense = true, timeMillis = now)
            ),
            budgetValue = 100.0
        )

        assertEquals("15.00", stats.monthExpense)
        assertEquals("15.00", stats.todayExpense)
        // 本月只有支出，结余为负
        assertFalse(stats.monthPositive)
        assertEquals(15f, stats.budgetProgress, 0.001f)
    }

    @Test
    fun currentStatsRanges_coversNowAndNestsTodayInMonth() {
        val now = System.currentTimeMillis()
        val ranges = LedgerStats.currentStatsRanges()

        assertTrue(ranges.todayStart <= now && now <= ranges.todayEnd)
        assertTrue(ranges.monthStart <= now && now <= ranges.monthEnd)
        // 本月区间应完整包含今日区间
        assertTrue(ranges.monthStart <= ranges.todayStart)
        assertTrue(ranges.todayEnd <= ranges.monthEnd)
    }

    @Test
    fun pieEntries_excludeIncomeAndSumByCategory() {
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = now),
            item(cents = 500, isExpense = true, category = "餐饮", timeMillis = now),
            item(cents = 9999, isExpense = false, category = "工资", timeMillis = now)
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list)
        assertEquals(1, pie.size)
        assertEquals("餐饮", pie[0].label)
        assertEquals(15.0f, pie[0].value, 0.001f)
    }

    @Test
    fun weeklyBars_alwaysReturnSevenDays_latestDayReflectsToday() {
        val now = System.currentTimeMillis()
        val list = listOf(item(cents = 4200, isExpense = true, timeMillis = now))
        val (entries, labels) = LedgerStats.getWeeklyExpenseBarEntries(list)
        assertEquals(7, labels.size)
        assertEquals(7, entries.size)
        // 今天的支出应落在最后一根柱子上。
        assertTrue(entries.last().y > 0)
    }

    @Test
    fun reportSummary_showsTotals() {
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 1000, isExpense = false, timeMillis = now),
            item(cents = 400, isExpense = true, timeMillis = now)
        )
        val summary = LedgerStats.getReportSummary(list, budgetValue = 5000.0)
        assertTrue(summary.contains("总收入: ¥10.00"))
        assertTrue(summary.contains("总支出: ¥4.00"))
        assertTrue(summary.contains("结余: ¥6.00"))
        assertTrue(summary.contains("当月预算: ¥5000.00"))
        assertTrue(summary.contains("记录总数: 2"))
    }

    // ---------- 饼图时间范围筛选 ----------

    private fun daysAgo(days: Int): Long =
        System.currentTimeMillis() - days * 24L * 60 * 60 * 1000

    @Test
    fun pieRange_all_keepsEverything() {
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(0)),
            item(cents = 2000, isExpense = true, category = "交通", timeMillis = daysAgo(200))
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.ALL)
        assertEquals(2, pie.size)
    }

    @Test
    fun pieRange_last7Days_excludesOlderRecords() {
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(1)),
            item(cents = 5000, isExpense = true, category = "购物", timeMillis = daysAgo(10))
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.LAST_7_DAYS)
        assertEquals(1, pie.size)
        assertEquals("餐饮", pie[0].label)
        assertEquals(10.0f, pie[0].value, 0.001f)
    }

    @Test
    fun pieRange_lastMonth_includesWithin30DaysAndExcludesBeyond() {
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(29)),
            item(cents = 2000, isExpense = true, category = "交通", timeMillis = daysAgo(31))
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.LAST_MONTH)
        assertEquals(1, pie.size)
        assertEquals("餐饮", pie[0].label)
    }

    @Test
    fun pieRange_last3Months_boundaryAt90Days() {
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(89)),
            item(cents = 2000, isExpense = true, category = "交通", timeMillis = daysAgo(91))
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.LAST_3_MONTHS)
        assertEquals(1, pie.size)
        assertEquals("餐饮", pie[0].label)
    }

    @Test
    fun pieRange_todayIsAlwaysIncluded() {
        // 刚记的一笔必须落在任何非 ALL 范围内，避免“今天的消费看不到”
        val list = listOf(item(cents = 888, isExpense = true, category = "餐饮", timeMillis = System.currentTimeMillis()))
        PieTimeRange.entries.filter { it != PieTimeRange.ALL }.forEach { range ->
            val pie = LedgerStats.getExpenseCategoryPieEntries(list, range)
            assertEquals("范围 $range 应包含今天的记录", 1, pie.size)
        }
    }

    @Test
    fun pieRange_excludesIncomeRegardlessOfRange() {
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(1)),
            item(cents = 9999, isExpense = false, category = "工资", timeMillis = daysAgo(1))
        )
        val pie = LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.LAST_7_DAYS)
        assertEquals(1, pie.size)
        assertEquals("餐饮", pie[0].label)
    }

    @Test
    fun pieRange_emptyWhenNoRecordsInRange() {
        val list = listOf(item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(200)))
        assertTrue(LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.LAST_7_DAYS).isEmpty())
        // 但「全部」范围仍能看到
        assertEquals(1, LedgerStats.getExpenseCategoryPieEntries(list, PieTimeRange.ALL).size)
    }

    @Test
    fun pieRange_containsRecentRecordsAndExcludesOldOnes() {
        // 3 天前在「近 7 天」内、200 天前不在，验证范围过滤确实生效
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = daysAgo(3)),
            item(cents = 5000, isExpense = true, category = "购物", timeMillis = daysAgo(200))
        )
        val filtered = LedgerStats.filterExpenseByRange(list, PieTimeRange.LAST_7_DAYS)
        assertEquals(1, filtered.size)
        assertEquals("餐饮", filtered[0].categoryName)
    }
}
