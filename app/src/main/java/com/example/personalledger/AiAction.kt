package com.example.personalledger

import kotlin.math.roundToLong

/**
 * 助手能执行的动作 —— 纯 Kotlin，不依赖任何 Android 类型（连 `R` 都不碰），因此可以在 JVM 上直接测。
 *
 * 三条设计约束，改动前务必先读懂：
 *
 * 1. **模型不执行动作，只描述动作。** 模型输出「要做什么 + 按什么条件找」，真正的查询、匹配、
 *    写入全部由本地代码完成（见 [AiToolRunner]）。所以模型从头到尾拿不到也不需要主键，
 *    「删错一笔」「改到别的记录上」这类事故在结构上就不可能发生 —— 而不是靠提示词求它别犯错。
 * 2. **条件用「选择器」表达，不用主键。** 用户说「昨天打车那笔」时，模型只输出
 *    `{dayOffset:-1, keyword:"打车"}`，由 [AiTargetResolver] 在本地匹配。
 *    模型的输出里没有主键，也就没有「编一个不存在的主键」这种失败模式。
 * 3. **新建仍然走顶层的 `entries`。** 它与改造前逐字一致，因此那 38 条解析评估的数字仍然可比 ——
 *    这是刻意保留的兼容面，不是遗留代码。
 */
sealed interface AiAction {

    /** 与 JSON 里的 `action.type` 一致；评估集按它判分。 */
    val type: String

    /** 一行摘要，只用于日志与评估明细（不展示给用户）。 */
    val summary: String

    /** 记账（[AiReply.entries] 才是数据本体）。 */
    data object Create : AiAction {
        override val type: String get() = TYPE_CREATE
        override val summary: String get() = TYPE_CREATE
    }

    /** 打招呼、提问、闲聊 —— 什么都不做。 */
    data object Chat : AiAction {
        override val type: String get() = TYPE_CHAT
        override val summary: String get() = TYPE_CHAT
    }

    /** 修改一笔已有账目。 */
    data class Update(val target: AiTargetSpec, val changes: AiChanges) : AiAction {
        override val type: String get() = TYPE_UPDATE
        override val summary: String get() = "$TYPE_UPDATE ${target.summary} → ${changes.summary}"
    }

    /** 删除一笔或多笔已有账目。 */
    data class Delete(val target: AiTargetSpec) : AiAction {
        override val type: String get() = TYPE_DELETE
        override val summary: String get() = "$TYPE_DELETE ${target.summary}"
    }

    /** 统计某个时间区间的收支。 */
    data class Summarize(val range: AiRangeSpec) : AiAction {
        override val type: String get() = TYPE_SUMMARIZE
        override val summary: String get() = "$TYPE_SUMMARIZE ${range.type.wire}"
    }

    companion object {
        const val TYPE_CREATE = "create"
        const val TYPE_UPDATE = "update"
        const val TYPE_DELETE = "delete"
        const val TYPE_SUMMARIZE = "summarize"
        const val TYPE_CHAT = "chat"

        /** 模型可能给出的同义写法。归一化只做「词表映射」，不做语义猜测。 */
        private val ALIASES: Map<String, String> = mapOf(
            "create" to TYPE_CREATE, "add" to TYPE_CREATE, "new" to TYPE_CREATE, "record" to TYPE_CREATE,
            "update" to TYPE_UPDATE, "modify" to TYPE_UPDATE, "edit" to TYPE_UPDATE, "change" to TYPE_UPDATE,
            "delete" to TYPE_DELETE, "remove" to TYPE_DELETE, "cancel" to TYPE_DELETE,
            "summarize" to TYPE_SUMMARIZE, "summary" to TYPE_SUMMARIZE, "stats" to TYPE_SUMMARIZE,
            "statistics" to TYPE_SUMMARIZE, "report" to TYPE_SUMMARIZE, "query" to TYPE_SUMMARIZE,
            "chat" to TYPE_CHAT, "reply" to TYPE_CHAT, "none" to TYPE_CHAT, "talk" to TYPE_CHAT
        )

        /** 把模型给的字符串归一化成 [type]；认不出返回 `null`（由调用方决定兜底成什么）。 */
        fun normalizeType(raw: String?): String? {
            val text = raw?.trim()?.lowercase().orEmpty()
            if (text.isEmpty()) return null
            return ALIASES[text] ?: text.takeIf { it in ALIASES.values }
        }
    }
}

/**
 * 「要操作哪一笔」的选择器。
 *
 * 所有字段都可空 / 可缺省：模型只写它从用户话里真正听到的那些条件，缺的条件不参与过滤。
 * **条件越少匹配越宽**，所以 [AiTargetResolver] 在「匹配到多笔」时不会替你猜，而是把候选摆出来。
 */
