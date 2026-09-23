package com.example.personalledger

import java.util.Calendar
import java.util.TimeZone

/**
 * 日期 / 时刻选择器的取值。
 *
 * - [utcDateMillis]：**UTC 零点**毫秒。这是 MaterialDatePicker 的约定，
 *   `setSelection()` 收、`addOnPositiveButtonClickListener` 给，都是这个表示。
 * - [hourOfDay] / [minute]：本地时区的时分。
 */
data class PickerDateTime(
    val utcDateMillis: Long,
    val hourOfDay: Int,
    val minute: Int
)

/**
 * 记账时间在「选择器取值」与「本地时间戳」之间的换算。
 *
 * 这里有一个必须显式处理的坑：[MaterialDatePicker] 的日期是 **UTC 零点毫秒**，
 * 不能直接当成本地时间戳使用。在东八区（UTC+8）把「3 月 1 日的 UTC 零点」
 * 直接存进去，实际对应本地 3 月 1 日 08:00 尚可；但如果反过来把「本地时间戳」
 * 直接喂给选择器，选择器会显示成前一天 16:00 —— 表现为「选了 3 月 1 日，
 * 列表里却显示 2 月 28 日」。因此本对象明确区分两种表示，并保证来回换算自洽。
 *
 * 秒与毫秒在选择器里无法表达，换算时统一归零（记账精确到分钟足够）。
 */
object LedgerDateTime {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * 把本地时间戳拆成选择器需要的表示。
     *
     * 关键点：取**本地时区**的年月日，再构造该日期的 UTC 零点，
     * 这样选择器显示的就是用户看到的那个日期。
     */
    fun toPicker(localMillis: Long, zone: TimeZone = TimeZone.getDefault()): PickerDateTime {
        val local = Calendar.getInstance(zone).apply { timeInMillis = localMillis }
        val utcMidnight = Calendar.getInstance(UTC).apply {
            clear()
            set(
                local.get(Calendar.YEAR),
                local.get(Calendar.MONTH),
                local.get(Calendar.DAY_OF_MONTH),
                0,
                0,
                0
            )
        }
        return PickerDateTime(
            utcDateMillis = utcMidnight.timeInMillis,
            hourOfDay = local.get(Calendar.HOUR_OF_DAY),
            minute = local.get(Calendar.MINUTE)
        )
    }

    /**
     * 把选择器取值合成本地时间戳。
     *
     * 关键点：先从 UTC 表示里取出年月日，再以**本地时区**构造当天的时分，
     * 保证「选了什么日期」与「存进去的日期」永远一致。
     * 时分会被收敛到合法区间，避免异常取值把时间算到别的天。
     */
    fun fromPicker(value: PickerDateTime, zone: TimeZone = TimeZone.getDefault()): Long {
        val utc = Calendar.getInstance(UTC).apply { timeInMillis = value.utcDateMillis }
        return Calendar.getInstance(zone).apply {
            clear()
            set(
                utc.get(Calendar.YEAR),
                utc.get(Calendar.MONTH),
                utc.get(Calendar.DAY_OF_MONTH),
                value.hourOfDay.coerceIn(0, 23),
                value.minute.coerceIn(0, 59)
            )
        }.timeInMillis
    }

    // ---------- 日期区间筛选：把「某一天」换算成本地时间戳的上下界 ----------

    /**
     * 选择器的 UTC 零点 → 该日在**本地时区的零点**。
     *
     * 用作筛选区间的**下界**（DAO 里是 `timeMillis >= dateFrom`）。
     */
    fun dayStartFromPicker(utcDateMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        fromPicker(PickerDateTime(utcDateMillis, 0, 0), zone)

    /**
     * 选择器的 UTC 零点 → 该日在**本地时区的最后一毫秒**。
     *
     * 用作筛选区间的**上界**。DAO 里的比较是 `timeMillis <= dateTo`（闭区间），
     * 所以上界必须是 23:59:59.999 而不是次日零点，否则结束日当天的记录会被整日漏掉。
     *
     * 用「当天零点 + 1 天 − 1 毫秒」而不是写死 23:59，是为了顺带兼容夏令时地区
     * （那里的一天可能是 23 或 25 小时）。
     */
    fun dayEndFromPicker(utcDateMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = dayStartFromPicker(utcDateMillis, zone)
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
        }.timeInMillis

    /**
     * 本地时间戳 → 选择器需要的 UTC 零点，用于把已选区间**回显**到选择器上。
     *
     * 没有它就会出现「筛完再打开筛选弹窗，日期显示成前一天」。
     */
    fun dayToPicker(localMillis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        toPicker(localMillis, zone).utcDateMillis
}
