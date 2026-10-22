package com.example.personalledger

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [AiToolRunner] 的分发规则。
 *
 * 数据访问用假实现注入（[AiLedgerGateway]），所以这些用例**不需要数据库、不需要网络**，
 * 却能守住整个功能里最危险的三条：
 * 1. 写操作只出卡片、绝不落库；
 * 2. 命中多笔 / 命中太多时停下来，不替用户猜；
 * 3. 统计的数字全部来自代码算出的值，模型说什么都不进卡片。
 */
class AiToolRunnerTest {

    private val now = Calendar.getInstance().apply {
        set(2026, 4, 15, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val allowed = setOf("餐饮", "交通", "购物", "其他")

    private val taxi = LedgerItem(
        id = "taxi",
        amountCents = 2_300,
        note = "滴滴打车",
        timeMillis = now,
        isExpense = true,
        categoryName = "交通",
        categoryIconRes = 0
    )

    private val coffee = LedgerItem(
        id = "coffee",
        amountCents = 1_500,
        note = "咖啡",
        timeMillis = now,
        isExpense = true,
        categoryName = "餐饮",
        categoryIconRes = 0
    )

    // ---------- 新建 / 闲聊 ----------

    @Test
    fun `新建只生成待确认卡片`() {
        val entry = ParsedEntry(1_500, true, "餐饮", now, "咖啡")
        val result = execute(AiReply(listOf(entry), "已解析出 1 笔支出", AiAction.Create))

        val card = result.card as ChatCard.NewEntries
        assertEquals(listOf(entry), card.entries)
        assertTrue(card.pending)
        assertNull(result.notice)
    }

    @Test
    fun `闲聊不产生卡片`() {
        val result = execute(AiReply(emptyList(), "你好", AiAction.Chat))

        assertNull(result.card)
        assertEquals("你好", result.text)
    }

    // ---------- 删除 ----------

    @Test
    fun `删除恰好命中一笔时生成删除卡片`() {
        val action = AiAction.Delete(AiTargetSpec(keyword = "打车"))
        val result = execute(AiReply(emptyList(), "要删除这笔打车", action), FakeGateway(listOf(taxi)))

        val card = result.card as ChatCard.Remove
        assertEquals(listOf("taxi"), card.entries.map { it.id })
        assertEquals("要删除这笔打车", result.text)
    }

    @Test
    fun `删除命中多笔时反问而不是随便删一笔`() {
        val action = AiAction.Delete(AiTargetSpec(keyword = "餐"))
        val result = execute(
            AiReply(emptyList(), "x", action),
            FakeGateway(listOf(coffee, coffee.copy(id = "coffee-2")))
        )

        assertEquals(AiNoticeKind.AMBIGUOUS, result.notice?.kind)
        assertEquals(2, result.notice?.count)
        assertTrue("歧义时不能给可点的删除卡片", result.card !is ChatCard.Remove)
        // 候选要摆出来，用户才知道该怎么说
        assertEquals(2, (result.card as ChatCard.Answer).lines.size)
    }

    @Test
    fun `删除匹配到超上限时拒绝执行`() {
        // 30 笔里只展示 20 笔：卡片上写 20、实际该删 30，用户点完只会得到一个不完整的结果
        val many = (1..30).map { coffee.copy(id = "c$it") }
        val action = AiAction.Delete(AiTargetSpec(keyword = "咖啡", all = true))
        val result = execute(AiReply(emptyList(), "x", action), FakeGateway(many))

        assertEquals(AiNoticeKind.TOO_MANY, result.notice?.kind)
        assertEquals(30, result.notice?.count)
        assertTrue(result.card !is ChatCard.Remove)
    }

    @Test
    fun `删除最后一笔在多笔记录里也能执行`() {
        // 真实用法的第一条：「删掉最后一笔」。账本里当然不止一笔 ——
        // 若把「还有别的记录」当成「命中太多」，这个指令就永远执行不了。
        val action = AiAction.Delete(AiTargetSpec(latest = true))
        val result = execute(
            AiReply(emptyList(), "要删除最后一笔", action),
            FakeGateway(listOf(taxi, coffee))
        )

        val card = result.card as ChatCard.Remove
        assertEquals(1, card.entries.size)
        // 时间最新的是 coffee（同一时刻构造，按 id 次序兜底）—— 关键是它必须真的给出一笔
        assertTrue(card.entries.single().id in setOf("taxi", "coffee"))
    }

    @Test
    fun `删除没找到时给出明确原因`() {
        val action = AiAction.Delete(AiTargetSpec(keyword = "健身房"))
        val result = execute(AiReply(emptyList(), "x", action), FakeGateway(listOf(taxi)))

        assertEquals(AiNoticeKind.NOT_FOUND, result.notice?.kind)
        assertNull(result.card)
    }

    // ---------- 修改 ----------

    @Test
    fun `修改生成原值到新值的卡片`() {
        val action = AiAction.Update(
            target = AiTargetSpec(keyword = "打车"),
            changes = AiChanges(amountYuan = 30.0)
        )
        val result = execute(AiReply(emptyList(), "要把这笔改成 30 元", action), FakeGateway(listOf(taxi)))

        val card = result.card as ChatCard.Modify
        assertEquals("taxi", card.before.id)
        assertEquals(2_300L, card.before.amountCents)
        assertEquals(3_000L, card.after.amountCents)
        assertEquals("交通", card.after.categoryName)
        assertEquals(now, card.after.timeMillis)
    }

    @Test
    fun `改动全部无效时不给假的操作入口`() {
        // 金额非法 + 分类不在清单里：applyTo 会把两项都丢掉，结果与原值完全一致。
        // 这时挂一张「确认修改」的卡片等于骗用户点一下什么都没发生。
        val action = AiAction.Update(
            target = AiTargetSpec(keyword = "打车"),
            changes = AiChanges(amountYuan = 0.0, category = "宠物")
        )
        val result = execute(AiReply(emptyList(), "x", action), FakeGateway(listOf(taxi)))

        assertEquals(AiNoticeKind.NO_CHANGE, result.notice?.kind)
        assertNull(result.card)
    }

    @Test
    fun `修改命中多笔时拒绝批量修改`() {
        val action = AiAction.Update(
            target = AiTargetSpec(keyword = "咖啡", all = true),
            changes = AiChanges(amountYuan = 20.0)
        )
        val result = execute(
            AiReply(emptyList(), "x", action),
            FakeGateway(listOf(coffee, coffee.copy(id = "coffee-2")))
        )

        assertEquals(AiNoticeKind.TOO_MANY, result.notice?.kind)
        assertTrue(result.card !is ChatCard.Modify)
    }

    // ---------- 统计 ----------

    @Test
    fun `统计卡片的数字来自网关而不是模型`() {
        val action = AiAction.Summarize(AiRangeSpec(AiRangeType.THIS_MONTH))
        val result = execute(
            AiReply(emptyList(), "本月支出约 3000 元", action),
            FakeGateway(
                totals = AiSummary.Totals(expenseCents = 123_456, incomeCents = 0, count = 7),
                categories = listOf(CategoryTotal("餐饮", 100_000)),
                largest = coffee
            )
        )

        val card = result.card as ChatCard.Answer
        // 模型那句「约 3000 元」必须被丢掉，否则它会和卡片上的真实数字打架
        assertEquals("", result.text)
        assertTrue("标题应是区间：${card.title}", card.title.startsWith("2026-05-01"))
        val joined = card.lines.joinToString("\n")
        assertTrue("缺少支出合计：$joined", joined.contains("1,234.56") || joined.contains("1234.56"))
        assertTrue("缺少笔数：$joined", joined.contains("共 7 笔"))
    }

    @Test
    fun `统计空区间时只提示没有记录`() {
        val action = AiAction.Summarize(AiRangeSpec(AiRangeType.LAST_MONTH))
        val result = execute(AiReply(emptyList(), "x", action))

        val card = result.card as ChatCard.Answer
        assertEquals(listOf(AiSummary.NO_RECORD), card.lines)
    }

    // ---------- 兜底 ----------

    @Test
    fun `模型漏掉 action 时按 entries 兜底成新建`() {
        val entry = ParsedEntry(2_300, true, "交通", now, "打车")
        val result = execute(AiReply(listOf(entry), "已解析出 1 笔支出", action = null))

        assertTrue(result.card is ChatCard.NewEntries)
    }

    // ---------- 测试脚手架 ----------

    private fun execute(
        reply: AiReply,
        gateway: AiLedgerGateway = FakeGateway()
    ): AiToolResult = runBlocking {
        AiToolRunner.run(reply, gateway, now, allowed)
    }

    private class FakeGateway(
        private val candidates: List<LedgerItem> = emptyList(),
        private val totals: AiSummary.Totals = AiSummary.Totals(0, 0, 0),
        private val categories: List<CategoryTotal> = emptyList(),
        private val largest: LedgerItem? = null
    ) : AiLedgerGateway {

        override suspend fun candidates(spec: AiTargetSpec, nowMillis: Long): List<LedgerItem> =
            candidates

        override suspend fun totals(range: PeriodRange): AiSummary.Totals = totals

        override suspend fun topExpenseCategories(
            range: PeriodRange,
            limit: Int
        ): List<CategoryTotal> = categories

        override suspend fun largestExpense(range: PeriodRange): LedgerItem? = largest
    }
}
