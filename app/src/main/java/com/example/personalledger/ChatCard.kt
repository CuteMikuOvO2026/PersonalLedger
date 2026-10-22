package com.example.personalledger

/**
 * 账本条目的**快照**：确认卡片上展示的、以及确认后要精确命中的那一条。
 *
 * 为什么快照里必须带 [id]：用户看到卡片到点「确认」之间可能过了几秒，
 * 这期间账本可能被别处改动（另一条通知自动记账、或手动编辑）。带着 id 去改/删，
 * 改的就是「他刚才看到的这一条」；只带金额和时间去重新匹配，可能命中另一笔。
 *
 * [id] 对用户不可见，也**永远不会来自模型** —— 它由本地匹配产生（见 [AiTargetResolver]）。
 */
data class EntryRef(
    val id: String,
    val amountCents: Long,
    val isExpense: Boolean,
    val categoryName: String,
    val timeMillis: Long,
    val note: String
)

/** 账本条目 → 快照。 */
fun LedgerItem.toEntryRef(): EntryRef = EntryRef(
    id = id,
    amountCents = amountCents,
    isExpense = isExpense,
    categoryName = categoryName,
    timeMillis = timeMillis,
    note = note
)

/** 快照 → 账本条目。图标由 [CategoryColors] 按分类名解析，与列表、确认卡片同一条路径。 */
fun EntryRef.toLedgerItem(): LedgerItem = LedgerItem(
    id = id,
    amountCents = amountCents,
    note = note,
    timeMillis = timeMillis,
    isExpense = isExpense,
    categoryName = categoryName,
    categoryIconRes = CategoryColors.iconResFor(categoryName)
)

/**
 * 一条助手消息上挂的卡片。
 *
 * 三类「待确认」卡片 + 一类「只读」结果，构成了这个功能的交互骨架：
 *
 * - [NewEntries]：新建。确认后写入账本（与改造前完全一致）。
 * - [Modify]：修改。卡片上同时显示「原」与「改后」，确认后才落库。
 * - [Remove]：删除。卡片逐笔列出将删的记录，确认后才落库。
 * - [Answer]：统计结果。**只读**：数字已经由数据库算好、渲染进 [Answer.lines]，
 *   没有确认按钮。它不随账本变化重算 —— 卡片是那一刻的快照，事后改了账也不会「自己变」。
 *
 * [pending] 是「要不要显示确认按钮」的唯一判据，也是「这条消息还能不能被处理」的状态位：
 * 用户确认/忽略后，持久化层会把卡片清空，消息退化成一条普通文本（见 [ChatCardCodec]）。
 */
sealed interface ChatCard {

    val pending: Boolean

    data class NewEntries(val entries: List<ParsedEntry>) : ChatCard {
        override val pending: Boolean get() = true
    }

    data class Modify(val before: EntryRef, val after: ParsedEntry) : ChatCard {
        override val pending: Boolean get() = true
    }

    data class Remove(val entries: List<EntryRef>) : ChatCard {
        override val pending: Boolean get() = true
    }

    data class Answer(val title: String, val lines: List<String>) : ChatCard {
        override val pending: Boolean get() = false
    }
}
