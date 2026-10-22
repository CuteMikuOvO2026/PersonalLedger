package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * [AiChanges.applyTo]：把「要改成什么」作用到一笔已有账目上。
 *
 * 核心取舍只有一条：**非法值一律不改，绝不猜**。新建时模型给个不存在的分类会被收敛成「其他」，
 * 那是兜底；修改时若也这么做，就等于把用户一笔好好的「餐饮」改成「其他」，而卡片上只显示
 * 「改为 其他」—— 用户很可能不会细看就点了确认。
 */
class AiChangesTest {

    private val allowed = setOf("餐饮", "交通", "购物", "其他")

    private val now = Calendar.getInstance().apply {
        set(2026, 4, 15, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val coffee = LedgerItem(
        id = "x",
        amountCents = 1_500,
        note = "咖啡",
        timeMillis = now,
        isExpense = true,
        categoryName = "餐饮",
        categoryIconRes = 0
    )

    @Test
    fun `只改金额时其余字段保持原样`() {
        val after = AiChanges(amountYuan = 30.0).applyTo(coffee, allowed, now)

        assertEquals(3_000L, after.amountCents)
        assertEquals("餐饮", after.categoryName)
        assertEquals("咖啡", after.note)
        assertEquals(now, after.timeMillis)
        assertEquals(true, after.isExpense)
    }

    @Test
    fun `清单外的分类不会覆盖原分类`() {
        val after = AiChanges(category = "宠物").applyTo(coffee, allowed, now)

        assertEquals("餐饮", after.categoryName)
    }

    @Test
    fun `清单内的分类正常生效`() {
        val after = AiChanges(category = "交通").applyTo(coffee, allowed, now)

        assertEquals("交通", after.categoryName)
    }

    @Test
    fun `方向与备注可以改`() {
        val after = AiChanges(isExpense = false, note = "退款").applyTo(coffee, allowed, now)

        assertEquals(false, after.isExpense)
        assertEquals("退款", after.note)
    }

    @Test
    fun `非法金额保持原值而不是变成 0`() {
        listOf(0.0, -5.0, 1e12).forEach { bad ->
            val after = AiChanges(amountYuan = bad).applyTo(coffee, allowed, now)
            assertEquals("金额 $bad 应被忽略", 1_500L, after.amountCents)
        }
    }

    @Test
    fun `时间可以改到指定日期`() {
        val after = AiChanges(dateTime = "2026-05-14 09:00").applyTo(coffee, allowed, now)

        val expected = Calendar.getInstance().apply {
            set(2026, 4, 14, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expected, after.timeMillis)
    }

    @Test
    fun `什么都没给时结果与原记录一致`() {
        val after = AiChanges().applyTo(coffee, allowed, now)

        assertEquals(coffee.amountCents, after.amountCents)
        assertEquals(coffee.categoryName, after.categoryName)
        assertEquals(coffee.note, after.note)
        assertEquals(coffee.timeMillis, after.timeMillis)
        assertEquals(coffee.isExpense, after.isExpense)
    }
}
