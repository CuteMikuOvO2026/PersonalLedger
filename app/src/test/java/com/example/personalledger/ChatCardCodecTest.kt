package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatCardCodec] 的往返与容错。
 *
 * 它守着两件事：
 * 1. **升级不丢卡片** —— 老版本存的是裸数组，升级后用户手上那张没确认的记账必须在；
 * 2. **坏数据不崩页面** —— 一段残缺的 JSON 只该让卡片消失，不该让助手页打不开。
 */
class ChatCardCodecTest {

    private val entry = ParsedEntry(
        amountCents = 1_500,
        isExpense = true,
        categoryName = "餐饮",
        timeMillis = 1_700_000_000_000L,
        note = "咖啡"
    )

    private val ref = EntryRef(
        id = "id-1",
        amountCents = 2_300,
        isExpense = true,
        categoryName = "交通",
        timeMillis = 1_700_000_100_000L,
        note = "打车"
    )

    @Test
    fun `新建卡片往返`() {
        val decoded = ChatCardCodec.decode(
            ChatCardCodec.encode(ChatCard.NewEntries(listOf(entry)))
        )

        assertEquals(ChatCard.NewEntries(listOf(entry)), decoded)
    }

    @Test
    fun `修改卡片往返并保留主键`() {
        val decoded = ChatCardCodec.decode(
            ChatCardCodec.encode(ChatCard.Modify(before = ref, after = entry))
        )

        val modify = decoded as ChatCard.Modify
        assertEquals(ref, modify.before)
        assertEquals(entry, modify.after)
        // 主键必须回来：确认时要按它精确命中那一笔，丢了就只能重新匹配（可能匹配到别的记录）
        assertEquals("id-1", modify.before.id)
    }

    @Test
    fun `删除卡片往返`() {
        val decoded = ChatCardCodec.decode(
            ChatCardCodec.encode(ChatCard.Remove(listOf(ref, ref.copy(id = "id-2"))))
        )

        assertEquals(2, (decoded as ChatCard.Remove).entries.size)
        assertEquals(listOf("id-1", "id-2"), decoded.entries.map { it.id })
    }

    @Test
    fun `统计卡片往返且是只读的`() {
        val card = ChatCard.Answer("2026-05-01 ~ 2026-05-31", listOf("支出 ¥1,234.50"))
        val decoded = ChatCardCodec.decode(ChatCardCodec.encode(card)) as ChatCard.Answer

        assertEquals(card, decoded)
        assertEquals(false, decoded.pending)
    }

    @Test
    fun `老版本的裸数组仍能解码成新建卡片`() {
        // 改造前 draftJson 存的就是这个形状；升级后不能把用户没确认的记账弄丢
        val legacy = """
            [{"amountCents":1500,"isExpense":true,"categoryName":"餐饮",
            "timeMillis":1700000000000,"note":"咖啡"}]
        """.trimIndent()

        val decoded = ChatCardCodec.decode(legacy) as ChatCard.NewEntries
        assertEquals(listOf(entry), decoded.entries)
    }

    @Test
    fun `残缺的删除卡片整条丢弃`() {
        // 没有 id 就不知道要删哪一笔 —— 这种卡片绝对不能渲染出「确认删除」按钮
        val broken = """{"kind":"remove","entries":[{"amountCents":100,"timeMillis":1}]}"""

        assertNull(ChatCardCodec.decode(broken))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(ChatCardCodec.decode("{不是 json"))
        assertNull(ChatCardCodec.decode("[]"))
        assertNull(ChatCardCodec.decode(""))
        assertNull(ChatCardCodec.decode(null))
    }

    @Test
    fun `空卡片编码为 null`() {
        assertNull(ChatCardCodec.encode(null))
        assertNull(ChatCardCodec.encode(ChatCard.NewEntries(emptyList())))
        assertNull(ChatCardCodec.encode(ChatCard.Remove(emptyList())))
    }

    @Test
    fun `待确认与只读的判定只由卡片类型决定`() {
        assertTrue(ChatCard.NewEntries(listOf(entry)).pending)
        assertTrue(ChatCard.Modify(ref, entry).pending)
        assertTrue(ChatCard.Remove(listOf(ref)).pending)
        assertTrue(!ChatCard.Answer("t", listOf("x")).pending)
    }
}