data class AiTargetSpec(
    /** 「最后一笔」「刚才那笔」：取时间最新的一条。 */
    val latest: Boolean = false,
    /** 「所有」「全部」：匹配到的全部操作（删除时卡片会逐笔列出，仍需确认）。 */
    val all: Boolean = false,
    /** 绝对日期，`yyyy-MM-dd`。 */
    val date: String? = null,
    /** 相对今天的天数偏移：0=今天，-1=昨天，-2=前天。 */
    val dayOffset: Int? = null,
    /** 备注或分类里的关键词。 */
    val keyword: String? = null,
    /** 精确分类名。 */
    val category: String? = null,
    /** 收支方向：true=支出。 */
    val isExpense: Boolean? = null,
    /** 精确金额（元）。 */
    val amountYuan: Double? = null,
    /** 金额下限（元，含）。 */
    val minAmountYuan: Double? = null,
    /** 金额上限（元，含）。 */
    val maxAmountYuan: Double? = null
) {

    /** 是否至少给出了一个定位条件。一个都没有时**不允许**执行任何写操作。 */
    val hasCondition: Boolean
        get() = latest || all || !date.isNullOrBlank() || dayOffset != null ||
            !keyword.isNullOrBlank() || !category.isNullOrBlank() ||
            isExpense != null || amountYuan != null || minAmountYuan != null || maxAmountYuan != null

    /** 日志 / 评估明细用的紧凑描述。 */
    val summary: String
        get() = listOfNotNull(
            "latest".takeIf { latest },
            "all".takeIf { all },
            date?.let { "date=$it" },
            dayOffset?.let { "day=$it" },
            keyword?.takeIf { it.isNotBlank() }?.let { "kw=$it" },
            category?.takeIf { it.isNotBlank() }?.let { "cat=$it" },
            isExpense?.let { "expense=$it" },
            amountYuan?.let { "amount=$it" },
            minAmountYuan?.let { "min=$it" },
            maxAmountYuan?.let { "max=$it" }
        ).joinToString(",")
}

/**
 * 修改时**要改成什么**。
 *
 * 只包含用户明确要求改的字段：没提到的字段一律为 null，表示「保持原值」。
 * 这一点很关键 —— 模型如果「顺手补全」了没提到的字段（比如把备注清空），
 * 用户看到的是一笔被悄悄改坏的账，而卡片上未必看得出来。
 */
data class AiChanges(
    /** 新金额（元）。 */
    val amountYuan: Double? = null,
    /** 新的收支方向。 */
    val isExpense: Boolean? = null,
    /** 新的分类名。 */
    val category: String? = null,
    /** 新的备注。 */
    val note: String? = null,
    /** 新的时间，`yyyy-MM-dd HH:mm` 或 `yyyy-MM-dd`。 */
    val dateTime: String? = null
) {

    val hasChange: Boolean
        get() = amountYuan != null || isExpense != null ||
            !category.isNullOrBlank() || !note.isNullOrBlank() || !dateTime.isNullOrBlank()

    val summary: String
        get() = listOfNotNull(
            amountYuan?.let { "amount=$it" },
            isExpense?.let { "expense=$it" },
            category?.takeIf { it.isNotBlank() }?.let { "cat=$it" },
            note?.takeIf { it.isNotBlank() }?.let { "note=$it" },
            dateTime?.takeIf { it.isNotBlank() }?.let { "time=$it" }
        ).joinToString(",")

    /**
     * 把改动作用到一笔已有账目上，得到「改完之后」的样子。
     *
     * 与 [AiBookkeepingParser] 同一套取舍：**宁可这个字段不改，也不要猜一个值**。
     * 因此非法金额、清单外的分类名都只会被忽略，而不是被收敛成「其他」——
     * 新建时收敛成「其他」是兜底，修改时收敛成「其他」则是**破坏**用户已有的数据。
     */
    fun applyTo(entry: LedgerItem, allowedCategories: Set<String>, nowMillis: Long): ParsedEntry {
        val amountCents = amountYuan?.toCentsOrNull() ?: entry.amountCents
        val categoryName = category?.trim()
            ?.takeIf { it.isNotEmpty() && it in allowedCategories }
            ?: entry.categoryName
        val note = note?.takeIf { it.isNotBlank() }?.trim() ?: entry.note
        val timeMillis = dateTime?.takeIf { it.isNotBlank() }
            ?.let { AiBookkeepingParser.parseDateTime(it, nowMillis) }
            ?: entry.timeMillis

        return ParsedEntry(
            amountCents = amountCents,
            isExpense = isExpense ?: entry.isExpense,
            categoryName = categoryName,
            timeMillis = timeMillis,
            note = note
        )
    }
}

/** 统计用时间区间。只给类型，具体边界由 [AiPeriods] 在本地算，模型不碰毫秒。 */
data class AiRangeSpec(
    val type: AiRangeType = AiRangeType.THIS_MONTH,
    /** [AiRangeType.CUSTOM] 时的起止日期，`yyyy-MM-dd`。 */
    val startDate: String? = null,
    val endDate: String? = null
)

/** 区间类型。`wire` 是 JSON 里的取值，模型只允许从这几种里选一个。 */
enum class AiRangeType(val wire: String) {
    TODAY("today"),
    YESTERDAY("yesterday"),
    THIS_WEEK("this_week"),
    LAST_WEEK("last_week"),
    THIS_MONTH("this_month"),
    LAST_MONTH("last_month"),
    THIS_YEAR("this_year"),
    ALL("all"),
    CUSTOM("custom");

    companion object {
        /** 认不出时**不**报错，而是回落到「本月」——「总结一下」这类话不给区间时的合理默认。 */
        fun fromWire(raw: String?): AiRangeType {
            val text = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == text } ?: THIS_MONTH
        }
    }
}

/** 元 → 分。非法值（非有限、非正、超上限）返回 null，调用方据此保留原值或丢弃这笔。 */
fun Double.toCentsOrNull(): Long? {
    if (!isFinite() || this <= 0) return null
    val cents = (this * 100).roundToLong()
    if (cents < 1L || cents > AiBookkeepingParser.MAX_AMOUNT_CENTS) return null
    return cents
}
