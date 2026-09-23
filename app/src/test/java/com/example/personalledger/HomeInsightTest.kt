package com.example.personalledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * [HomeInsights] 纯计算逻辑的 JVM 单元测试。
 *
 * 重点覆盖两类容易出错的地方：
 * - 日均支出的分母是「本月已过天数」而不是整月天数；
 * - 上月没有支出时环比必须返回 null（不能除零，也不该把「从无到有」说成增长 100%）。
 */
class HomeInsightTest {

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

    /** 2026-09-10 12:00（本地时区），用于确定「本月已过 10 天」。 */
    private val nowMillis: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 10, 12, 0, 0)
    }.timeInMillis

    private fun totals(vararg pairs: Pair<String, Long>): List<CategoryTotal> =
        pairs.map { CategoryTotal(it.first, it.second) }

    @Test
    fun build_picksTheHighestExpenseCategory() {
        val insight = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 30000, "购物" to 85000, "交通" to 12000),
            lastMonthCategoryTotals = emptyList(),
            nowMillis = nowMillis
        )

        assertEquals("购物", insight.topCategoryName)
        assertEquals(85000L, insight.topCategoryCents)
    }

    @Test
    fun build_computesDailyAverageOverElapsedDaysNotWholeMonth() {
        // 9 月 10 日：已过 10 天，因此日均 = 总支出 / 10
        val insight = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 200000),
            lastMonthCategoryTotals = emptyList(),
            nowMillis = nowMillis
        )

        assertEquals(10, LedgerStats.elapsedDaysInMonth(nowMillis))
        assertEquals(20000L, insight.dailyAverageCents)
    }

    @Test
    fun build_computesMonthOverMonthForIncreaseAndDecrease() {
        val increase = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 120000),
            lastMonthCategoryTotals = totals("餐饮" to 100000),
            nowMillis = nowMillis
        )
        assertEquals(20, increase.monthOverMonthPercent)
        assertTrue(increase.monthOverMonthUp)

        val decrease = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 80000),
            lastMonthCategoryTotals = totals("餐饮" to 100000),
            nowMillis = nowMillis
        )
        assertEquals(-20, decrease.monthOverMonthPercent)
        assertFalse(decrease.monthOverMonthUp)
    }

    @Test
    fun build_returnsNullMonthOverMonthWhenLastMonthHadNoExpense() {
        val insight = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 50000),
            lastMonthCategoryTotals = emptyList(),
            nowMillis = nowMillis
        )

        // 上月为 0 时无法比较：既不能除零，也不该报成「增长 100%」
        assertNull(insight.monthOverMonthPercent)
        assertFalse(insight.monthOverMonthUp)
    }

    @Test
    fun build_noExpenseThisMonthYieldsPlaceholders() {
        val insight = HomeInsights.build(
            monthCategoryTotals = emptyList(),
            lastMonthCategoryTotals = totals("餐饮" to 50000),
            nowMillis = nowMillis
        )

        assertFalse(insight.hasExpense)
        assertNull(insight.topCategoryName)
        assertEquals(0L, insight.topCategoryCents)
        assertEquals(0L, insight.dailyAverageCents)
        // 本月 0、上月有支出 → 环比 -100%
        assertEquals(-100, insight.monthOverMonthPercent)
    }

    @Test
    fun build_ignoresZeroAmountCategoryWhenPickingTop() {
        val insight = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 0, "交通" to 0),
            lastMonthCategoryTotals = emptyList(),
            nowMillis = nowMillis
        )

        // 全是 0 时不应把某个分类当成「最高支出」
        assertNull(insight.topCategoryName)
        assertFalse(insight.hasExpense)
    }

    @Test
    fun monthOverMonthPercent_roundsToNearestIntAndGuardsZero() {
        assertEquals(33, HomeInsights.monthOverMonthPercent(13300, 10000))
        assertEquals(-33, HomeInsights.monthOverMonthPercent(6700, 10000))
        assertEquals(0, HomeInsights.monthOverMonthPercent(10000, 10000))
        assertNull(HomeInsights.monthOverMonthPercent(10000, 0))
        assertNull(HomeInsights.monthOverMonthPercent(0, 0))
    }

    @Test
    fun build_sumsAllCategoriesForMonthTotal() {
        val insight = HomeInsights.build(
            monthCategoryTotals = totals("餐饮" to 30000, "交通" to 12000, "购物" to 8000),
            lastMonthCategoryTotals = totals("餐饮" to 25000),
            nowMillis = nowMillis
        )

        assertEquals(50000L, insight.monthExpenseCents)
        assertEquals(25000L, insight.lastMonthExpenseCents)
    }
}
