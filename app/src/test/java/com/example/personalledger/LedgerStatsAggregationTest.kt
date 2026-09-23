package com.example.personalledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 报表页「数据库聚合」路径的 JVM 单元测试。
 *
 * 报表页已把饼图、柱状图、摘要全部下推到 SQL，本文件负责两件事：
 * 1. **对拍**：把「SQL 聚合结果 → 图表数据」的换算结果，与逐条累加的内存版结果比对，
 *    保证改造前后图表上的数字完全一致（沿用 `buildHomeStats` 已有的对拍思路）；
 * 2. **边界**：分桶区间左闭右开、窗口锚点为本地自然日零点，避免跨零点 / 跨月时算错。
 */
class LedgerStatsAggregationTest {

    private var previousLocale: Locale? = null

    @Before
    fun setUp() {
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

    private fun daysAgo(days: Int): Long =
        System.currentTimeMillis() - days * 24L * 60 * 60 * 1000

    // ---------- 对拍：SQL 聚合结果换算 == 内存逐条累加 ----------

    @Test
    fun pieEntries_sqlCategoryTotalsMatchInMemoryAggregation() {
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 1000, isExpense = true, category = "餐饮", timeMillis = now),
            item(cents = 500, isExpense = true, category = "餐饮", timeMillis = now),
            item(cents = 2000, isExpense = true, category = "交通", timeMillis = now),
            item(cents = 7777, isExpense = false, category = "工资", timeMillis = now)
        )

        // 模拟数据库的 GROUP BY categoryName + SUM(amountCents)，收入不参与
        val sqlTotals = list
            .filter { it.isExpense }
            .groupBy { it.categoryName }
            .map { (name, items) -> CategoryTotal(name, items.sumOf { it.amountCents }) }

        val fromSql = LedgerStats.buildPieEntries(sqlTotals).associate { it.label to it.value }
        val fromMemory = LedgerStats.getExpenseCategoryPieEntries(list).associate { it.label to it.value }

