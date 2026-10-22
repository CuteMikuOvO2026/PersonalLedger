package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * [AiPeriods] 的区间换算。
 *
 * 这些边界一旦错一天，统计结果整体偏移，而界面上的数字看起来仍然「很正常」——
 * 用户不会去数「本月」到底包不包含 1 号零点那一秒。所以逐条钉死。
 */
class AiPeriodsTest {

    /** 2026-05-15 10:00，星期五。 */
    private val now = at(2026, 5, 15, 10, 0)

    @Test
    fun `本月从 1 日零点到月末最后一毫秒`() {
        val range = AiPeriods.resolve(AiRangeSpec(AiRangeType.THIS_MONTH), now)

        assertEquals(at(2026, 5, 1, 0, 0), range.startMillis)
        assertEquals(at(2026, 6, 1, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `上月是完整的一个月`() {
        val range = AiPeriods.resolve(AiRangeSpec(AiRangeType.LAST_MONTH), now)

        assertEquals(at(2026, 4, 1, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 1, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `昨天只有一天`() {
        val range = AiPeriods.resolve(AiRangeSpec(AiRangeType.YESTERDAY), now)

        assertEquals(at(2026, 5, 14, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 15, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `本周从周一开始`() {
        // 2026-05-15 是周五，本周一是 05-11。
        // 刻意固定周一为周首而不是跟随系统设置：预算与报表都按这个口径。
        val range = AiPeriods.resolve(AiRangeSpec(AiRangeType.THIS_WEEK), now)

        assertEquals(at(2026, 5, 11, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 18, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `自定义区间的结束日包含整天`() {
        val range = AiPeriods.resolve(
            AiRangeSpec(AiRangeType.CUSTOM, startDate = "2026-05-03", endDate = "2026-05-05"),
            now
        )

        assertEquals(at(2026, 5, 3, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 6, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `自定义区间只给一端时按单日处理`() {
        val range = AiPeriods.resolve(
            AiRangeSpec(AiRangeType.CUSTOM, startDate = "2026-05-03"),
            now
        )

        assertEquals(at(2026, 5, 3, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 4, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `自定义区间写反了自动交换`() {
        val range = AiPeriods.resolve(
            AiRangeSpec(AiRangeType.CUSTOM, startDate = "2026-05-09", endDate = "2026-05-03"),
            now
        )

        assertEquals(at(2026, 5, 3, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 10, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `全部时间不限制两端`() {
        val range = AiPeriods.resolve(AiRangeSpec(AiRangeType.ALL), now)

        assertEquals(Long.MIN_VALUE, range.startMillis)
        assertEquals(Long.MAX_VALUE, range.endMillis)
    }

    @Test
    fun `非法日期回落到今天而不是解析成别的日子`() {
        // `2026-02-30` 在宽容模式下会变成 3 月 2 日，统计口径就悄悄变了
        val range = AiPeriods.resolve(
            AiRangeSpec(AiRangeType.CUSTOM, startDate = "2026-02-30", endDate = "2026-02-30"),
            now
        )

        assertEquals(at(2026, 5, 15, 0, 0), range.startMillis)
        assertEquals(at(2026, 5, 16, 0, 0) - 1, range.endMillis)
    }

    @Test
    fun `日均按已过天数算而不是整月天数`() {
        // 月中看本月，分母必须是 15（含今天），用 31 会让日均明显偏低
        assertEquals(15, AiPeriods.daysOf(AiPeriods.resolve(AiRangeSpec(AiRangeType.THIS_MONTH), now), now))

        // 上个月已经走完，按整月 30 天算
        assertEquals(30, AiPeriods.daysOf(AiPeriods.resolve(AiRangeSpec(AiRangeType.LAST_MONTH), now), now))
    }

    @Test
    fun `全部时间算不出天数`() {
        assertNull(AiPeriods.daysOf(AiPeriods.resolve(AiRangeSpec(AiRangeType.ALL), now), now))
    }

    @Test
    fun `描述单日与跨日两种形态`() {
        val oneDay = AiPeriods.resolve(
            AiRangeSpec(AiRangeType.CUSTOM, startDate = "2026-05-03", endDate = "2026-05-03"),
            now
        )
        assertEquals("2026-05-03", AiPeriods.describe(oneDay))

        val span = AiPeriods.resolve(AiRangeSpec(AiRangeType.THIS_MONTH), now)
        assertEquals("2026-05-01 ~ 2026-05-31", AiPeriods.describe(span))

        // 「全部时间」没有可读区间，界面用文案兜底
        assertEquals("", AiPeriods.describe(AiPeriods.resolve(AiRangeSpec(AiRangeType.ALL), now)))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
