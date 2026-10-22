package com.example.personalledger

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 把 [AiRangeSpec] 换算成毫秒区间 —— 纯函数，不碰 Android 类型。
 *
 * 为什么由代码算而不是让模型直接给两个时间戳：模型没有日历，跨月、跨年、闰年、夏令时全靠猜，
 * 而「本月」这种说法的边界一旦错一天，统计结果就整体偏移。让模型只选一个**枚举**，
 * 边界由 [LedgerStats] 里那套已经被预算与报表共用的算法给出，口径天然一致。
 *
 * 所有边界都取本地时区，与首页「今日 / 本月」、报表「近 7 天」保持同一套口径。
 */
object AiPeriods {

    private const val DAY_MS = 86_400_000L

    private const val PATTERN_DAY = "yyyy-MM-dd"

    /** 解析出的区间。两端都**包含**，与 [PeriodRange] 的既有约定一致。 */
    fun resolve(spec: AiRangeSpec, nowMillis: Long): PeriodRange = when (spec.type) {
        AiRangeType.TODAY -> LedgerStats.todayRange(nowMillis)

        AiRangeType.YESTERDAY -> shiftDay(LedgerStats.startOfDay(nowMillis), -1)

        AiRangeType.THIS_WEEK -> LedgerStats.weekRange(nowMillis)

        AiRangeType.LAST_WEEK -> {
            val thisWeekStart = LedgerStats.weekRange(nowMillis).startMillis
            val lastWeekStart = shiftDay(thisWeekStart, -7).startMillis
            PeriodRange(lastWeekStart, thisWeekStart - 1)
        }

        AiRangeType.THIS_MONTH -> LedgerStats.monthRange(nowMillis)

        AiRangeType.LAST_MONTH -> LedgerStats.lastMonthRange(nowMillis)

        AiRangeType.THIS_YEAR -> yearRange(nowMillis)

        // 「全部」用 Long 的极值做开区间：DAO 里就是 `>= from AND <= to`，
        // 不需要为它再加一个「不过滤时间」的分支（少一个分支就少一处口径不一致）。
        AiRangeType.ALL -> PeriodRange(Long.MIN_VALUE, Long.MAX_VALUE)

        AiRangeType.CUSTOM -> customRange(spec, nowMillis)
    }

    /** 区间的可读描述，例如 `2026-10-01 ~ 2026-10-31`；「全部」返回空串（由界面换成文案）。 */
    fun describe(range: PeriodRange): String {
        if (range.startMillis <= 0L) return ""
        val start = formatDay(range.startMillis)
        val end = formatDay(range.endMillis)
        return if (start == end) start else "$start ~ $end"
    }

    /**
     * 区间覆盖的天数，用于日均支出。
     *
     * 区间还没走完时按「已经过完的天数」算：月中看本月日均，分母必须是 15 而不是 31，
     * 否则月初的日均会明显偏低、失去参考意义（与 [LedgerStats.elapsedDaysInMonth] 同一取舍）。
     */
    fun daysOf(range: PeriodRange, nowMillis: Long): Int? {
        if (range.startMillis <= 0L) return null
        val end = if (range.endMillis > nowMillis) nowMillis else range.endMillis
        val startDay = LedgerStats.startOfDay(range.startMillis)
        val endDay = LedgerStats.startOfDay(end)
        val days = ((endDay - startDay).toDouble() / DAY_MS).toInt() + 1
        return days.coerceAtLeast(1)
    }

    /**
     * 自定义区间：`yyyy-MM-dd` → `[起始日 00:00, 结束日 23:59:59.999]`。
     *
     * 只给一端时另一端取同一天；两端都给反了就自动交换（用户说「10-01 到 09-30」多半是口误，
     * 与其给一个空区间，不如按他真正想要的方向理解）。
     */
    private fun customRange(spec: AiRangeSpec, nowMillis: Long): PeriodRange {
        val start = parseDay(spec.startDate)
        val end = parseDay(spec.endDate)

        val (fromDay, toDay) = when {
            start != null && end != null ->
                if (start <= end) start to end else end to start

            start != null -> start to start
            end != null -> end to end
            else -> LedgerStats.startOfDay(nowMillis).let { it to it }
        }

        return PeriodRange(fromDay, shiftDay(toDay, 1).startMillis - 1)
    }

    /** 当年区间：1 月 1 日零点到 12 月 31 日最后一毫秒。 */
    private fun yearRange(nowMillis: Long): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.YEAR, 1)
        return PeriodRange(start, cal.timeInMillis - 1)
    }

    /**
     * 以某个「某日零点」为锚点平移整天。
     *
     * 用 [Calendar.add] 而不是 `+ n * 86400000`：夏令时那天只有 23 或 25 小时，
     * 用毫秒加减会得到前一天的 23:00 或后一天的 01:00，于是「昨天」里混进了别的日子。
     */
    private fun shiftDay(dayStartMillis: Long, days: Int): PeriodRange {
        val cal = Calendar.getInstance().apply {
            timeInMillis = dayStartMillis
            add(Calendar.DAY_OF_MONTH, days)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        return PeriodRange(start, cal.timeInMillis - 1)
    }

    private fun parseDay(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            // 非宽容模式：`2026-02-30` 必须解析失败并回落到默认区间，而不是悄悄变成 3 月 2 日
            SimpleDateFormat(PATTERN_DAY, Locale.ROOT).apply { isLenient = false }.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDay(millis: Long): String =
        SimpleDateFormat(PATTERN_DAY, Locale.getDefault()).format(millis)
}
