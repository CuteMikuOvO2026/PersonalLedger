package com.example.personalledger

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 统计卡片里那几行文字 —— **纯函数**，所有数字都由调用方从数据库算好后传进来。
 *
 * 这是整个「助手能干活」里最容易被做坏的一处：把 `{"expense": 1234.5}` 喂给模型，
 * 让它写一段自然语言总结，看起来最像「AI」，但它会**算错**——加错、漏掉一笔、
 * 把 1.2k 写成 1.5k。这类错误在聊天记录里看起来完全正常，用户不会去验算。
 *
 * 所以这里的取舍是：**模型负责选区间，代码负责出数字，代码负责出文字**。
 * 模型在这一步的唯一产出是 `range` 那一枚举值，它连一个金额都没碰过。
 *
 * ⚠️ 这里的文案是**硬编码中文**，与项目其它界面文案走 `strings.xml` 的做法不同。理由：本应用只有
 * 中文资源，而这类句子要塞进纯函数才测得了；若将来要国际化，应改成由调用方传入一个格式化器，
 * 而不是把这些句子搬回 `R.string` 再到域层拼字符串。
 */
object AiSummary {

    /** 收支合计（金额单位为分）。 */
    data class Totals(val expenseCents: Long, val incomeCents: Long, val count: Int)

    private const val TOP_CATEGORIES = 3

    /**
     * @param days 区间已过天数，用于日均；null 表示算不出（「全部时间」）。
     * @param largest 区间内金额最大的一笔支出，没有则为 null。
     */
    fun lines(
        totals: Totals,
        categories: List<CategoryTotal>,
        largest: LedgerItem?,
        days: Int?
    ): List<String> {
        if (totals.count == 0) return listOf(NO_RECORD)

        val result = mutableListOf<String>()

        result += "支出 ${money(totals.expenseCents)}　收入 ${money(totals.incomeCents)}"

        val daily = if (days != null && days > 0 && totals.expenseCents > 0) {
            "　日均支出 ${money((totals.expenseCents.toDouble() / days).roundToLong())}"
        } else {
            ""
        }
        result += "共 ${totals.count} 笔$daily"

        val top = categories.filter { it.totalCents > 0 }.take(TOP_CATEGORIES)
        if (top.isNotEmpty()) {
            val text = top.joinToString("　") { category ->
                val percent = if (totals.expenseCents > 0) {
                    "（${(category.totalCents * 100.0 / totals.expenseCents).roundToInt()}%）"
                } else {
                    ""
                }
                "${category.categoryName} ${money(category.totalCents)}$percent"
            }
            result += "支出分类：$text"
        }

        if (largest != null) {
            val note = largest.note.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            result += "最大一笔：${largest.categoryName} ${money(largest.amountCents)}$note · " +
                day(largest.timeMillis)
        }

        return result
    }

    /** 空区间时唯一的提示。与「解析失败」区分开：这里不是模型没听懂，是真的没有记录。 */
    const val NO_RECORD = "这个时间段没有记录。"

    /**
     * 一条记录的单行描述，用于「找到好几笔，你说的是哪一笔」这类候选列表。
     *
     * 格式刻意包含日期 + 分类 + 金额 + 备注四要素：用户要把它们和自己记忆里的那笔对上，
     * 少任何一项都会让「1 月 3 日的餐饮 15 元」和「1 月 3 日的餐饮 15 元（下午茶）」看起来一样。
     */
    fun entryLine(item: LedgerItem): String {
        val sign = if (item.isExpense) "-" else "+"
        val note = item.note.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
        return "${day(item.timeMillis)} · ${item.categoryName} · " +
            "$sign${LedgerStats.formatAmount(item.amountCents)}$note"
    }

    /** `¥1,234.50`。带千分位是因为账本数字动辄过万，不带的话一眼读不出量级。 */
    private fun money(cents: Long): String =
        "¥" + String.format(Locale.getDefault(), "%,.2f", cents / 100.0)

    private fun day(millis: Long): String =
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(millis)
}
