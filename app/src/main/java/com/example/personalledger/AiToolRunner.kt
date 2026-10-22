package com.example.personalledger

/**
 * 执行动作所需的**数据访问**。
 *
 * 抽成接口而不是直接调 [LedgerRepository]，是为了让「一句话 → 做什么 → 卡片长什么样」这段
 * 分发逻辑能在 JVM 上用假数据测（真实现见 `AssistantViewModel` 里的匿名实现）。
 * 这与 [AiEvalRunner] 注入 `complete` 的理由一致：**执行策略算错的代价，比不做还大。**
 */
interface AiLedgerGateway {

    /** 按选择器粗筛候选记录（实现方应下推到 SQL，而不是整表读进内存）。 */
    suspend fun candidates(spec: AiTargetSpec, nowMillis: Long): List<LedgerItem>

    /** 区间的收支合计。 */
    suspend fun totals(range: PeriodRange): AiSummary.Totals

    /** 区间内支出最多的前 [limit] 个分类。 */
    suspend fun topExpenseCategories(range: PeriodRange, limit: Int): List<CategoryTotal>

    /** 区间内金额最大的一笔支出。 */
    suspend fun largestExpense(range: PeriodRange): LedgerItem?
}

/** 需要界面翻译成文案的「没做成」原因。域层不碰 `R`，只给出原因码与数量。 */
enum class AiNoticeKind {
    /** 条件没命中任何记录。 */
    NOT_FOUND,

    /** 命中多笔，但用户没说「全部」，无法确定是哪一笔。 */
    AMBIGUOUS,

    /** 命中太多（超过一次能展示/处理的上限）。 */
    TOO_MANY,

    /** 改动解析出来是空的（用户没说要改成什么）。 */
    NO_CHANGE,

    /** 这句话没有对应任何动作。 */
    NOTHING_TO_DO
}

data class AiNotice(val kind: AiNoticeKind, val count: Int = 0)

/**
 * 一次动作的执行结果：[text] 是要放进气泡的文字，[card] 是要挂上去的卡片。
 *
 * [text] 为空表示「文字部分由界面给」（统计结果的文字是固定的，模型说什么都不重要）。
 */
data class AiToolResult(
    val text: String,
    val card: ChatCard? = null,
    val notice: AiNotice? = null
)

/**
 * 「动作 → 结果」的唯一分发点。
 *
 * 三条不可退让的规则，改动前务必先读懂：
 *
 * 1. **写操作一律只生成卡片，不落库。** 新建、修改、删除都停在「待确认」，
 *    由用户在卡片上点确认才真正写进账本 —— 模型会编造内容，而账本里一笔错账用户很难事后发现
 *    （这是 [AiBookkeepingParser] 与通知自动记账共有的前提，不是这里新加的）。
 * 2. **找不到精确的一条时，绝不替用户猜。** 命中多笔就停下来问（[AiNoticeKind.AMBIGUOUS]），
 *    一笔都没命中就说没找到。只有「恰好一笔」或用户明说「全部」/「最后一笔」才继续。
 * 3. **统计的数字全部由数据库算。** 模型只负责选区间（一个枚举值），
 *    它既看不到账本，也不会参与加法 —— 详见 [AiSummary]。
 */
object AiToolRunner {

    suspend fun run(
        reply: AiReply,
        gateway: AiLedgerGateway,
        nowMillis: Long,
        allowedCategories: Set<String>
    ): AiToolResult {
        // 模型漏掉 action 字段时按历史行为兜底：有账目就是新建，没有就是闲聊。
        val action = reply.action
            ?: if (reply.entries.isNotEmpty()) AiAction.Create else AiAction.Chat

        return when (action) {
            AiAction.Chat -> AiToolResult(reply.reply)

            AiAction.Create ->
                if (reply.entries.isEmpty()) {
                    AiToolResult(reply.reply, notice = AiNotice(AiNoticeKind.NOTHING_TO_DO))
                } else {
                    AiToolResult(reply.reply, ChatCard.NewEntries(reply.entries))
                }

            is AiAction.Update -> update(action, reply, gateway, nowMillis, allowedCategories)

            is AiAction.Delete -> delete(action, reply, gateway, nowMillis)

            is AiAction.Summarize -> summarize(action, gateway, nowMillis)
        }
    }