        assertEquals(fromMemory, fromSql)
        assertEquals(setOf("餐饮", "交通"), fromSql.keys)
        assertEquals(15.0f, fromSql.getValue("餐饮"), 0.001f)
        assertEquals(20.0f, fromSql.getValue("交通"), 0.001f)
    }

    @Test
    fun weeklyBars_sqlBucketsMatchInMemoryAggregation() {
        val now = System.currentTimeMillis()
        val ranges = LedgerStats.weeklyDayRanges(now)
        val list = listOf(
            item(cents = 1234, isExpense = true, timeMillis = now),
            item(cents = 500, isExpense = true, timeMillis = ranges.first().startMillis),
            item(cents = 9999, isExpense = false, timeMillis = now)
        )

        // 模拟数据库的 7 个 SUM(CASE WHEN timeMillis >= 起点 AND < 终点 …)
        val buckets = ranges.map { range ->
            list.filter { it.isExpense && it.timeMillis >= range.startMillis && it.timeMillis < range.endMillis }
                .sumOf { it.amountCents }
        }
        val dayTotals = WeeklyDayTotals(
            day0 = buckets[0],
            day1 = buckets[1],
            day2 = buckets[2],
            day3 = buckets[3],
            day4 = buckets[4],
            day5 = buckets[5],
            day6 = buckets[6]
        )

        val fromSql = LedgerStats.buildWeeklyBarEntries(dayTotals, ranges)
        val fromMemory = LedgerStats.getWeeklyExpenseBarEntries(list, now)

        assertEquals(fromMemory.second, fromSql.second)
        assertEquals(fromMemory.first.size, fromSql.first.size)
        fromMemory.first.forEachIndexed { index, entry ->
            assertEquals(
                "第 $index 根柱子的高度必须一致",
                entry.y,
                fromSql.first[index].y,
                0.001f
            )
        }
        assertEquals(5.0f, fromSql.first.first().y, 0.001f)
        assertEquals(12.34f, fromSql.first.last().y, 0.001f)
    }

    @Test
    fun reportSummary_sqlTotalsMatchInMemoryAggregation() {
        val now = System.currentTimeMillis()
        val list = listOf(
            item(cents = 1000, isExpense = false, timeMillis = now),
            item(cents = 400, isExpense = true, timeMillis = now),
            item(cents = 600, isExpense = true, timeMillis = now)
        )
        val sqlTotals = ReportTotals(incomeCents = 1000, expenseCents = 1000, entryCount = 3)

        val fromSql = LedgerStats.buildReportSummary(sqlTotals, budgetValue = 5000.0, nowMillis = now)
        val fromMemory = LedgerStats.getReportSummary(list, budgetValue = 5000.0)

        // 首行是「生成时间」（分钟级），两条路径的取值时刻不同，其余各行必须逐字一致
        assertEquals(fromMemory.lines().drop(1), fromSql.lines().drop(1))
        assertTrue(fromSql.contains("总收入: ¥10.00"))
        assertTrue(fromSql.contains("总支出: ¥10.00"))
        assertTrue(fromSql.contains("结余: ¥0.00"))
        assertTrue(fromSql.contains("当月预算: ¥5000.00"))
        assertTrue(fromSql.contains("记录总数: 3"))
    }

    // ---------- 首页聚合：两个方向的查询结果 → HomeTotals ----------

    @Test
    fun buildHomeTotals_mapsIncomeAndExpenseToTheRightFields() {
        // 方向映射写反不会报错，只会让界面「收支颠倒」，所以单独锁死
        val totals = LedgerStats.buildHomeTotals(
            income = DirectionTotals(todayCents = 111, monthCents = 222),
            expense = DirectionTotals(todayCents = 333, monthCents = 444)
        )

        assertEquals(111L, totals.todayIncomeCents)
        assertEquals(333L, totals.todayExpenseCents)
        assertEquals(222L, totals.monthIncomeCents)
        assertEquals(444L, totals.monthExpenseCents)
    }

    @Test
    fun homeTotals_twoDirectionQueriesMatchInMemoryAggregation() {
        val now = System.currentTimeMillis()
        val ranges = LedgerStats.currentStatsRanges()
        // 一个「本月内但不在今天」的时刻：今天不是本月最后一天就用明天零点，否则用今天的前一毫秒
        val tomorrowStart = ranges.todayEnd + 1
        val otherDayInMonth =
            if (tomorrowStart <= ranges.monthEnd) tomorrowStart else ranges.todayStart - 1

        val list = listOf(
            item(cents = 3000, isExpense = false, timeMillis = now),          // 今日收入
            item(cents = 800, isExpense = true, timeMillis = now),            // 今日支出
            item(cents = 1200, isExpense = true, timeMillis = otherDayInMonth), // 本月另一天的支出
            item(cents = 9999, isExpense = true, timeMillis = ranges.monthStart - 1) // 上月支出，必须排除
        )

        // 模拟 observeDirectionTotals：WHERE isExpense=? AND timeMillis BETWEEN monthStart AND monthEnd
        fun directionTotals(isExpense: Boolean): DirectionTotals {
            val inMonth = list.filter {
                it.isExpense == isExpense &&
                    it.timeMillis >= ranges.monthStart && it.timeMillis <= ranges.monthEnd
            }
            return DirectionTotals(
                todayCents = inMonth
                    .filter { it.timeMillis in ranges.todayStart..ranges.todayEnd }
                    .sumOf { it.amountCents },
                monthCents = inMonth.sumOf { it.amountCents }
            )
        }

        val totals = LedgerStats.buildHomeTotals(
            income = directionTotals(isExpense = false),
            expense = directionTotals(isExpense = true)
        )
        val fromSql = LedgerStats.buildHomeStats(
            todayIncomeCents = totals.todayIncomeCents,
            todayExpenseCents = totals.todayExpenseCents,
            monthIncomeCents = totals.monthIncomeCents,
            monthExpenseCents = totals.monthExpenseCents,
            budgetValue = 100.0
        )
        val fromMemory = LedgerStats.computeHomeStats(list, budgetValue = 100.0)

        assertEquals(fromMemory, fromSql)
        // 今日只算今天那笔；本月还要算上同月另一天那笔；上月那笔必须被排除
        assertEquals("30.00", fromSql.todayIncome)
        assertEquals("8.00", fromSql.todayExpense)
        assertEquals("30.00", fromSql.monthIncome)
        assertEquals("20.00", fromSql.monthExpense)
    }

    @Test
    fun homeTotals_emptyLedgerYieldsZeros() {
        // SQL 聚合在「无匹配行」时仍返回一行（SUM 为 NULL，被 COALESCE 兜成 0），
        // 因此空账本必须得到 0 而不是缺数据
        val totals = LedgerStats.buildHomeTotals(
            income = DirectionTotals(todayCents = 0, monthCents = 0),
            expense = DirectionTotals(todayCents = 0, monthCents = 0)
        )
        val stats = LedgerStats.buildHomeStats(
            todayIncomeCents = totals.todayIncomeCents,
            todayExpenseCents = totals.todayExpenseCents,
            monthIncomeCents = totals.monthIncomeCents,
            monthExpenseCents = totals.monthExpenseCents,
            budgetValue = 0.0
        )

        assertEquals("0.00", stats.todayIncome)
        assertEquals("0.00", stats.todayExpense)
        assertEquals("0.00", stats.monthExpense)
        assertEquals("0.00", stats.monthBalance)
        assertEquals(0f, stats.budgetProgress, 0.001f)
    }

    // ---------- 边界：时间窗口口径 ----------

    @Test
    fun startOfDay_truncatesToLocalMidnight() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 15)
            set(Calendar.MINUTE, 42)
            set(Calendar.SECOND, 7)
            set(Calendar.MILLISECOND, 321)
        }
        val start = LedgerStats.startOfDay(cal.timeInMillis)

        val truncated = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, truncated.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, truncated.get(Calendar.MINUTE))
        assertEquals(0, truncated.get(Calendar.SECOND))
        assertEquals(0, truncated.get(Calendar.MILLISECOND))
        assertTrue(start <= cal.timeInMillis)

        // 今天零点的次日零点，必须正好是柱状图最后一天（今天）的右开边界
        val tomorrow = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.DAY_OF_MONTH, 1)
        }
        assertEquals(tomorrow.timeInMillis, LedgerStats.weeklyDayRanges(cal.timeInMillis).last().endMillis)
    }

    @Test
    fun weeklyDayRanges_returnsSevenContiguousDaysEndingToday() {
        val now = System.currentTimeMillis()
        val ranges = LedgerStats.weeklyDayRanges(now)

        assertEquals(LedgerStats.WEEKLY_DAYS, ranges.size)

        // 首尾相接：前一天的终点就是后一天的起点
        ranges.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.endMillis, next.startMillis)
        }

        // 今天是最后一个分桶，且 now 落在其中
        val today = ranges.last()
        assertTrue(today.startMillis <= now && now < today.endMillis)

        // 日期键与标签格式
        assertTrue(today.dateKey.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue(today.label.matches(Regex("""\d{2}-\d{2}""")))
        assertEquals(today.dateKey.substring(5), today.label)
    }

    @Test
    fun weeklyBarEntries_bucketBoundaryBelongsToNextDay() {
        val now = System.currentTimeMillis()
        val ranges = LedgerStats.weeklyDayRanges(now)
        val yesterday = ranges[ranges.size - 2]
        val today = ranges.last()

        // 恰好落在今天零点的一笔必须算作「今天」；昨天区间的右端是开区间，不含它
        val list = listOf(
            item(cents = 1000, isExpense = true, timeMillis = today.startMillis),
            item(cents = 2000, isExpense = true, timeMillis = today.endMillis - 1)
        )
        val (entries, _) = LedgerStats.getWeeklyExpenseBarEntries(list, now)

        assertEquals(0f, entries[entries.size - 2].y, 0.001f)
        assertEquals(30f, entries.last().y, 0.001f)
        assertEquals(today.startMillis, yesterday.endMillis)
    }

    @Test
    fun rangeStartMillisOrNull_allMeansNoLowerBound() {
        val todayStart = LedgerStats.startOfDay(System.currentTimeMillis())

        // ALL 返回 null，SQL 侧即 `:startMillis IS NULL`（不过滤）
        assertNull(LedgerStats.rangeStartMillisOrNull(PieTimeRange.ALL, todayStart))
        PieTimeRange.entries.filter { it != PieTimeRange.ALL }.forEach { range ->
            assertNotNull(LedgerStats.rangeStartMillisOrNull(range, todayStart))
        }
    }

    @Test
    fun rangeStartMillisOrNull_sqlLowerBoundMatchesInMemoryFilter() {
        val now = System.currentTimeMillis()
        val todayStart = LedgerStats.startOfDay(now)
        // 覆盖到 120 天前，确保能测到「近三个月」的边界
        val list = (0..120).map { offset ->
            item(cents = 100, isExpense = true, timeMillis = now - offset * 24L * 60 * 60 * 1000)
        }

        PieTimeRange.entries.forEach { range ->
            val start = LedgerStats.rangeStartMillisOrNull(range, todayStart)
            // 模拟 SQL 的 WHERE (:startMillis IS NULL OR timeMillis >= :startMillis)
            val bySqlBound = list.filter { start == null || it.timeMillis >= start }
            val byMemory = LedgerStats.filterExpenseByRange(list, range, now)

            assertEquals("范围 $range 的 SQL 下界与内存过滤结果必须一致", byMemory, bySqlBound)
        }
    }

    @Test
    fun rangeStartMillis_usesWholeDayArithmeticAcrossMonthBoundary() {
        // 锚点为 3 月 1 日零点：窗口回溯到 2 月时必须落在 2/28 零点，不能因跨月算错
        val marchFirst = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val febLast = Calendar.getInstance().apply {
            timeInMillis = marchFirst
            add(Calendar.DAY_OF_MONTH, -1)
        }.timeInMillis

        // days 是「窗口包含今天在内的总天数」，不是回溯天数
        // days = 1 → 只含今天，起点即今天零点
        assertEquals(marchFirst, LedgerStats.rangeStartMillis(marchFirst, days = 1))
        // days = 2 → 含今天与昨天，起点是昨天零点
        assertEquals(febLast, LedgerStats.rangeStartMillis(marchFirst, days = 2))
        assertTrue(LedgerStats.rangeStartMillis(marchFirst, days = 2) < marchFirst)
        // 与「近 7 天」的定义一致：起点 = 今天零点 - 6 天
        val sevenDayStart = LedgerStats.rangeStartMillis(marchFirst, days = LedgerStats.WEEKLY_DAYS)
        val expectedSevenDayStart = Calendar.getInstance().apply {
            timeInMillis = marchFirst
            add(Calendar.DAY_OF_MONTH, -(LedgerStats.WEEKLY_DAYS - 1))
        }.timeInMillis
        assertEquals(expectedSevenDayStart, sevenDayStart)
    }

    @Test
    fun buildWeeklyBarEntries_alwaysRendersSevenBars() {
        // 无论分桶数值如何，图表都要有 7 根柱子，缺数据的桶按 0 画
        val ranges = LedgerStats.weeklyDayRanges()
        val dayTotals = WeeklyDayTotals(100, 0, 0, 0, 0, 0, 0)

        val (entries, labels) = LedgerStats.buildWeeklyBarEntries(dayTotals, ranges)

        assertEquals(LedgerStats.WEEKLY_DAYS, entries.size)
        assertEquals(LedgerStats.WEEKLY_DAYS, labels.size)
        assertEquals(1.0f, entries.first().y, 0.001f)
        assertEquals(0f, entries[1].y, 0.001f)
    }

    @Test
    fun buildPieEntries_handlesEmptyAggregation() {
        assertTrue(LedgerStats.buildPieEntries(emptyList()).isEmpty())
    }

    @Test
    fun buildReportSummary_reportsZeroWhenLedgerIsEmpty() {
        val summary = LedgerStats.buildReportSummary(
            ReportTotals(incomeCents = 0, expenseCents = 0, entryCount = 0),
            budgetValue = 0.0
        )
        assertTrue(summary.contains("总收入: ¥0.00"))
        assertTrue(summary.contains("总支出: ¥0.00"))
        assertTrue(summary.contains("记录总数: 0"))
    }

    @Test
    fun daysAgoHelper_staysInsideSevenDayWindow() {
        // 兜底：确保本文件的时间构造方式与「近 7 天」窗口一致，避免测试自身口径漂移
        val todayStart = LedgerStats.startOfDay(System.currentTimeMillis())
        val start = LedgerStats.rangeStartMillisOrNull(PieTimeRange.LAST_7_DAYS, todayStart)
        assertNotNull(start)
        assertTrue(daysAgo(0) >= start!!)
        assertTrue(daysAgo(6) >= start)
        assertTrue(daysAgo(8) < start)
    }
}
