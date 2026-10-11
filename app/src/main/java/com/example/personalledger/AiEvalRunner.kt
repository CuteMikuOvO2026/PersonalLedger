package com.example.personalledger

import kotlin.math.roundToInt

/** 命中数 / 总数。`total == 0` 表示这条用例不校验该字段（显示为「—」）。 */
data class EvalScore(val hits: Int, val total: Int) {
    val text: String get() = if (total == 0) "—" else "$hits/$total"

    /** 不校验的字段按满分计，这样它不会拖低「整句全对」的判定。 */
    val ratio: Double get() = if (total == 0) 1.0 else hits.toDouble() / total
}

/** 单条用例的评估结果。 */
data class AiEvalResult(
    val case: AiEvalCase,
    /** 实际解析出的条数；请求失败时为 -1。 */
    val actualCount: Int,
    val fullyCorrect: Boolean,
    val amount: EvalScore,
    val category: EvalScore,
    val direction: EvalScore,
    val date: EvalScore,
    val note: EvalScore,
    val usage: AiUsage?,
    /** 请求本身失败的分类名（网络 / 鉴权 / ...）；非 null 时各项得分为空。 */
    val failure: String?,
    /** 实际解析结果的可读摘要，供排查用。 */
    val actualSummary: String
)

/** 汇总指标。 */
data class AiEvalReport(val results: List<AiEvalResult>) {

    val total: Int get() = results.size

    /** 整句全对（条数与每个被校验的字段都对）。 */
    val fullyCorrect: Int get() = results.count { it.fullyCorrect }

    val amount: EvalScore get() = merge { it.amount }
    val category: EvalScore get() = merge { it.category }
    val direction: EvalScore get() = merge { it.direction }
    val date: EvalScore get() = merge { it.date }
    val note: EvalScore get() = merge { it.note }

    /** 请求失败（没拿到模型输出）的条数，与「答错」要分开看。 */
    val failed: Int get() = results.count { it.failure != null }

    /**
     * 期望产生账目的**用例数**（当前 20）。
     *
     * 注意它**不是**逐字段分数的分母 —— 分母是 [expectedEntryCount]（期望的账目笔数，当前 22）。
     * 两者不等，是因为有的用例一句话含多笔（「早餐 8 块，咖啡 15」期望 2 笔）。
     * 把这两个数字混为一谈，报告就会自相矛盾（屏幕上曾出现过「期望记账 20」却「金额 22/22」）。
     */
    val entryCaseCount: Int get() = results.count { it.case.expect.isNotEmpty() }

    /**
     * 全部用例期望的账目**总笔数** —— **金额 / 分类 / 方向 的分母就是它**。
     *
     * 「你好」这类期望不记账的用例不贡献任何笔数，因此天然不参与逐字段统计，只计入 [fullyCorrect]。
     */
    val expectedEntryCount: Int get() = results.sumOf { it.case.expect.size }

    /** 期望不产生账目的用例数（打招呼、问统计），用于验证「不编造账目」。 */
    val noEntryCaseCount: Int get() = total - entryCaseCount

    val usages: List<AiUsage> get() = results.mapNotNull { it.usage }

    val avgLatencyMillis: Int
        get() = usages.map { it.elapsedMillis }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0

    val avgTotalTokens: Int
        get() = usages.map { it.totalTokens }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0

    val avgPromptTokens: Int
        get() = usages.map { it.promptTokens }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0

    /** 平均缓存命中率：输入 token 里有多少是复用的系统提示词（按 1/50 计价）。 */
    val cacheHitRate: Double
        get() {
            val prompt = usages.sumOf { it.promptTokens }
            if (prompt == 0) return 0.0
            return usages.sumOf { it.cachedTokens }.toDouble() / prompt
        }

    private fun merge(select: (AiEvalResult) -> EvalScore): EvalScore =
        EvalScore(results.sumOf { select(it).hits }, results.sumOf { select(it).total })

