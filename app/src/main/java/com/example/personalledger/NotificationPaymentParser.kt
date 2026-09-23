package com.example.personalledger

import kotlin.math.roundToLong

/**
 * 微信 / 支付宝支付通知的解析逻辑。
 *
 * 从 [LedgerNotificationListenerService] 中抽出来的**纯函数**，原因有二：
 *
 * 1. 这段逻辑此前**零测试覆盖**，而它直接决定「自动记账记成什么」——
 *    金额算错、方向判反都会静默写进账本，用户很难察觉；
 * 2. 抽成无 Android 依赖的对象后，就能用真实通知文本做 JVM 回归测试。
 *
 * 解析结果刻意只给分类**名称**而不给图标资源：图标由 [CategoryColors.iconResFor]
 * 按名称解析，与列表渲染走同一条路径，纯逻辑因此不必依赖 Android 的 `R`。
 */
object NotificationPaymentParser {

    /** 解析结果。[amountCents] 单位为「分」。 */
    data class Parsed(
        val amountCents: Long,
        val isExpense: Boolean,
        val categoryName: String,
        val note: String
    )

    /** 未命中任何规则时的兜底分类。 */
    private const val DEFAULT_CATEGORY = "其他"

    /** 备注截断长度（通知正文可能很长，且常带无关尾巴）。 */
    private const val NOTE_MAX_LENGTH = 60

    /**
     * 金额正则，按顺序尝试。
     *
     * 先匹配带货币符号的（更明确），再退到「数字 + 元」。
     */
    private val AMOUNT_PATTERNS = listOf(
        Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
        Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
    )

    /**
     * 通知里的「来源标签」——应用名 / 服务号名。
     *
     * 它们**不含交易语义**，却会污染方向投票：应用名「微信支付」「支付宝」都含有
     * 「支付」，等于给每条通知白送一张支出票。后果是只带一个收入信号的通知
     * （如「收到转账」）会被平票兜底判成支出。因此投票前先把这些标签抹掉。
     *
     * 注意顺序：长的标签必须排在短的前面，否则「微信」会先把「微信收款助手」切碎。
     */
    private val SOURCE_LABELS = listOf(
        "微信收款助手", "微信支付", "支付宝", "云闪付", "微信"
    )

    /**
     * 方向关键词。
     *
     * 这里**刻意保留**了同一语义组内的重叠词（如「支付」「已支付」「支付成功」）：
     * 它们都属于同一方向，重复命中只会放大该方向的票数，不会把方向带偏。
     *
     * [INCOME_WORDS] 需要覆盖 [AutoBookkeepingRules.incomeRules] 的关键词，
     * 否则会出现「被识别为收入分类、却被判成支出方向」的自相矛盾（如「工资代发」）。
     * 唯一例外见 [AMBIGUOUS_INVESTMENT_KEYWORDS]，该不变式由
     * `NotificationPaymentParserTest.incomeWordListCoversIncomeRules` 守护。
     *
     * 注意区分两类词：**方向词**（钱往哪走：扣款 / 到账）和**标的词**（买了什么：股票 / 基金）。
     * 只有前者能进这里——见 [AMBIGUOUS_INVESTMENT_KEYWORDS]。
     */
    private val EXPENSE_WORDS = listOf(
        "支付", "付款", "支出", "消费", "已付款", "付款成功",
        "成功付款", "已支付", "支付成功", "转账支出", "扣款",
        // 投资买入方向：与下方的「赎回 / 卖出 / 分红」成对，避免标的词左右方向
        "买入", "申购", "定投"
    )

    private val INCOME_WORDS = listOf(
        // 收款类
        "收款", "到账", "入账", "收到", "已收款", "收入", "进账", "红包", "转入",
        // 退款类：钱回来算收入
        "退款", "退回",
        // 薪资 / 奖励类：与 incomeRules 的「工资」「奖金」对齐（「薪」覆盖「薪水」这类说法）
        "工资", "薪资", "薪", "代发", "奖金", "奖励",
        // 投资类：只收方向明确的词，标的词见 AMBIGUOUS_INVESTMENT_KEYWORDS
        "收益", "利息", "赎回", "卖出", "分红",
        // 副业类：与 incomeRules 的「兼职」对齐
        "稿费", "兼职", "劳务", "外包"
    )

