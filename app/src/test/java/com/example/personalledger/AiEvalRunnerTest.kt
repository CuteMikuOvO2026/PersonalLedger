package com.example.personalledger

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiEvalRunner] 的评分与指标口径测试 —— 全程离线。
 *
 * 为什么必须有这一层：[AiEvalRunner] 的注释一直写着「注入 `complete` 是为了让评分逻辑可以脱离
 * 网络验证」，但在此之前**没有任何测试兑现这句话**。评分算错的话，得到的准确率就是错的，
 * 而错误的准确率会直接写进简历、也会用来判断一次提示词改动是好是坏。
 *
 * 这里用构造好的模型响应直接喂进去，因此不依赖网络、不花钱、可重复。
 */
class AiEvalRunnerTest {

    private val expense = listOf("餐饮", "交通", "购物", "其他")
    private val income = listOf("工资", "奖金", "兼职")

    private val nowMillis = 1_759_392_000_000L

    /** 一个只有单笔期望的用例。 */
    private fun singleCase(input: String, amountCents: Long, category: String, isExpense: Boolean = true) =
        AiEvalCase(input, listOf(AiEvalExpectation(amountCents, isExpense, category)))

    /** 用「固定返回某段 JSON」的假模型跑一次评估。 */
    private fun evaluate(cases: List<AiEvalCase>, response: String): AiEvalReport = runBlocking {
        AiEvalRunner(
            apiKey = "test",
            model = "test-model",
            expenseCategories = expense,
            incomeCategories = income,
            complete = { _, _ -> AiCompletionResult.Success(response, usage = null) }
        ).evaluate(cases)
    }

    // ---------- 评分正确性 ----------

    @Test
    fun `完全命中时整句全对`() {
        val report = evaluate(
            listOf(singleCase("咖啡 15", 1_500, "餐饮")),
            """{"entries":[{"amount":15,"isExpense":true,"category":"餐饮","note":"咖啡"}],"reply":"ok"}"""
        )

        assertEquals(1, report.fullyCorrect)
        assertEquals(EvalScore(1, 1), report.amount)
        assertEquals(EvalScore(1, 1), report.category)
        assertEquals(EvalScore(1, 1), report.direction)
    }

    @Test
    fun `分类错但金额对时整句不算全对`() {
        // 「超市买菜 67.5」被记成餐饮 —— 这正是真机上出现过的失败
        val report = evaluate(
            listOf(singleCase("超市买菜 67.5", 6_750, "购物")),
            """{"entries":[{"amount":67.5,"isExpense":true,"category":"餐饮","note":"超市买菜"}],"reply":"ok"}"""
        )

        assertEquals("分类错了就不能算全对", 0, report.fullyCorrect)
        assertEquals(EvalScore(1, 1), report.amount)
        assertEquals(EvalScore(0, 1), report.category)
    }

    @Test
    fun `漏记时该笔在所有字段上都算未命中`() {
        val report = evaluate(
            listOf(singleCase("咖啡 15", 1_500, "餐饮")),
            """{"entries":[],"reply":"没听懂"}"""
        )

        assertEquals(0, report.fullyCorrect)
        assertEquals(EvalScore(0, 1), report.amount)
        assertEquals(EvalScore(0, 1), report.category)
        assertEquals(EvalScore(0, 1), report.direction)
    }

    @Test
    fun `多记也算错`() {
        // 「你好」期望不记账，模型却记了一笔 —— 反幻觉用例
        val report = evaluate(
            listOf(AiEvalCase("你好", emptyList())),
            """{"entries":[{"amount":15,"isExpense":true,"category":"餐饮"}],"reply":"ok"}"""
        )

        assertEquals("多记一笔必须判错", 0, report.fullyCorrect)
        assertEquals(1, report.noEntryCaseCount)
    }

    @Test
    fun `多笔用例按金额降序对齐而不受模型输出顺序影响`() {
        val cases = listOf(
            AiEvalCase(
                "早餐 8 块，咖啡 15",
                listOf(
                    AiEvalExpectation(800, true, "餐饮"),
                    AiEvalExpectation(1_500, true, "餐饮")
                )
            )
        )
        // 故意把小的那笔放前面：排序对齐后不应扣分
        val report = evaluate(
            cases,
            """{"entries":[{"amount":8,"isExpense":true,"category":"餐饮"},
                {"amount":15,"isExpense":true,"category":"餐饮"}],"reply":"ok"}"""
        )

        assertEquals(1, report.fullyCorrect)
        assertEquals(EvalScore(2, 2), report.amount)
    }

