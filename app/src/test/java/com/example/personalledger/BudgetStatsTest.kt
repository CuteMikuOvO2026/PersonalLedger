package com.example.personalledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * [BudgetStats] 纯计算逻辑的 JVM 单元测试。
 *
 * 预算状态是「进度条配色」与「超支提醒」共用的唯一判据，阈值一旦漂移，
 * 界面会出现「进度条是红的、却不提醒」这类不一致，因此这里把边界逐个钉死。
 */
class BudgetStatsTest {

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

    private fun rule(
        limitCents: Long,
        categoryName: String? = null,
        period: BudgetPeriod = BudgetPeriod.MONTH
    ) = BudgetRule(period = period, categoryName = categoryName, limitCents = limitCents)

    private fun totals(vararg pairs: Pair<String, Long>): List<CategoryTotal> =
        pairs.map { CategoryTotal(it.first, it.second) }

    // ---------- 已花金额的归集 ----------

    @Test
    fun overallRule_sumsEveryCategory() {
        val totals = totals("餐饮" to 30000, "交通" to 12000, "购物" to 8000)

        assertEquals(50000L, BudgetStats.spentOf(rule(limitCents = 100000), totals))
    }

    @Test
    fun categoryRule_usesOnlyItsOwnCategory() {
        val totals = totals("餐饮" to 30000, "交通" to 12000)

        assertEquals(30000L, BudgetStats.spentOf(rule(100000, "餐饮"), totals))
    }

    @Test
    fun categoryRule_withNoRecordSpendsZero() {
        val totals = totals("交通" to 12000)

        assertEquals(0L, BudgetStats.spentOf(rule(100000, "餐饮"), totals))
    }

    @Test
    fun overallRule_isRecognisedByBlankCategory() {
        assertTrue(rule(1000).isOverall)
        assertTrue(BudgetRule(BudgetPeriod.MONTH, "", 1000).isOverall)
        assertFalse(rule(1000, "餐饮").isOverall)
    }

    // ---------- 状态阈值 ----------

    @Test
    fun state_isNormalBelowWarningThreshold() {
        assertEquals(BudgetState.NORMAL, BudgetStats.buildProgress(rule(10000), 0L).state)
        assertEquals(BudgetState.NORMAL, BudgetStats.buildProgress(rule(10000), 7999L).state)
    }

    @Test
    fun state_becomesWarningAtEightyPercent() {
        // 80% 正好进入预警
        val progress = BudgetStats.buildProgress(rule(10000), 8000L)
        assertEquals(BudgetState.WARNING, progress.state)
        assertEquals(80, progress.percent)

        assertEquals(BudgetState.WARNING, BudgetStats.buildProgress(rule(10000), 9999L).state)
    }

    @Test
    fun state_becomesOverExactlyAtLimit() {
        val progress = BudgetStats.buildProgress(rule(10000), 10000L)
        assertEquals(BudgetState.OVER, progress.state)
        assertEquals(100, progress.percent)
    }

    @Test
    fun percent_canExceedOneHundredForRealOverspend() {
        val progress = BudgetStats.buildProgress(rule(10000), 15000L)

        assertEquals(BudgetState.OVER, progress.state)
        assertEquals(150, progress.percent)
        assertEquals(5000L, progress.overspentCents)
        assertEquals(0L, progress.remainingCents)
    }

    @Test
    fun remaining_isLimitMinusSpentAndNeverNegative() {
        assertEquals(4000L, BudgetStats.buildProgress(rule(10000), 6000L).remainingCents)
        assertEquals(0L, BudgetStats.buildProgress(rule(10000), 12000L).remainingCents)
        assertEquals(0L, BudgetStats.buildProgress(rule(10000), 10000L).overspentCents)
    }

    @Test
    fun stateOfPercent_matchesBuildProgress() {
        // 两者必须给出一致的结论，否则首页进度条（只有百分比）与提醒会打架
        listOf(0, 50, 79, 80, 99, 100, 150).forEach { percent ->
            val limit = 10000L
            val spent = limit * percent / 100
            assertEquals(
                "百分比 $percent 时两者应一致",
                BudgetStats.buildProgress(rule(limit), spent).state,
                BudgetStats.stateOfPercent(percent)
            )
        }
    }

    @Test
    fun zeroLimit_isTreatedAsZeroPercentWithoutCrashing() {
        val progress = BudgetStats.buildProgress(rule(limitCents = 0), spentCents = 5000L)

        assertEquals(0, progress.percent)
        assertEquals(BudgetState.NORMAL, progress.state)
    }