    /**
     * 供弹窗直接展示的汇总文本。
     *
     * 分母口径必须写清楚，否则数字看着自相矛盾：屏幕上一度出现「期望记账 20」却又「金额 22/22」，
     * 原因就是前者是**用例数**、后者是**账目笔数**（有两条用例各含 2 笔）。两者都保留，
     * 但各自标注清楚。
     */
    fun summaryLines(): List<String> = listOf(
        "整句全对：$fullyCorrect / $total 条（${percent(fullyCorrect, total)}）",
        "用例：$total 条（记账 $entryCaseCount 条 / 不记账 $noEntryCaseCount 条），共期望 $expectedEntryCount 笔账目",
        "以下分母为「期望账目笔数 $expectedEntryCount」：",
        "  金额 ${amount.text}    分类 ${category.text}    方向 ${direction.text}",
        "以下分母为「指定了该字段的笔数」：",
        "  日期 ${date.text}    备注 ${note.text}",
        if (failed > 0) "请求失败：$failed 条（未计入正确率）" else "请求失败：0 条",
        "平均延迟：${avgLatencyMillis} ms",
        "平均用量：$avgTotalTokens tokens（输入 $avgPromptTokens，缓存命中率 ${(cacheHitRate * 100).roundToInt()}%）"
    )

    private fun percent(hits: Int, total: Int): String =
        if (total == 0) "—" else "${(hits * 100.0 / total).roundToInt()}%"
}

/**
 * 评估执行器：拿[评估集][AiEvalSet]去跑**生产同一条管线**。
 *
 * 关键在于「同一条」：同一个 [AiPrompt.systemPrompt]、同一个 [DeepSeekClient]、同一个
 * [AiBookkeepingParser]。如果评估时另写一套简化逻辑，测出来的就不是线上那个东西了 ——
 * 这是评估最常见的自欺方式。
 *
 * 用例之间**互不携带上下文**（每次只发一条 user 消息、不带历史）：评估要测的是「这句话解析得
 * 对不对」，掺进历史会让失败无法归因。
 *
 * 本类不依赖任何 Android 类型（category 名单由调用方传入），因此将来若要挪到 JVM 上跑，
 * 只需换掉触发方式，逻辑本身可以直接搬。
 */
