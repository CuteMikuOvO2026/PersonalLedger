package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [LedgerDateTime] 的 JVM 单元测试。
 *
 * 核心是锁住「MaterialDatePicker 用 UTC 零点表示日期」这个坑：
 * 一旦把本地时间戳直接当作选择器取值（或反过来），在东八区会整体偏移 8 小时，
 * 表现为「选了 3 月 1 日、列表里显示 2 月 28 日」。这里用固定时区做确定性断言。
 */
class LedgerDateTimeTest {

    private val zone: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    /** 选择器约定：某个日期的 UTC 零点。 */
    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }.timeInMillis

    private fun dayOfMonthInUtc(millis: Long): Int =
        Calendar.getInstance(utc).apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

    // ---------- 核心：UTC 日期 vs 本地时间戳 ----------

    @Test
    fun toPicker_returnsUtcMidnightOfTheLocalDate() {
        val local = localMillis(2026, 3, 1, 12, 30)

        val picker = LedgerDateTime.toPicker(local, zone)

        // 选择器拿到的必须是「本地 3 月 1 日」对应的 UTC 零点
        assertEquals(utcMidnight(2026, 3, 1), picker.utcDateMillis)
        assertEquals(12, picker.hourOfDay)
        assertEquals(30, picker.minute)
    }

    @Test
    fun toPicker_naiveImplementationWouldShiftToPreviousDay() {
        // 用本地零点做取证：这是「某个日期」最自然的表示，也是坑最容易暴露的地方
        val localMidnight = localMillis(2026, 3, 1, 0, 0)

        // 反例：把本地零点直接按 UTC 解释，会落到 2 月 28 日 16:00 —— 日期整整差一天
        assertEquals(28, dayOfMonthInUtc(localMidnight))
        assertNotEquals(localMidnight, LedgerDateTime.toPicker(localMidnight, zone).utcDateMillis)

        // 正确实现给出 3 月 1 日的 UTC 零点
        val picker = LedgerDateTime.toPicker(localMidnight, zone)
        assertEquals(utcMidnight(2026, 3, 1), picker.utcDateMillis)
        assertEquals(1, dayOfMonthInUtc(picker.utcDateMillis))
        assertEquals(0, picker.hourOfDay)
    }

    @Test
    fun fromPicker_keepsPickedLocalDate() {
        val picked = PickerDateTime(
            utcDateMillis = utcMidnight(2026, 3, 1),
            hourOfDay = 8,
            minute = 30
        )

        // 选的是 3 月 1 日 08:30，存进去就必须是本地 3 月 1 日 08:30
        assertEquals(localMillis(2026, 3, 1, 8, 30), LedgerDateTime.fromPicker(picked, zone))
    }

    @Test
    fun roundTrip_preservesMinutePrecision() {
        val samples = listOf(
            localMillis(2026, 1, 1, 0, 0),
            localMillis(2026, 3, 1, 8, 30),
            localMillis(2026, 6, 15, 12, 0),
            localMillis(2026, 12, 31, 23, 59),
            localMillis(2024, 2, 29, 12, 0) // 闰日
        )

        samples.forEach { millis ->
            val back = LedgerDateTime.fromPicker(LedgerDateTime.toPicker(millis, zone), zone)
            assertEquals("往返换算应保持不变", millis, back)
        }
    }

    @Test
    fun dateBoundary_staysOnItsOwnLocalDay() {
        val marchFirstMidnight = localMillis(2026, 3, 1, 0, 0)
        val februaryLastMinute = localMillis(2026, 2, 28, 23, 59)

        val marchPicker = LedgerDateTime.toPicker(marchFirstMidnight, zone)
        val februaryPicker = LedgerDateTime.toPicker(februaryLastMinute, zone)

        assertEquals(0, marchPicker.hourOfDay)
        assertEquals(23, februaryPicker.hourOfDay)
        // 东八区无夏令时，跨月的相邻两天在 UTC 日期上正好差 24 小时
        assertEquals(24 * 60 * 60 * 1000L, marchPicker.utcDateMillis - februaryPicker.utcDateMillis)

        // 两者各自往返后仍落在自己的那一天
        assertEquals(marchFirstMidnight, LedgerDateTime.fromPicker(marchPicker, zone))
        assertEquals(februaryLastMinute, LedgerDateTime.fromPicker(februaryPicker, zone))
    }

    // ---------- 异常取值兜底 ----------

    @Test
    fun fromPicker_clampsIllegalTimeValues() {
        val base = utcMidnight(2026, 6, 15)

        // 时分越界不应把时间算到相邻的日期上
        assertEquals(
            localMillis(2026, 6, 15, 23, 59),
            LedgerDateTime.fromPicker(PickerDateTime(base, hourOfDay = 99, minute = 99), zone)
        )
        assertEquals(
            localMillis(2026, 6, 15, 0, 0),
            LedgerDateTime.fromPicker(PickerDateTime(base, hourOfDay = -5, minute = -1), zone)
        )
    }

    @Test
    fun toPicker_dropsSecondsAndMillis() {
        // 选择器只能精确到分钟，秒/毫秒在往返后归零（记账精度足够）
        val withSeconds = Calendar.getInstance(zone).apply {
            clear()
            set(2026, Calendar.SEPTEMBER, 16, 12, 30, 45)
        }.timeInMillis

        val picker = LedgerDateTime.toPicker(withSeconds, zone)
        assertEquals(30, picker.minute)
        assertEquals(
            Calendar.getInstance(zone).apply {
                clear()
                set(2026, Calendar.SEPTEMBER, 16, 12, 30, 0)
            }.timeInMillis,
            LedgerDateTime.fromPicker(picker, zone)
        )
    }
}
