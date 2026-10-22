package com.example.personalledger

import kotlin.math.roundToInt

/**
 * 一次「找哪一笔」的结果。
 *
 * 刻意把「找到多笔」与「找到一笔」分成两个分支：调用方（[AiToolRunner]）对前者的处理是
 * **停下来问用户**，而不是取第一笔。账本里改错/删错的代价远高于多问一句。
 */
sealed interface AiTargetMatch {

    /**
     * 命中的记录（1..N 笔）。`all=true` 时可能就是多笔，卡片会逐笔列出。
     *
     * [total] 是**真实命中数**，可能大于 [entries] 的条数（[MAX_CANDIDATES] 会截断）。
     * 这个区分很重要：截断后静默删除前 20 笔，用户看到的和实际发生的就不是一回事了。
     */
    data class Found(val entries: List<LedgerItem>, val total: Int = entries.size) : AiTargetMatch

    /** 命中多笔但用户没说「全部」，无法确定是哪一笔。 */
    data class Ambiguous(val candidates: List<LedgerItem>, val total: Int = candidates.size) : AiTargetMatch

    /** 一笔都没命中。 */
    data object None : AiTargetMatch
}

/**
 * 目标选择器的本地匹配 —— **纯函数**，输入是候选记录与「现在」，输出是匹配结果。
 *
 * 为什么匹配必须放在本地：
 * - 模型看不到账本，让它「猜主键」就是在制造幻觉；
 * - 匹配规则（关键词如何算命中、金额是否精确、日期怎么对齐）必须可单测，否则「删错了」无法复盘；
 * - 匹配结果要给用户确认，所以它必须是一份**真实存在**的记录快照，而不是模型转述。
 *
 * 匹配只做「收敛」不做「发散」：任何一个条件不满足就排除。条件写得越宽，命中越多，
 * 最终会走到 [AiTargetMatch.Ambiguous] 让用户补条件 —— 这是刻意选择的失败方向。
 */
object AiTargetResolver {

    /** 候选上限。超过这个数量，「找哪一笔」本身就已经不成立了，摆出来只会让人更难选。 */
    const val MAX_CANDIDATES = 20

    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * @param candidates 由调用方按选择器从数据库粗筛出来的候选（已按时间倒序更好，这里会再排一次）。
     * @param nowMillis 当前时间，「昨天」「今天」以它为锚点。
     */
    fun resolve(
        spec: AiTargetSpec,
        candidates: List<LedgerItem>,
        nowMillis: Long
    ): AiTargetMatch {
        if (!spec.hasCondition) return AiTargetMatch.None

        val hits = candidates
            .filter { matches(spec, it, nowMillis) }
            .sortedWith(compareByDescending<LedgerItem> { it.timeMillis }.thenBy { it.id })

        if (hits.isEmpty()) return AiTargetMatch.None
        // latest 是「用户已经指定了要哪一笔」，因此总数记为 1。
        // 不能记成 hits.size —— 那样「删掉最后一笔」在账本里还有别的记录时会被上层判成
        // 「命中 30 笔、只展示 1 笔、可能截断」，于是一个完全明确的指令反而被拒绝执行。
        if (spec.latest) return AiTargetMatch.Found(hits.take(1), 1)
        if (spec.all) return AiTargetMatch.Found(hits.take(MAX_CANDIDATES), hits.size)
        if (hits.size == 1) return AiTargetMatch.Found(hits, hits.size)
        return AiTargetMatch.Ambiguous(hits.take(MAX_CANDIDATES), hits.size)
    }

    /** 单条记录是否满足全部条件。任一条件不满足即排除。 */
    fun matches(spec: AiTargetSpec, item: LedgerItem, nowMillis: Long): Boolean {
        spec.keyword?.takeIf { it.isNotBlank() }?.let { keyword ->
            val k = keyword.trim()
            // 只做「正向包含」：备注或分类名里含这个词才算命中。
            // 不做反向包含（备注是关键词的子串）——那会把「饭」匹配到「吃饭喝酒」这类无关记录上，
            // 而漏匹配的代价只是让用户补一句，误匹配的代价是改错账。
            if (!item.note.contains(k, ignoreCase = true) &&
                !item.categoryName.contains(k, ignoreCase = true)
            ) {
                return false
            }
        }

        spec.category?.takeIf { it.isNotBlank() }?.let { expected ->
            if (!item.categoryName.equals(expected.trim(), ignoreCase = true)) return false
        }

        spec.isExpense?.let { if (item.isExpense != it) return false }

        spec.amountYuan?.let { yuan ->
            val cents = yuan.toCentsOrNull()
            // 金额条件本身不合法（0、负数、超上限）时**忽略**这条条件，而不是判定为「全不匹配」：
            // 前者会退化成条件更宽的查询（最终落到 Ambiguous 让用户补），后者会给出「没找到」的
            // 误导性答案 —— 用户明明有这笔钱。
            if (cents != null && item.amountCents != cents) return false
        }

        spec.minAmountYuan?.let { yuan ->
            yuan.toCentsOrNull()?.let { if (item.amountCents < it) return false }
        }

        spec.maxAmountYuan?.let { yuan ->
            yuan.toCentsOrNull()?.let { if (item.amountCents > it) return false }
        }

        spec.date?.takeIf { it.isNotBlank() }?.let { text ->
            val dayStart = AiBookkeepingParser.parseDateTime(text, nowMillis)
            val itemDay = LedgerStats.startOfDay(item.timeMillis)
            val targetDay = LedgerStats.startOfDay(dayStart)
            if (itemDay != targetDay) return false
        }

        spec.dayOffset?.let { offset ->
            if (dayOffsetOf(item.timeMillis, nowMillis) != offset) return false
        }

        return true
    }

    /**
     * 记录时间相对「今天」的天数差：今天 = 0，昨天 = -1。
     *
     * 与 [AiEvalRunner] 里同名逻辑同一个写法：先对齐到自然日零点再相减，
     * 否则「昨天 23:00」与「今天 01:00」相差两小时，会被算成同一天。
     */
    private fun dayOffsetOf(millis: Long, nowMillis: Long): Int {
        val days = (LedgerStats.startOfDay(millis) - LedgerStats.startOfDay(nowMillis)).toDouble() /
            MILLIS_PER_DAY
        // 四舍五入而不是整除：夏令时那天只有 23 小时，整除会差一天
        return days.roundToInt()
    }
}