class AiEvalRunner(
    private val apiKey: String,
    private val model: String,
    private val expenseCategories: List<String>,
    private val incomeCategories: List<String>,
    /**
     * 模型调用入口。
     *
     * 抽成可注入的参数，是为了**让评分逻辑可以脱离网络验证**：评分算错的话，得到的准确率就是错的，
     * 而错误的指标比没有指标更糟 —— 它会让人据此做出错误判断。注入之后可以用构造好的响应直接
     * 检查评分（见 README 里的验证说明）。
     *
     * 默认值就是生产实现，因此正常路径不受影响。
     */
    private val complete: suspend (systemPrompt: String, userInput: String) -> AiCompletionResult =
        { systemPrompt, userInput ->
            DeepSeekClient.complete(
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                turns = listOf(AiChatTurn("user", userInput))
            )
        }
) {

    /**
     * 依次跑完所有用例。
     *
     * 方法名刻意不叫 `run`：那会和 Kotlin 标准库的 `run` 撞名，`runner.run { ... }` 读起来
     * 分不清是作用域函数还是跑评估。
     */
    suspend fun evaluate(
        cases: List<AiEvalCase> = AiEvalSet.cases,
        /** 回调 (已完成条数, 总条数)，用于驱动进度显示。 */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): AiEvalReport {
        // 整轮共用同一个「现在」：否则跨零点跑的时候，前几条和后几条的「今天」不是同一天
        val nowMillis = System.currentTimeMillis()
        val systemPrompt = AiPrompt.systemPrompt(nowMillis, expenseCategories, incomeCategories)
        val allowedCategories = (expenseCategories + incomeCategories).toSet()

        val results = ArrayList<AiEvalResult>(cases.size)
        onProgress(0, cases.size)
        cases.forEachIndexed { index, case ->
            val outcome = complete(systemPrompt, case.input)
            results += when (outcome) {
                is AiCompletionResult.Success -> {
                    val reply = AiBookkeepingParser.parse(outcome.content, allowedCategories, nowMillis)
                    if (reply == null) {
                        emptyResult(case, outcome.usage, "PARSE_ERROR", "无法解析模型输出")
                    } else {
                        grade(case, reply, outcome.usage, nowMillis)
                    }
                }

                is AiCompletionResult.Failure -> emptyResult(case, null, outcome.error.name, "请求失败")
            }
            onProgress(index + 1, cases.size)
        }

        return AiEvalReport(results)
    }

    /**
     * 给一条用例打分。
     *
     * 匹配方式：**两边都按金额降序排序后逐位对比**。不按「先找到金额相同的那笔再打分」，因为那样
     * 金额就自己给自己判卷了（永远 100%）。排序对齐既避免了这个循环论证，又能在多笔用例里
     * 容忍顺序不同 —— 模型先记哪一笔不该扣分。
     */
    private fun grade(
        case: AiEvalCase,
        reply: AiReply,
        usage: AiUsage?,
        nowMillis: Long
    ): AiEvalResult {
        val expected = case.expect.sortedByDescending { it.amountCents }
        val actual = reply.entries.sortedByDescending { it.amountCents }

        var amountHits = 0
        var categoryHits = 0
        var directionHits = 0
        var dateHits = 0
        var dateTotal = 0
        var noteHits = 0
        var noteTotal = 0

        expected.forEachIndexed { index, exp ->
            // 实际条数不足时 act 为 null，这条期望在**所有**字段上都算未命中
            val act = actual.getOrNull(index)

            if (act != null) {
                if (act.amountCents == exp.amountCents) amountHits++
                if (act.categoryName == exp.category) categoryHits++
                if (act.isExpense == exp.isExpense) directionHits++
            }
            exp.dayOffset?.let { offset ->
                dateTotal++
                if (act != null && dayOffsetOf(act.timeMillis, nowMillis) == offset) dateHits++
            }
            exp.noteContains?.let { keyword ->
                noteTotal++
                if (act != null && act.note.contains(keyword)) noteHits++
            }
        }

        val amount = EvalScore(amountHits, expected.size)
        val category = EvalScore(categoryHits, expected.size)
        val direction = EvalScore(directionHits, expected.size)
        val date = EvalScore(dateHits, dateTotal)
        val note = EvalScore(noteHits, noteTotal)

        return AiEvalResult(
            case = case,
            actualCount = actual.size,
            // 条数不对就不是全对：漏记和多记都会让这个功能不可用
            fullyCorrect = actual.size == expected.size &&
                listOf(amount, category, direction, date, note).all { it.ratio == 1.0 },
            amount = amount,
            category = category,
            direction = direction,
            date = date,
            note = note,
            usage = usage,
            failure = null,
            actualSummary = describe(actual, nowMillis)
        )
    }

    private fun emptyResult(
        case: AiEvalCase,
        usage: AiUsage?,
        failure: String,
        summary: String
    ) = AiEvalResult(
        case = case,
        actualCount = -1,
        fullyCorrect = false,
        amount = EvalScore(0, 0),
        category = EvalScore(0, 0),
        direction = EvalScore(0, 0),
        date = EvalScore(0, 0),
        note = EvalScore(0, 0),
        usage = usage,
        failure = failure,
        actualSummary = summary
    )

    /**
     * 实际日期相对「今天」的天数差，符号与 [AiEvalExpectation.dayOffset] 一致：
     * **今天 = 0，昨天 = -1，前天 = -2**（时间是向过去递减的）。
     *
     * 注意是 `实际 - 今天`，不是反过来。写反的话所有相对日期的用例都会判错 —— 而「今天」用例
     * 恰好是 0，符号不生效，所以这个 bug 只会在昨天/前天上暴露，很容易漏掉。
     * 用 [LedgerStats.startOfDay] 对齐自然日边界，避免「昨天 23:00 与今天 01:00 相差 2 小时」
     * 被算成同一天。
     */
    private fun dayOffsetOf(millis: Long, nowMillis: Long): Int {
        val days = (LedgerStats.startOfDay(millis) - LedgerStats.startOfDay(nowMillis)).toDouble() / MILLIS_PER_DAY
        // 用四舍五入而不是整除：夏令时的某天只有 23 小时，整除会差一天
        return days.roundToInt()
    }

    private fun describe(entries: List<ParsedEntry>, nowMillis: Long): String {
        if (entries.isEmpty()) return "（无账目）"
        return entries.joinToString(" | ") { entry ->
            val sign = if (entry.isExpense) "-" else "+"
            val amount = String.format(java.util.Locale.getDefault(), "%.2f", entry.amountCents / 100.0)
            "${entry.categoryName} $sign$amount（${entry.note}）"
        }
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}
