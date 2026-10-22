package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [AiTargetResolver] 的匹配规则。
 *
 * 这些用例守的是「改错账 / 删错账」这条线：匹配放宽一点，用户说「那笔打车」就可能落到别的记录上，
 * 而卡片上显示的是一条**看起来也合理**的账 —— 用户点确认时不会察觉。所以这里既测「该匹配上的」，
 * 也测「不该匹配上的」。
 */
class AiTargetResolverTest {

    private val now = at(2026, 5, 15, 10, 0)

    private val yesterday = at(2026, 5, 14, 9, 0)
    private val todayMorning = at(2026, 5, 15, 8, 0)
    private val todayNoon = at(2026, 5, 15, 12, 0)

    private val taxi = item("a", 2_300, true, "交通", yesterday, "滴滴打车")
    private val coffee = item("b", 1_500, true, "餐饮", todayMorning, "咖啡")
    private val lunch = item("c", 2_200, true, "餐饮", todayNoon, "午饭")
    private val salary = item("d", 1_200_000, false, "工资", todayMorning, "工资到账")

    private val all = listOf(taxi, coffee, lunch, salary)

    @Test
    fun `关键词加日期偏移只命中那一笔`() {
        val match = AiTargetResolver.resolve(
            AiTargetSpec(keyword = "打车", dayOffset = -1),
            all,
            now
        )

        assertTrue(match is AiTargetMatch.Found)
        assertEquals(listOf("a"), (match as AiTargetMatch.Found).entries.map { it.id })
    }

    @Test
    fun `关键词也能匹配分类名`() {
        val match = AiTargetResolver.resolve(AiTargetSpec(keyword = "工资"), all, now)

        assertEquals(listOf("d"), (match as AiTargetMatch.Found).entries.map { it.id })
    }

    @Test
    fun `只给关键词命中多笔时返回候选而不是替用户挑一笔`() {
        val match = AiTargetResolver.resolve(AiTargetSpec(keyword = "餐"), all, now)

        assertTrue("两笔餐饮应判为歧义，实际 $match", match is AiTargetMatch.Ambiguous)
        assertEquals(2, (match as AiTargetMatch.Ambiguous).total)
    }

    @Test
    fun `金额精确匹配不会命中相近的金额`() {
        val match = AiTargetResolver.resolve(AiTargetSpec(amountYuan = 23.0), all, now)

        assertEquals(listOf("a"), (match as AiTargetMatch.Found).entries.map { it.id })
    }

    @Test
    fun `latest 取时间最新的一笔且总数记为 1`() {
        val match = AiTargetResolver.resolve(AiTargetSpec(latest = true), all, now) as AiTargetMatch.Found

        assertEquals(listOf("c"), match.entries.map { it.id })
        // 总数必须是 1，而不是「候选有多少条」：否则「删掉最后一笔」会被上层判成超上限
        assertEquals(1, match.total)
    }

    @Test
    fun `all 返回全部命中并带上真实总数`() {
        val match = AiTargetResolver.resolve(
            AiTargetSpec(isExpense = true, all = true),
            all,
            now
        )

        val found = match as AiTargetMatch.Found
        assertEquals(3, found.entries.size)
        assertEquals(3, found.total)
    }

    @Test
    fun `一个条件都没有时不匹配任何东西`() {
        assertEquals(AiTargetMatch.None, AiTargetResolver.resolve(AiTargetSpec(), all, now))
    }

    @Test
    fun `绝对日期与相对日期都能定位到同一天`() {
        val byDate = AiTargetResolver.resolve(AiTargetSpec(date = "2026-05-14"), all, now)
        val byOffset = AiTargetResolver.resolve(AiTargetSpec(dayOffset = -1), all, now)

        assertEquals((byDate as AiTargetMatch.Found).entries.map { it.id }, (byOffset as AiTargetMatch.Found).entries.map { it.id })
    }

    @Test
    fun `昨天不会把今天凌晨算进来`() {
        // 这条守的是「日界」：用毫秒做 24 小时减法，会把今天 01:00 算成昨天
        val earlyToday = item("e", 999, true, "餐饮", at(2026, 5, 15, 1, 0), "夜宵")
        val match = AiTargetResolver.resolve(
            AiTargetSpec(dayOffset = -1),
            all + earlyToday,
            now
        )

        assertEquals(listOf("a"), (match as AiTargetMatch.Found).entries.map { it.id })
    }

    @Test
    fun `关键词不命中时返回 None`() {
        assertEquals(
            AiTargetMatch.None,
            AiTargetResolver.resolve(AiTargetSpec(keyword = "健身房"), all, now)
        )
    }

    private fun item(
        id: String,
        cents: Long,
        isExpense: Boolean,
        category: String,
        millis: Long,
        note: String
    ) = LedgerItem(
        id = id,
        amountCents = cents,
        note = note,
        timeMillis = millis,
        isExpense = isExpense,
        categoryName = category,
        categoryIconRes = 0
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
