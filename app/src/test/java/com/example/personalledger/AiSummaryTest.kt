package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [AiSummary] 的文案与数字。
 *
 * 这一层的意义是「数字不经过模型」：这里只要有一个百分比算错，用户看到的就是一条
 * **看起来很正常的错误结论**。所以除了格式，百分比与日均也要钉住。
 */
class AiSummaryTest {

    private val now = Calendar.getInstance().apply {
        set(2026, 4, 15, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `没有记录时只给一句提示`() {
        val lines = AiSummary.lines(
            totals = AiSummary.Totals(0, 0, 0),
            categories = emptyList(),
            largest = null,
            days = 15
        )

        assertEquals(listOf(AiSummary.NO_RECORD), lines)
    }

    @Test
    fun `汇总包含收支、笔数、分类占比与最大一笔`() {
        val lines = AiSummary.lines(
            totals = AiSummary.Totals(expenseCents = 12_345, incomeCents = 800_000, count = 3),
            categories = listOf(
                CategoryTotal("餐饮", 4_567),
                CategoryTotal("交通", 1_000)
            ),
            largest = LedgerItem(
                id = "a",
                amountCents = 4_567,
                note = "火锅",
                timeMillis = now,
                isExpense = true,
                categoryName = "餐饮",
                categoryIconRes = 0
            ),
            days = 15
        )

        assertTrue("应有两行以上，实际 $lines", lines.size >= 4)
        assertTrue("收支合计缺失：$lines", lines[0].startsWith("支出 "))
        assertTrue("收入缺失：$lines", lines[0].contains("收入"))
        assertTrue("笔数缺失：$lines", lines[1].contains("共 3 笔"))
        assertTrue("日均缺失：$lines", lines[1].contains("日均支出"))
        // 4567 / 12345 ≈ 37%
        assertTrue("分类占比算错：$lines", lines[2].contains("37%"))
        assertTrue("最大一笔缺失：$lines", lines[3].contains("最大一笔"))
        assertTrue("最大一笔应带备注：$lines", lines[3].contains("火锅"))
    }

    @Test
    fun `算不出天数时不显示日均`() {
        val lines = AiSummary.lines(
            totals = AiSummary.Totals(1_000, 0, 1),
            categories = listOf(CategoryTotal("餐饮", 1_000)),
            largest = null,
            days = null
        )

        assertFalse("「全部时间」不该出现日均：$lines", lines.any { it.contains("日均") })
    }

    @Test
    fun `支出为零时不做除法也不输出占比`() {
        // 全是收入的时间段：expenseCents = 0，百分比必须跳过而不是除以 0
        val lines = AiSummary.lines(
            totals = AiSummary.Totals(expenseCents = 0, incomeCents = 500_000, count = 1),
            categories = emptyList(),
            largest = null,
            days = 30
        )

        assertTrue(lines.any { it.contains("收入") })
        assertFalse("支出为 0 时不该出现分类占比：$lines", lines.any { it.contains("%") })
    }

    @Test
    fun `候选列表的单行描述包含日期分类金额与备注`() {
        val line = AiSummary.entryLine(
            LedgerItem(
                id = "a",
                amountCents = 2_300,
                note = "滴滴打车",
                timeMillis = now,
                isExpense = true,
                categoryName = "交通",
                categoryIconRes = 0
            )
        )

        assertTrue(line.contains("交通"))
        assertTrue(line.contains("23.00"))
        assertTrue(line.contains("滴滴打车"))
        assertTrue("应带日期：$line", line.contains("05-15"))
    }
}