    // ---------- 分母口径 ----------

    @Test
    fun `用例数与账目笔数分别统计且不混淆`() {
        val cases = listOf(
            singleCase("咖啡 15", 1_500, "餐饮"),
            AiEvalCase(
                "早餐 8 块，咖啡 15",
                listOf(AiEvalExpectation(800, true, "餐饮"), AiEvalExpectation(1_500, true, "餐饮"))
            ),
            AiEvalCase("你好", emptyList())
        )
        val report = evaluate(cases, """{"entries":[],"reply":"ok"}""")

        // 这正是屏幕上出现过的那处矛盾：3 条用例里只有 2 条含账目，但期望笔数是 3 笔。
        // 两个数字都必须保留，且各自标注清楚，否则报告看着自相矛盾。
        assertEquals(3, report.total)
        assertEquals(2, report.entryCaseCount)
        assertEquals(1, report.noEntryCaseCount)
        assertEquals(3, report.expectedEntryCount)
    }

    // ---------- 重试与首次失败归因 ----------

    @Test
    fun `空回复由重试救回时计入首次失败但不计入请求失败`() {
        var calls = 0
        val report = runBlocking {
            AiEvalRunner(
                apiKey = "test",
                model = "test-model",
                expenseCategories = expense,
                incomeCategories = income,
                complete = { _, _ ->
                    calls++
                    // 每条用例的第一次调用返回空回复，第二次返回正确结果
                    if (calls % 2 == 1) AiCompletionResult.Failure(AiError.EMPTY_RESPONSE)
                    else AiCompletionResult.Success(
                        """{"entries":[{"amount":15,"isExpense":true,"category":"餐饮"}],"reply":"ok"}""",
                        usage = null
                    )
                }
            ).evaluate(listOf(singleCase("咖啡 15", 1_500, "餐饮")))
        }

        assertEquals("用户最终拿到了结果", 0, report.failed)
        assertEquals("但模型首次并没有答对", 1, report.firstAttemptFailures)
        assertEquals("差额就是重试救回的数量", 1, report.retriedSuccesses)
        assertEquals("重试后结果可用，仍算全对", 1, report.fullyCorrect)
    }

    @Test
    fun `报告记录模型名`() {
        val report = evaluate(listOf(singleCase("咖啡 15", 1_500, "餐饮")), """{"entries":[],"reply":"ok"}""")

        // 准确率离开模型名就没有意义：换模型数字会变，报告里不写就说不清是谁跑出来的
        assertEquals("test-model", report.model)
        assertTrue("模型名必须出现在汇总文本里", report.summaryLines().any { it.contains("test-model") })
    }

    @Test
    fun `首次尝试失败时汇总里同时给出两个口径`() {
        var calls = 0
        val report = runBlocking {
            AiEvalRunner(
                apiKey = "test",
                model = "test-model",
                expenseCategories = expense,
                incomeCategories = income,
                complete = { _, _ ->
                    calls++
                    if (calls == 1) AiCompletionResult.Failure(AiError.EMPTY_RESPONSE)
                    else AiCompletionResult.Success("""{"entries":[],"reply":"ok"}""", usage = null)
                }
            ).evaluate(listOf(AiEvalCase("你好", emptyList())))
        }

        val text = report.summaryLines().joinToString("\n")
        assertTrue("要给出最终失败口径", text.contains("请求失败"))
        assertTrue("也要给出首次尝试口径", text.contains("首次尝试失败"))
        assertTrue("并说明有几条是重试救回的", text.contains("重试救回"))
    }

    // ---------- 汇总文本 ----------

    @Test
    fun `汇总文本同时包含用例数与账目笔数两个口径`() {
        val report = evaluate(
            listOf(singleCase("咖啡 15", 1_500, "餐饮"), AiEvalCase("你好", emptyList())),
            """{"entries":[],"reply":"ok"}"""
        )

        val text = report.summaryLines().joinToString("\n")
        assertTrue(text.contains("含账目 1 条"))
        assertTrue(text.contains("纯对话 1 条"))
        assertTrue("账目笔数要单独写出来，避免与用例数并列时看着矛盾", text.contains("账目笔数：1 笔"))
    }

    @Test
    fun `EvalScore 未校验字段显示为破折号且不拖低全对判定`() {
        val unverified = EvalScore(0, 0)

        assertEquals("—", unverified.text)
        assertEquals("不校验的字段按满分计", 1.0, unverified.ratio, 0.0)
        assertNotNull(EvalScore(1, 2).text)
        assertNull(null)
    }
}
