package com.example.personalledger

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiRequestPolicy] 的重试与首次失败归因测试。
 *
 * 这是整个评估体系里**最该被测的一段**：它同时决定了「用户能不能拿到结果」和
 * 「报出来的成功率是多少」。重试条件写错，得到的指标就是错的 —— 而错误的指标比没有指标更糟，
 * 它会让人据此做出错误判断。
 *
 * 全程离线：模型调用由 `complete` 参数注入，不发出任何网络请求。
 */
class AiRequestPolicyTest {

    private val categories = setOf("餐饮", "交通", "购物", "其他")

    /** 一段合法的模型输出（15 元餐饮支出）。 */
    private val validJson = """
        {"entries":[{"amount":15,"isExpense":true,"category":"餐饮","note":"咖啡",
        "dateTime":"2026-10-02 14:30"}],"reply":"已解析出 1 笔支出"}
    """.trimIndent()

    private fun success(content: String) =
        AiCompletionResult.Success(content, usage = null)

    private fun run(
        nowMillis: Long = FIXED_NOW,
        complete: suspend () -> AiCompletionResult
    ): AiReplyOutcome = runBlocking {
        AiRequestPolicy.requestAndParse(
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "system",
            turns = listOf(AiChatTurn("user", "咖啡 15")),
            allowedCategories = categories,
            nowMillis = nowMillis,
            complete = { _, _, _, _ -> complete() }
        )
    }

    // ---------- 正常路径 ----------

    @Test
    fun `一次成功时不记录首次失败`() {
        val outcome = run { success(validJson) }

        assertTrue("应解析成功", outcome is AiReplyOutcome.Parsed)
        val parsed = outcome as AiReplyOutcome.Parsed
        assertEquals(1, parsed.reply.entries.size)
        assertEquals(1_500L, parsed.reply.entries.first().amountCents)
        assertNull("一次就过时首次失败必须为 null", parsed.firstAttemptError)
    }

    // ---------- 重试：空回复 ----------

    @Test
    fun `空回复后重试成功并标记首次失败`() {
        var calls = 0
        val outcome = run {
            calls++
            if (calls == 1) AiCompletionResult.Failure(AiError.EMPTY_RESPONSE)
            else success(validJson)
        }

        assertEquals("应当正好请求两次", 2, calls)
        assertTrue(outcome is AiReplyOutcome.Parsed)
        // 这是本次改动的核心断言：结果可用，但**必须**留下「首次失败过」的痕迹，
        // 否则报告会把这次重试救回当成一次干净的成功，从而高估模型可靠性。
        assertEquals(AiError.EMPTY_RESPONSE, (outcome as AiReplyOutcome.Parsed).firstAttemptError)
    }

    @Test
    fun `两次都空回复时最终失败且首次失败可归因`() {
        var calls = 0
        val outcome = run { calls++; AiCompletionResult.Failure(AiError.EMPTY_RESPONSE) }

        assertEquals("不应超过 MAX_ATTEMPTS", AiRequestPolicy.MAX_ATTEMPTS, calls)
        val failed = outcome as AiReplyOutcome.Failed
        assertEquals(AiError.EMPTY_RESPONSE, failed.error)
        assertEquals(AiError.EMPTY_RESPONSE, failed.firstAttemptError)
    }

    // ---------- 重试：非法 JSON ----------

    @Test
    fun `非法 JSON 会重试且第二次成功后保留原话之外的结果`() {
        var calls = 0
        val outcome = run {
            calls++
            if (calls == 1) success("这不是 JSON") else success(validJson)
        }

        assertEquals(2, calls)
        assertTrue(outcome is AiReplyOutcome.Parsed)
        assertEquals(AiError.PARSE_ERROR, (outcome as AiReplyOutcome.Parsed).firstAttemptError)
    }

    @Test
    fun `解析失败时保留模型原话供诊断`() {
        val outcome = run { success("模型跑偏了，这是一段散文") }

        val failed = outcome as AiReplyOutcome.Failed
        assertEquals(AiError.PARSE_ERROR, failed.error)
        assertEquals("模型跑偏了，这是一段散文", failed.rawContent)
    }

    // ---------- 不可重试的错误必须立即返回 ----------

    @Test
    fun `鉴权失败不重试且首次失败被记为自身`() {
        var calls = 0
        val outcome = run { calls++; AiCompletionResult.Failure(AiError.UNAUTHORIZED) }

        assertEquals("401 重试毫无意义，只应请求一次", 1, calls)
        val failed = outcome as AiReplyOutcome.Failed
        assertEquals(AiError.UNAUTHORIZED, failed.error)
        // 首次尝试就撞上不可重试错误时，firstAttemptError 也必须非空 ——
        // 漏成 null 会让它被统计成「模型一次成功」，把失败藏起来。
        assertEquals(AiError.UNAUTHORIZED, failed.firstAttemptError)
    }

    @Test
    fun `限流与断网都不重试`() {
        listOf(AiError.RATE_LIMITED, AiError.NETWORK, AiError.INSUFFICIENT_BALANCE).forEach { error ->
            var calls = 0
            val outcome = run { calls++; AiCompletionResult.Failure(error) }
            assertEquals("$error 不应重试", 1, calls)
            assertEquals(error, (outcome as AiReplyOutcome.Failed).error)
        }
    }

    @Test
    fun `截断不重试`() {
        var calls = 0
        val outcome = run { calls++; AiCompletionResult.Failure(AiError.TRUNCATED) }

        // max_tokens 撞顶导致的截断，重试会得到一模一样的结果，纯属浪费一次往返
        assertEquals(1, calls)
        assertEquals(AiError.TRUNCATED, (outcome as AiReplyOutcome.Failed).error)
    }

    // ---------- 与分类白名单的联动 ----------

    @Test
    fun `清单外的分类被收敛到兜底值而不是丢弃整笔`() {
        val outcome = run {
            success("""{"entries":[{"amount":15,"isExpense":true,"category":"奶茶","note":"咖啡"}],"reply":"ok"}""")
        }

        val entry = (outcome as AiReplyOutcome.Parsed).reply.entries.single()
        assertEquals("分类名不在清单内应收敛成「其他」", "其他", entry.categoryName)
        assertEquals("金额本身仍然保留", 1_500L, entry.amountCents)
    }

    @Test
    fun `金额非法的整笔被丢弃`() {
        val outcome = run {
            success("""{"entries":[{"amount":0,"isExpense":true,"category":"餐饮"}],"reply":"ok"}""")
        }

        // 「宁可少记，不可错记」：金额 <= 0 时丢掉这一笔，绝不猜一个值补齐
        assertTrue((outcome as AiReplyOutcome.Parsed).reply.entries.isEmpty())
    }

    private companion object {
        /** 固定「现在」，避免用例依赖运行时刻。 */
        const val FIXED_NOW = 1_759_392_000_000L
    }
}
