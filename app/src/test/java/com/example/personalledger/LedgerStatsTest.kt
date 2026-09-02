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
        assertTrue(summary.contains("当月预算: ¥50.00"))
        assertTrue(summary.contains("记录总数: 2"))
    }
}