    // ---------- 批量与总预算查找 ----------

    @Test
    fun buildAllProgress_keepsInputOrder() {
        val rules = listOf(
            rule(limitCents = 100000),
            rule(30000, "餐饮"),
            rule(20000, "交通", BudgetPeriod.WEEK)
        )

        val progress = BudgetStats.buildAllProgress(rules, totals("餐饮" to 30000, "交通" to 20000))

        assertEquals(rules, progress.map { it.rule })
        assertEquals(50000L, progress[0].spentCents)
        assertEquals(BudgetState.OVER, progress[1].state)
        assertEquals(BudgetState.OVER, progress[2].state)
    }

    @Test
    fun overallLimitCents_picksRuleByPeriodAndFallsBackToZero() {
        val rules = listOf(
            rule(500000),
            rule(120000, "餐饮"),
            rule(80000, period = BudgetPeriod.WEEK)
        )

        assertEquals(500000L, BudgetStats.overallLimitCents(rules, BudgetPeriod.MONTH))
        assertEquals(80000L, BudgetStats.overallLimitCents(rules, BudgetPeriod.WEEK))
        // 没有总预算只有分类预算时返回 0（界面按「未设置」展示）
        assertEquals(0L, BudgetStats.overallLimitCents(listOf(rule(1000, "餐饮")), BudgetPeriod.MONTH))
        assertEquals(0L, BudgetStats.overallLimitCents(emptyList(), BudgetPeriod.MONTH))
    }

    @Test
    fun budgetPeriod_fromKeyFallsBackToMonth() {
        assertEquals(BudgetPeriod.MONTH, BudgetPeriod.fromKey("month"))
        assertEquals(BudgetPeriod.WEEK, BudgetPeriod.fromKey("week"))
        // 未知取值兜底为「每月」，避免历史脏数据让规则消失
        assertEquals(BudgetPeriod.MONTH, BudgetPeriod.fromKey("quarter"))
    }

    @Test
    fun budgetRule_roundTripsThroughEntity() {
        listOf(
            rule(150000),
            rule(30000, "餐饮"),
            rule(20000, "交通", BudgetPeriod.WEEK)
        ).forEach { original ->
            val restored = original.toEntity().toRule()
            assertEquals(original.period, restored.period)
            assertEquals(original.categoryName, restored.categoryName)
            assertEquals(original.limitCents, restored.limitCents)
            // 界面主键稳定，便于列表比对
            assertEquals(original.key, restored.key)
        }
    }

    // ---------- 周期区间 ----------

    @Test
    fun monthRange_coversWholeMonthFromTheFirst() {
        val now = System.currentTimeMillis()
        val range = LedgerStats.monthRange(now)

        val start = Calendar.getInstance().apply { timeInMillis = range.startMillis }
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))

        // 区间包含当前时刻，且右端正好是下月 1 日的前一毫秒
        assertTrue(range.startMillis <= now && now <= range.endMillis)
        val end = Calendar.getInstance().apply {
            timeInMillis = range.endMillis
            add(Calendar.MILLISECOND, 1)
        }
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, end.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun weekRange_startsOnMondayAndSpansSevenDays() {
        val now = System.currentTimeMillis()
        val range = LedgerStats.weekRange(now)

        val start = Calendar.getInstance().apply { timeInMillis = range.startMillis }
        assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertTrue(range.startMillis <= now && now <= range.endMillis)

        // 跨度为 7 个自然日：右端 +1ms 就是下周一零点
        // （不断言「恰好 7×24 小时」，因为夏令时地区的一周可能少/多一小时）
        val nextMonday = Calendar.getInstance().apply {
            timeInMillis = range.endMillis
            add(Calendar.MILLISECOND, 1)
        }
        assertEquals(Calendar.MONDAY, nextMonday.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, nextMonday.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, nextMonday.get(Calendar.MINUTE))
    }

    @Test
    fun currentPeriodRange_mapsPeriodToItsRange() {
        val now = System.currentTimeMillis()

        assertEquals(LedgerStats.monthRange(now), LedgerStats.currentPeriodRange(BudgetPeriod.MONTH, now))
        assertEquals(LedgerStats.weekRange(now), LedgerStats.currentPeriodRange(BudgetPeriod.WEEK, now))
    }
}