    /**
     * 刻意**不进** [INCOME_WORDS] 的 `incomeRules` 关键词。
     *
     * 它们描述的是**投资标的**而不是**资金流向**。用它们做分类是安全的（分类只在方向已判定为
     * 收入之后才跑），但拿来投票会把方向带反——「股票买入」「基金申购」同样含这两个字，
     * 却是不折不扣的支出；而把「买入」误判成收入的代价远大于漏记一笔。
     *
     * 这类通知的方向改由**方向词**兜住，见 [EXPENSE_WORDS] / [INCOME_WORDS] 里的
     * 买入 / 申购 / 定投 / 赎回 / 卖出 / 分红。该取舍由 `NotificationPaymentParserTest`
     * 的 `incomeWordListCoversIncomeRules` 与 `ambiguousInvestmentKeywordsDoNotVoteIncome`
     * 两个用例同时锁住。
     */
    val AMBIGUOUS_INVESTMENT_KEYWORDS = listOf("理财", "基金", "股票")

    /** 抹掉来源标签，只保留可能承载交易语义的正文。 */
    private fun stripSourceLabels(text: String): String =
        SOURCE_LABELS.fold(text) { acc, label -> acc.replace(label, " ") }

    /** 收款方名称：优先取方括号 / 书名号内的内容。 */
    private val BRACKET_NAME = Regex("""[\[\u3010]([^\]\u3011]{1,24})[\]\u3011]""")

    private val WHITESPACE = Regex("""\s+""")

    /**
     * 解析一条通知正文。
     *
     * @return 解析不出金额时返回 `null`（调用方据此跳过，不入账）。
     */
    fun parse(text: String): Parsed? {
        val amountCents = extractAmountCents(text) ?: return null
        val isExpense = detectDirection(text)
        val rule = if (isExpense) {
            AutoBookkeepingRules.matchExpense(text)
        } else {
            AutoBookkeepingRules.matchIncome(text)
        }
        return Parsed(
            amountCents = amountCents,
            isExpense = isExpense,
            categoryName = rule?.name ?: DEFAULT_CATEGORY,
            note = buildNote(text)
        )
    }

    /** 提取金额并换算成「分」；容忍千分位逗号。 */
    fun extractAmountCents(text: String): Long? {
        val cleaned = text.replace(",", "")
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(cleaned) ?: continue
            val amount = match.groupValues[1].toDoubleOrNull() ?: continue
            if (amount > 0) return (amount * 100).roundToLong()
        }
        return null
    }

    /**
     * 判断收支方向。
     *
     * 投票前会先抹掉来源标签（见 [SOURCE_LABELS]），因为应用名里的「支付」不代表
     * 这笔交易是支出。
     *
     * @return `true` 表示支出，`false` 表示收入；两个方向票数相同时默认按支出处理
     *   （通知里出现「支付」的概率远高于纯收入通知，且误判成支出的代价更小）。
     */
    fun detectDirection(text: String): Boolean {
        val content = stripSourceLabels(text)
        val expenseHits = EXPENSE_WORDS.count { content.contains(it) }
        val incomeHits = INCOME_WORDS.count { content.contains(it) }
        return incomeHits <= expenseHits
    }

    /** 生成备注：优先取收款方名称，否则取压平后的正文前 [NOTE_MAX_LENGTH] 个字符。 */
    fun buildNote(text: String): String {
        val bracketMatch = BRACKET_NAME.find(text)
        if (bracketMatch != null) {
            val name = bracketMatch.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        val cleaned = text.replace("\n", " ").replace(WHITESPACE, " ").trim()
        return cleaned.take(NOTE_MAX_LENGTH)
    }
}
