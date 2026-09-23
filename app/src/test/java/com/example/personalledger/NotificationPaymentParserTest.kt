package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotificationPaymentParser] 的 JVM 单元测试。
 *
 * 这是「自动记账」唯一一条被自动化兜住的路径：金额算错或方向判反都会**静默**写进账本，
 * 用户很难发现，所以这里用真实微信 / 支付宝的通知文本做回归样本。
 *
 * 通知正文的形态来自 `LedgerNotificationListenerService` 里对 `EXTRA_TITLE` /
 * `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` 的拼接，因此样本里刻意保留了换行。
 */
class NotificationPaymentParserTest {

    // ---------- 金额提取 ----------

    @Test
    fun extractAmountCents_handlesCommonCurrencyFormats() {
        assertEquals(2550L, NotificationPaymentParser.extractAmountCents("已支付￥25.50"))
        assertEquals(2550L, NotificationPaymentParser.extractAmountCents("已支付 ¥25.50"))
        assertEquals(2550L, NotificationPaymentParser.extractAmountCents("消费25.50元"))
        assertEquals(10000L, NotificationPaymentParser.extractAmountCents("收款到账 ￥100"))
        assertEquals(888L, NotificationPaymentParser.extractAmountCents("收到红包 ￥8.88"))
    }

    @Test
    fun extractAmountCents_handlesThousandsSeparator() {
        // 微信大额通知会带千分位逗号
        assertEquals(123456L, NotificationPaymentParser.extractAmountCents("你已成功支付¥1,234.56"))
    }

    @Test
    fun extractAmountCents_returnsNullWhenNoAmount() {
        assertNull(NotificationPaymentParser.extractAmountCents("微信支付凭证"))
        assertNull(NotificationPaymentParser.extractAmountCents(""))
        // 0 元不算有效金额，避免把「0 元购」之类误记一笔
        assertNull(NotificationPaymentParser.extractAmountCents("已支付￥0.00"))
    }

    // ---------- 收支方向：真实通知样本 ----------