    private suspend fun update(
        action: AiAction.Update,
        reply: AiReply,
        gateway: AiLedgerGateway,
        nowMillis: Long,
        allowedCategories: Set<String>
    ): AiToolResult {
        val candidates = gateway.candidates(action.target, nowMillis)
        return when (val match = AiTargetResolver.resolve(action.target, candidates, nowMillis)) {
            AiTargetMatch.None -> AiToolResult("", notice = AiNotice(AiNoticeKind.NOT_FOUND))

            is AiTargetMatch.Ambiguous -> AiToolResult(
                text = "",
                card = candidatesCard(match.candidates),
                notice = AiNotice(AiNoticeKind.AMBIGUOUS, match.total)
            )

            is AiTargetMatch.Found -> {
                // 一次只改一笔：批量改是另一个量级的风险（改错一列会波及整批），暂不做。
                if (match.total > 1) {
                    return AiToolResult(
                        text = "",
                        card = candidatesCard(match.entries),
                        notice = AiNotice(AiNoticeKind.TOO_MANY, match.total)
                    )
                }

                val before = match.entries.first()
                val after = action.changes.applyTo(before, allowedCategories, nowMillis)

                // 改动全部被校验丢掉了（非法金额、清单外分类、空备注……）——
                // 此时「确认」按钮什么也不会改，等于给用户一个假的操作入口。
                if (after.amountCents == before.amountCents &&
                    after.isExpense == before.isExpense &&
                    after.categoryName == before.categoryName &&
                    after.timeMillis == before.timeMillis &&
                    after.note == before.note
                ) {
                    return AiToolResult("", notice = AiNotice(AiNoticeKind.NO_CHANGE))
                }

                AiToolResult(reply.reply, ChatCard.Modify(before.toEntryRef(), after))
            }
        }
    }

    private suspend fun delete(
        action: AiAction.Delete,
        reply: AiReply,
        gateway: AiLedgerGateway,
        nowMillis: Long
    ): AiToolResult {
        val candidates = gateway.candidates(action.target, nowMillis)
        return when (val match = AiTargetResolver.resolve(action.target, candidates, nowMillis)) {
            AiTargetMatch.None -> AiToolResult("", notice = AiNotice(AiNoticeKind.NOT_FOUND))

            is AiTargetMatch.Ambiguous -> AiToolResult(
                text = "",
                card = candidatesCard(match.candidates),
                notice = AiNotice(AiNoticeKind.AMBIGUOUS, match.total)
            )

            is AiTargetMatch.Found -> {
                // 截断时必须拒绝，不能只删展示出来的那 20 笔：卡片上写 20 笔、实际该删 30 笔，
                // 用户点完确认只会得到一个「不完整」的结果，而他无从发现。
                if (match.total > match.entries.size) {
                    return AiToolResult(
                        text = "",
                        card = candidatesCard(match.entries),
                        notice = AiNotice(AiNoticeKind.TOO_MANY, match.total)
                    )
                }
                AiToolResult(reply.reply, ChatCard.Remove(match.entries.map { it.toEntryRef() }))
            }
        }
    }

    private suspend fun summarize(
        action: AiAction.Summarize,
        gateway: AiLedgerGateway,
        nowMillis: Long
    ): AiToolResult {
        val range = AiPeriods.resolve(action.range, nowMillis)
        val totals = gateway.totals(range)

        // 空区间不必再查分类与最大一笔：两次查询换回一堆 0，只是白花时间。
        val categories = if (totals.count == 0) emptyList() else
            gateway.topExpenseCategories(range, TOP_CATEGORIES)
        val largest = if (totals.count == 0) null else gateway.largestExpense(range)

        return AiToolResult(
            // 刻意丢掉模型那句 reply：它在生成时并不知道真实数字，留着只会和卡片打架
            // （「本月支出约 3000 元」压在「支出 ¥1,234.50」上面）。
            text = "",
            card = ChatCard.Answer(
                title = AiPeriods.describe(range),
                lines = AiSummary.lines(
                    totals = totals,
                    categories = categories,
                    largest = largest,
                    days = AiPeriods.daysOf(range, nowMillis)
                )
            )
        )
    }

    /** 「找到好几笔」时把候选摆出来：用户看一眼就知道该怎么说，而不是被反问一句「哪一笔？」。 */
    private fun candidatesCard(entries: List<LedgerItem>): ChatCard.Answer = ChatCard.Answer(
        title = "",
        lines = entries.map { AiSummary.entryLine(it) }
    )

    private const val TOP_CATEGORIES = 3
}
