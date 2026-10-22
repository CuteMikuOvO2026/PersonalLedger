package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [AiBookkeepingParser] 对 `action` 的解析与校验。
 *
 * 这里测的核心不是「能不能解析出 JSON」（Gson 保证），而是**不完整 / 不合法的动作会不会被
 * 拒绝**：一个「改了但没说要改成什么」的 update，如果被放行，界面会挂出一张点确认什么也不改的卡片；
 * 一个「没给条件」的 delete，如果被当成「全部删除」，后果更严重。
 */
class AiActionParserTest {

    private val now = at(2026, 5, 15, 10, 0)

    private val allowed = setOf(
        "餐饮", "交通", "购物", "娱乐", "医疗", "教育", "住房", "其他",
        "工资", "奖金", "投资", "兼职", "转账"
    )

    // ---------- 兼容：没有 action 字段的老格式 ----------

    @Test
    fun `没有 action 字段时按 entries 判定为新建`() {
        val reply = parse(
            """
            {"entries":[{"amount":15,"isExpense":true,"category":"餐饮",
            "note":"咖啡","dateTime":"2026-05-15 10:00"}],"reply":"已解析出 1 笔支出"}
            """
        )

        assertEquals(AiAction.TYPE_CREATE, reply.action?.type)
        assertEquals(1, reply.entries.size)
    }

    @Test
    fun `action 与 entries 都缺失时是闲聊`() {
        val reply = parse("""{"reply":"你好"}""")

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
        assertTrue(reply.entries.isEmpty())
    }

    // ---------- 四类新动作 ----------

    @Test
    fun `修改动作解析出目标选择器与改动`() {
        val reply = parse(
            """
            {"action":{"type":"update","target":{"keyword":"打车","dayOffset":-1},
            "changes":{"amount":30}},"entries":[],"reply":"要把昨天那笔打车改成 30 元"}
            """
        )

        val action = reply.action
        assertTrue("应为修改动作，实际 ${action?.type}", action is AiAction.Update)
        action as AiAction.Update
        assertEquals("打车", action.target.keyword)
        assertEquals(-1, action.target.dayOffset)
        assertEquals(30.0, action.changes.amountYuan!!, 0.0001)
        assertTrue(reply.entries.isEmpty())
    }

    @Test
    fun `删除动作解析出 latest`() {
        val reply = parse(
            """{"action":{"type":"delete","target":{"latest":true}},"entries":[],"reply":"要删除最后一笔"}"""
        )

        val action = reply.action as AiAction.Delete
        assertTrue(action.target.latest)
    }

    @Test
    fun `统计动作未给 range 时默认本月`() {
        val reply = parse(
            """{"action":{"type":"summarize"},"entries":[],"reply":"统计本月收支"}"""
        )

        val action = reply.action as AiAction.Summarize
        assertEquals(AiRangeType.THIS_MONTH, action.range.type)
    }

    @Test
    fun `统计动作能识别上个月与自定义区间`() {
        val lastMonth = parse(
            """{"action":{"type":"summarize","range":{"type":"last_month"}},"entries":[],"reply":"x"}"""
        ).action as AiAction.Summarize
        assertEquals(AiRangeType.LAST_MONTH, lastMonth.range.type)

        val custom = parse(
            """
            {"action":{"type":"summarize","range":{"type":"custom",
            "startDate":"2026-05-01","endDate":"2026-05-07"}},"entries":[],"reply":"x"}
            """
        ).action as AiAction.Summarize
        assertEquals(AiRangeType.CUSTOM, custom.range.type)
        assertEquals("2026-05-01", custom.range.startDate)
    }

    @Test
    fun `闲聊动作不产生任何卡片`() {
        val reply = parse("""{"action":{"type":"chat"},"entries":[],"reply":"你好，我可以帮你记账"}""")

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    // ---------- 不完整的动作必须被拒绝 ----------

    @Test
    fun `修改但没给 changes 时降级为闲聊`() {
        val reply = parse(
            """{"action":{"type":"update","target":{"keyword":"打车"}},"entries":[],"reply":"x"}"""
        )

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    @Test
    fun `修改的目标一个条件都没有时降级为闲聊`() {
        val reply = parse(
            """{"action":{"type":"update","target":{},"changes":{"amount":30}},"entries":[],"reply":"x"}"""
        )

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    @Test
    fun `删除没给 target 时绝不等于删除全部`() {
        // 这是最危险的一条：空 target 若被当成「无条件匹配」，用户一句含糊的话就会清空账本
        val reply = parse("""{"action":{"type":"delete"},"entries":[],"reply":"x"}""")

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    @Test
    fun `清单外的分类改动被丢弃而不是收敛成其他`() {
        // 新建时把未知分类收敛成「其他」是兜底，修改时收敛就是篡改用户已有的分类
        val reply = parse(
            """
            {"action":{"type":"update","target":{"latest":true},
            "changes":{"category":"不存在的分类"}},"entries":[],"reply":"x"}
            """
        )

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    @Test
    fun `非法金额的改动被丢弃`() {
        val reply = parse(
            """{"action":{"type":"update","target":{"latest":true},"changes":{"amount":0}},"entries":[],"reply":"x"}"""
        )

        assertEquals(AiAction.TYPE_CHAT, reply.action?.type)
    }

    @Test
    fun `同义的动作类型被归一化`() {
        assertEquals(
            AiAction.TYPE_DELETE,
            parse("""{"action":{"type":"remove","target":{"latest":true}},"entries":[],"reply":"x"}""")
                .action?.type
        )
        assertEquals(
            AiAction.TYPE_SUMMARIZE,
            parse("""{"action":{"type":"stats"},"entries":[],"reply":"x"}""").action?.type
        )
    }

    @Test
    fun `无法解析的 JSON 仍然返回 null`() {
        assertNull(AiBookkeepingParser.parse("不是 json", allowed, now))
    }

    private fun parse(json: String): AiReply =
        requireNotNull(AiBookkeepingParser.parse(json.trimIndent(), allowed, now)) {
            "解析失败：$json"
        }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
