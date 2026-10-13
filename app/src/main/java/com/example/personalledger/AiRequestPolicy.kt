package com.example.personalledger

import android.util.Log

/**
 * 一次「请求 → 解析 → 按需重试」的结果。
 *
 * [Parsed] / [Failed] 两个分支都带 [firstAttemptError]：它记录**首次尝试**（重试之前）是否
 * 已经失败过。这个字段是刻意留的，见 [AiEvalReport.firstAttemptFailures]。
 */
sealed interface AiReplyOutcome {

    /**
     * 拿到并成功解析了模型输出。
     *
     * @param firstAttemptError 首次尝试的失败原因；null 表示一次就过。
     *   非 null 说明**这次是重试救回来的** —— 结果一样可用，但模型的可靠性并没有那么高。
     */
    data class Parsed(
        val reply: AiReply,
        val usage: AiUsage?,
        val firstAttemptError: AiError? = null
    ) : AiReplyOutcome

    /**
     * @param rawContent 解析失败时模型的原始输出，用于把诊断信息显示给用户；
     *   其他失败（网络、鉴权等）没有可展示的模型输出，为 null。
     * @param firstAttemptError 首次尝试的失败原因；null 表示首次就撞上了不可重试的错误。
     */
    data class Failed(
        val error: AiError,
        val rawContent: String? = null,
        val firstAttemptError: AiError? = null
    ) : AiReplyOutcome
}

/**
 * 「请求 → 解析 → 失败重试」这条策略的**唯一实现**。
 *
 * 为什么必须抽出来共用：它有两个调用方 —— 助手页的发送（[AssistantViewModel.send]）与
 * 解析自测（[AiEvalRunner]）。只要这两处各写一份，评估测的就不再是生产行为：副本一旦漂移，
 * 准确率反映的是那个副本，而不是用户实际会遇到的东西。这是评估最常见的自欺方式，
 * 所以这里在**结构上**堵死，而不是靠注释约定「记得两边一起改」。
 *
 * 实际发生过一次：生产对空回复重试一次，而评估是单次请求。于是同一批用例，
 * 评估报出来的失败率高于用户实际感受 —— 两个数字都「没错」，但谁也解释不了对方。
 */
object AiRequestPolicy {

    /**
     * 总尝试次数（含首次）。
     *
     * 定成 2 而不是更多：空回复与非法 JSON 都是低频偶发，第二次能救回来的概率很高，
     * 而每多一次重试都要多等一个完整的往返（本项目实测单次约 830ms），用户能感知到。
     */
    const val MAX_ATTEMPTS = 2

    /** 解析失败的日志标签，`adb logcat -s AiParse` 可直接过滤。 */
    private const val TAG_PARSE = "AiParse"

    /**
     * 发一次请求并解析；失败时**只对「偶发」的两类失败重试一次**。
     *
     * 重试范围是刻意收窄的：DeepSeek 的 JSON Output 已知会偶发返回空内容，模型偶尔也会
     * 返回不合法的 JSON，这两种重试有意义。而 401 / 402 / 429 / 断网重试只是白白多等一次
     * 往返、甚至更容易触发限流，直接告诉用户怎么办更快。
     *
     * 截断（[AiError.TRUNCATED]）同样不重试：它是 `max_tokens` 撞顶导致的，重试会得到
     * 一模一样的结果。
     *
     * @param complete 模型调用入口。默认就是生产实现，因此正常路径不受影响；
     *   抽成参数是为了让**重试与评分逻辑能脱离网络验证** —— 重试条件写错的话，得到的
     *   成功率就是错的，而错误的指标比没有指标更糟：它会让人据此做出错误判断。
     */
    suspend fun requestAndParse(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<AiChatTurn>,
        allowedCategories: Set<String>,
        nowMillis: Long,
        maxAttempts: Int = MAX_ATTEMPTS,
        complete: suspend (
            apiKey: String,
            model: String,
            systemPrompt: String,
            turns: List<AiChatTurn>
        ) -> AiCompletionResult = { key, m, prompt, t -> DeepSeekClient.complete(key, m, prompt, t) }
    ): AiReplyOutcome {
        var lastError = AiError.UNKNOWN
        var lastRawContent: String? = null
        var firstAttemptError: AiError? = null

        repeat(maxAttempts) { attempt ->
            when (val result = complete(apiKey, model, systemPrompt, turns)) {
                is AiCompletionResult.Success -> {
                    val parsed = AiBookkeepingParser.parse(result.content, allowedCategories, nowMillis)
                    if (parsed != null) {
                        return AiReplyOutcome.Parsed(parsed, result.usage, firstAttemptError)
                    }
                    lastError = AiError.PARSE_ERROR
                    // 留住模型原话：既写日志，也随错误气泡显示给用户（省掉连电脑抓 adb 那一步）。
                    // 不这么做的话 content 会被静默丢弃，界面上只剩一句「没能理解」，
                    // 无法区分是模型返回了非法 JSON、还是字段不合规被逐笔丢弃 —— 两者修法完全不同。
                    lastRawContent = result.content
                    Log.e(TAG_PARSE, "解析失败（第 ${attempt + 1} 次）。模型原始输出：${result.content.take(1200)}")
                }

                is AiCompletionResult.Failure -> {
                    if (result.error != AiError.EMPTY_RESPONSE) {
                        // `?: result.error` 兜住「首次尝试就撞上不可重试错误」的情况 ——
                        // 此时 firstAttemptError 还没被赋值（赋值在下面），不能让它漏成 null。
                        return AiReplyOutcome.Failed(result.error, null, firstAttemptError ?: result.error)
                    }
                    lastError = result.error
                }
            }
            // 只在首轮记录：这一轮结束仍未返回，说明首次尝试失败了
            if (attempt == 0) firstAttemptError = lastError
        }
        return AiReplyOutcome.Failed(lastError, lastRawContent, firstAttemptError)
    }
}