    @Test
    fun detectDirection_wechatExpense() {
        val sample = "微信支付\n微信支付凭证\n已支付￥25.50"
        assertTrue("微信支付凭证应判为支出", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_alipayExpense() {
        val sample = "支付宝\n支付成功 ￥25.50"
        assertTrue("支付宝支付成功应判为支出", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_expenseNotificationMentioningPayee() {
        // 关键边界：支出通知里也会出现「收款方」字样，不能因此判成收入
        val sample = "支付宝\n付款成功，收款方：XX超市 ￥88.00"
        assertTrue("出现「收款方」不应翻转方向", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_wechatIncome() {
        val sample = "微信收款助手\n微信支付收款到账通知\n收款到账 ￥100.00"
        assertFalse("收款到账应判为收入", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_redPacketIsIncome() {
        val sample = "微信\n收到红包 ￥8.88"
        assertFalse("收到红包应判为收入", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_transferReceivedIsIncome() {
        val sample = "微信转账\n已收款 ￥200.00"
        assertFalse("已收款应判为收入", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_refundIsIncome() {
        // 退款到账是钱回来，应当算收入；这里的关键词是「到账」
        val sample = "微信支付\n退款到账 ￥25.50"
        assertFalse("退款到账应判为收入", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_defaultsToExpenseWhenNoSignal() {
        // 没有任何方向关键词时按支出兜底（通知里出现「支付」的概率远高于纯收入通知）
        assertTrue(NotificationPaymentParser.detectDirection("￥25.50"))
    }

    // ---------- 回归：曾静默记错账的两个样本 ----------

    @Test
    fun detectDirection_appNameInTitleDoesNotForceExpense() {
        // 回归：应用名「微信支付」自带「支付」，曾给每条通知白送一张支出票，
        // 把只带一个收入信号的「收到转账」平票兜底成了支出
        val sample = "微信支付\n收到转账 ￥200.00"
        assertFalse("标题里的应用名不应把收入判成支出", NotificationPaymentParser.detectDirection(sample))
    }

    @Test
    fun detectDirection_salaryKeywordIsIncome() {
        // 回归：收入词表曾漏掉「工资 / 代发」，而 AutoBookkeepingRules 已把「代发」
        // 当作收入分类，两边不同步导致 8000 元工资被记成一笔支出
        assertFalse("工资代发应判为收入", NotificationPaymentParser.detectDirection("支付宝\n工资代发 ￥8000.00"))
    }

    @Test
    fun parse_salaryNotificationIsNotRecordedAsExpense() {
        // 上面那条缺陷的端到端形态：工资必须入账为收入，且分类为「工资」
        val parsed = NotificationPaymentParser.parse("支付宝\n工资代发 ￥8000.00")

        assertEquals(800000L, parsed?.amountCents)
        assertFalse("工资不能记成支出", parsed!!.isExpense)
        assertEquals("工资", parsed.categoryName)
    }

    // ---------- 词表同步守护 ----------

    @Test
    fun incomeWordListCoversIncomeRules() {
        // 不变式：incomeRules 的关键词必须能在方向投票里投出「收入」，否则会出现
        // 「分类判收入、方向判支出」的自相矛盾——历史上「工资代发」就是这样：
        // 一边被 incomeRules 当作收入分类，一边被判成支出方向。
        // 唯一例外是投资标的词，见下一个用例。
        val uncovered = AutoBookkeepingRules.incomeRules
            .flatMap { rule -> rule.keywords }
            .filterNot { it in NotificationPaymentParser.AMBIGUOUS_INVESTMENT_KEYWORDS }
            .filter { keyword -> NotificationPaymentParser.detectDirection("$keyword ￥100.00") }

        assertEquals(
            "以下 incomeRules 关键词会被判成支出，需同步进 INCOME_WORDS：$uncovered",
            emptyList<String>(),
            uncovered
        )
    }

    @Test
    fun ambiguousInvestmentKeywordsDoNotVoteIncome() {
        // 投资标的词（理财 / 基金 / 股票）刻意不进方向词表：它们回答的是「买了什么」，
        // 而不是「钱往哪走」。若「股票」能投收入票，「股票买入」就会被记成一笔收入。
        for (keyword in NotificationPaymentParser.AMBIGUOUS_INVESTMENT_KEYWORDS) {
            assertTrue(
                "「$keyword」不应单独投出收入票",
                NotificationPaymentParser.detectDirection("$keyword ￥100.00")
            )
        }
    }

    @Test
    fun detectDirection_investmentDirectionWordsDecideDirection() {
        // 标的词不投票，方向交给方向词：买入 / 申购 / 定投 是支出，赎回 / 卖出 / 分红 是收入
        assertTrue(
            "股票买入应判为支出",
            NotificationPaymentParser.detectDirection("支付宝\n股票买入 ￥5000.00")
        )
        assertTrue(
            "基金申购应判为支出",
            NotificationPaymentParser.detectDirection("支付宝\n基金申购 ￥1000.00")
        )
        assertTrue(
            "基金定投应判为支出",
            NotificationPaymentParser.detectDirection("支付宝\n基金定投扣款 ￥1000.00")
        )
        assertFalse(
            "基金赎回应判为收入",
            NotificationPaymentParser.detectDirection("支付宝\n基金赎回 ￥1000.00")
        )
        assertFalse(
            "股票分红应判为收入",
            NotificationPaymentParser.detectDirection("微信支付\n股票分红 ￥300.00")
        )
    }

    @Test
    fun detectDirection_sideJobKeywordsAreIncome() {
        // incomeRules 的「兼职」关键词此前未进方向词表：转账类通知没有「到账」等收款词，
        // 会因 0:0 平票被兜底判成支出
        assertFalse(
            "兼职应判为收入",
            NotificationPaymentParser.detectDirection("微信转账\n兼职 ￥300.00")
        )
        assertFalse(
            "劳务应判为收入",
            NotificationPaymentParser.detectDirection("微信转账\n劳务费 ￥800.00")
        )
        assertFalse(
            "外包应判为收入",
            NotificationPaymentParser.detectDirection("微信转账\n外包 ￥1200.00")
        )
    }

    @Test
    fun detectDirection_salaryShortFormIsIncome() {
        // incomeRules 的「薪」关键词此前未进方向词表，「薪水」这类说法会漏判
        assertFalse(
            "薪水应判为收入",
            NotificationPaymentParser.detectDirection("支付宝\n薪水 ￥9000.00")
        )
    }

    @Test
    fun detectDirection_expenseSignalWinsOnTie() {
        // 反例：收入词与支出词同时出现且票数相同时，仍按支出兜底（刻意的保守取向）
        assertTrue(
            "「收款失败，已扣款」应判为支出",
            NotificationPaymentParser.detectDirection("微信支付\n收款失败，已扣款 ￥100.00")
        )
    }

    // ---------- 分类匹配 ----------

    @Test
    fun parse_fundRedemptionMapsToInvestmentCategory() {
        // 「基金」只在 incomeRules 里做分类用，方向靠「赎回」判定，两者配合才得出「投资 / 收入」
        val parsed = NotificationPaymentParser.parse("支付宝\n基金赎回 ￥1000.00")

        assertEquals(100000L, parsed?.amountCents)
        assertFalse("基金赎回不能记成支出", parsed!!.isExpense)
        assertEquals("投资", parsed.categoryName)
    }

    @Test
    fun parse_mapsMerchantKeywordToCategory() {
        val parsed = NotificationPaymentParser.parse("微信支付\n美团外卖 ￥32.00")

        assertEquals(3200L, parsed?.amountCents)
        assertTrue(parsed!!.isExpense)
        assertEquals("餐饮", parsed.categoryName)
    }

    @Test
    fun parse_mapsIncomeKeywordToIncomeCategory() {
        val parsed = NotificationPaymentParser.parse("支付宝\n工资代发 ￥8000.00")

        assertEquals(800000L, parsed?.amountCents)
        assertFalse(parsed!!.isExpense)
        assertEquals("工资", parsed.categoryName)
    }

    @Test
    fun parse_fallsBackToOtherWhenNoRuleMatches() {
        val parsed = NotificationPaymentParser.parse("微信支付\n已支付￥25.50")

        assertEquals("其他", parsed?.categoryName)
    }

    @Test
    fun parse_returnsNullWhenAmountMissing() {
        assertNull(NotificationPaymentParser.parse("微信支付\n您有一条新消息"))
    }

    // ---------- 备注 ----------

    @Test
    fun buildNote_prefersBracketName() {
        assertEquals("星巴克", NotificationPaymentParser.buildNote("微信支付\n【星巴克】已支付￥32.00"))
        assertEquals("美团外卖", NotificationPaymentParser.buildNote("微信支付\n[美团外卖] ￥32.00"))
    }

    @Test
    fun buildNote_flattensWhitespaceWhenNoBracket() {
        assertEquals(
            "微信支付 已支付￥25.50",
            NotificationPaymentParser.buildNote("微信支付\n已支付￥25.50")
        )
    }

    @Test
    fun buildNote_truncatesLongText() {
        val long = "备注".repeat(60)
        assertEquals(60, NotificationPaymentParser.buildNote(long).length)
    }

    // ---------- 端到端 ----------

    @Test
    fun parse_fullWechatPaymentNotification() {
        // 模拟 Service 里 "$title\n$text\n$bigText\n$sub" 的拼接结果
        val full = "微信支付\n已支付￥25.50\n【星巴克】微信支付凭证\n"
        val parsed = NotificationPaymentParser.parse(full)

        assertEquals(2550L, parsed?.amountCents)
        assertTrue(parsed!!.isExpense)
        assertEquals("餐饮", parsed.categoryName)
        assertEquals("星巴克", parsed.note)
    }
}
