package com.example.personalledger

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一轮对话。[role] 取 `system` / `user` / `assistant`。 */
data class AiChatTurn(val role: String, val content: String)

/**
 * 一次请求的用量与耗时。
 *
 * 为什么要采这两个数：**改提示词、换模型、开关思考模式，都会同时影响准确率、延迟和花费**，
 * 只盯着准确率会在某天收到账单时才发现问题。有了它，「这次改动是变快还是变慢、变便宜还是变贵」
 * 才有事实依据，而不是凭感觉。
 */
data class AiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    /**
     * 命中上下文缓存（KV Cache）的输入 token 数。
     *
     * 单独采它是因为**计价完全不同**：命中部分按未命中的 1/50 计价。系统提示词每次请求都一样，
     * 因此这部分命中率通常很高 —— 不理解这一点会严重高估成本。
     */
    val cachedTokens: Int,
    /** 从发起到读完全部响应的墙钟耗时。 */
    val elapsedMillis: Long
)

/** 一次请求的结果。 */
sealed interface AiCompletionResult {
    data class Success(val content: String, val usage: AiUsage?) : AiCompletionResult

    data class Failure(val error: AiError) : AiCompletionResult
}

/**
 * 失败原因。**每一条都要在界面上有对应的中文说明**，不要合并成「请求失败」：
 * 401 要去改 Key、402 要去充值、429 要等一会儿、断网要检查网络——用户的下一步动作完全不同。
 */
enum class AiError {
    /** 本地没配 Key（调用方通常已提前挡掉，这里是兜底）。 */
    NO_API_KEY,

    /** 401：Key 无效或已失效。 */
    UNAUTHORIZED,

    /** 402：账户余额不足。 */
    INSUFFICIENT_BALANCE,

    /** 429：请求过于频繁。 */
    RATE_LIMITED,

    /** 400 / 422：请求体或参数不被接受（例如模型名不存在）。 */
    BAD_REQUEST,

    /** 5xx：DeepSeek 侧故障或过载。 */
    SERVER_ERROR,

    /** 连不上、超时、读写中断。 */
    NETWORK,

    /** 200 但 content 为空——DeepSeek 在 JSON Output 下已知会偶发此情况。 */
    EMPTY_RESPONSE,

    /** 拿到内容了，但不是能解析的合法 json（含模型跑偏、答非所问的情况）。 */
    PARSE_ERROR,

    /** `finish_reason = length`：回复被 max_tokens 截断，JSON 必然不完整。 */
    TRUNCATED,

    /** 其他未归类的失败。 */
    UNKNOWN
}

/**
 * DeepSeek 的调用封装 —— **全项目唯一的联网代码**。
 *
 * 几个刻意的取舍：
 *
 * 1. **`HttpURLConnection` 而不是 OkHttp / Retrofit。** 本项目原本零网络依赖，这里只有一个
 *    「POST 一个 JSON、拿回一个 JSON」的非流式请求，用 JDK 自带的实现即可，不必为一个请求
 *    引入一整套依赖（更不必改写 version catalog 与 README 的技术栈表）。
 *    代价是不支持 SSE 流式输出，等待期间由界面显示「正在解析…」。
 * 2. **`thinking` 必须显式关掉。** DeepSeek 的思考模式**默认是开启的**，不传这个字段，
 *    每次记账都会走一遍推理：又慢又贵，而且 `temperature` 在思考模式下会被忽略。
 * 3. **不发送 `frequency_penalty` / `presence_penalty`。** 官方已标注这两个参数废弃、
 *    传了也不生效，发出去只会让请求体里多两处读代码的人以为有用、实则无效的配置。
 * 4. 异常一律收敛成 [AiError]，不向上抛：调用方只需要「给用户看哪句话」。
 */
object DeepSeekClient {

    private const val TAG = "DeepSeekClient"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 输出上限。
     *
     * 从 800 提到 1200：助手支持改账 / 删账 / 统计之后，输出里多了 `action` 与 `target` /
     * `changes` / `range` 这些字段，一条「修改 + 长备注」的回复贴近 800 时会撞上
     * `finish_reason = length`，而截断的 JSON 必然解析失败（[AiError.TRUNCATED]）。
     *
     * 仍不能给得太大：这个上限同时也是「模型跑飞了长篇大论」的兜底。
     */
    private const val MAX_TOKENS = 1200

    /** 记账是抽取任务不是创作任务，低温度让同一句话的结果更稳定。 */
    private const val TEMPERATURE = 0.2

    private val gson = Gson()

    // ---------- 请求 / 响应结构 ----------

    private data class ChatRequest(
        val model: String,
        val messages: List<RequestMessage>,
        @SerializedName("response_format") val responseFormat: ResponseFormat,
        val thinking: Thinking,
        @SerializedName("max_tokens") val maxTokens: Int,
        val temperature: Double,
        val stream: Boolean
    )

    private data class RequestMessage(val role: String, val content: String)

    private data class ResponseFormat(val type: String)

    private data class Thinking(val type: String)

    private data class ChatResponse(
        val choices: List<Choice>?,
        val usage: UsageDto?
    )

    private data class Choice(
        val message: ResponseMessage?,
        @SerializedName("finish_reason") val finishReason: String?
    )

    /** 响应里的 content 是可空的（被内容过滤或截断时会是 null）。 */
    private data class ResponseMessage(val content: String?)

    /**
     * 用量对象。**每个字段都可空**：这是网络来的数据，缺字段必须是 null 而不是让 Gson 塞进
     * 一个「非空但为 null」的假象（Gson 绕过 Kotlin 构造器，不会执行非空校验）。
     */
    private data class UsageDto(
        @SerializedName("prompt_tokens") val promptTokens: Int?,
        @SerializedName("completion_tokens") val completionTokens: Int?,
        @SerializedName("total_tokens") val totalTokens: Int?,
        @SerializedName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetailsDto?,
        /**
         * 顶层的缓存命中原生字段，与 `prompt_tokens_details.cached_tokens` **同值**。
         *
         * 两个都读、互为兜底：官方同时下发这两个字段，任何一个改名都不至于让命中率掉回 0。
         */
        @SerializedName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int?
    )

    private data class PromptTokensDetailsDto(
        /**
         * 缓存命中的输入 token 数。
         *
         * ⚠️ 这里曾经写的是 `prompt_cache_hit_tokens` —— **嵌套路径下并没有这个字段**，
         * 它只在 `usage` 顶层。Gson 对不存在的路径返回 null，而调用处用 `?: 0` 兜底，
         * 于是命中率恒为 0，且**永远不会报错**：日志里「缓存命中 0」看着完全正常。
         *
         * 教训：给解析外部结构的字段加默认值，会把一次 schema 漂移变成一个看起来正常的零。
         * 这个 bug 让 [AiUsage.cachedTokens] 的成本意义完全失效（命中部分按 1/50 计价），
         * 而它被发现靠的是**打日志看原始响应**，不是看代码。
         */
        @SerializedName("cached_tokens") val cachedTokens: Int?
    )

    /**
     * 发起一次对话补全。
     *
     * 本函数是阻塞的（`HttpURLConnection` 同步 API），内部已切到 [Dispatchers.IO]，
     * 调用方直接 `suspend` 调用即可。
     */
    suspend fun complete(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<AiChatTurn>
    ): AiCompletionResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext AiCompletionResult.Failure(AiError.NO_API_KEY)

        val request = ChatRequest(
            model = model,
            messages = buildList {
                add(RequestMessage("system", systemPrompt))
                turns.forEach { add(RequestMessage(it.role, it.content)) }
            },
            responseFormat = ResponseFormat("json_object"),
            thinking = Thinking("disabled"),
            maxTokens = MAX_TOKENS,
            temperature = TEMPERATURE,
            stream = false
        )

        val body = gson.toJson(request)
        var connection: HttpURLConnection? = null
        val startedAt = System.currentTimeMillis()
        try {
            connection = (URL(AiSettings.BASE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            // 4xx/5xx 的说明文字在 errorStream 里，不读就只能看到一个光秃秃的状态码。
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = try {
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            } catch (_: IOException) {
                ""
            }

            if (code !in 200..299) {
                // 只记状态码与截断后的正文，绝不记 API Key。
                Log.e(TAG, "DeepSeek 返回 $code：${text.take(500)}")
                return@withContext AiCompletionResult.Failure(mapHttpError(code))
            }

            val result = parseSuccess(text, System.currentTimeMillis() - startedAt)
            when (result) {
                is AiCompletionResult.Success -> result.usage?.let {
                    // 成功路径统一在这里打一行用量，所有调用方（包括评估器）都不必自己记。
                    Log.i(
                        TAG,
                        "解析成功 ${it.elapsedMillis}ms · ${it.totalTokens} tokens" +
                            "（输入 ${it.promptTokens}，其中缓存命中 ${it.cachedTokens}；输出 ${it.completionTokens}）"
                    )
                }

                is AiCompletionResult.Failure -> {
                    // ⚠️ 把 200 响应体**原样**打出来。
                    //
                    // 没有这一行，「没听懂」这个提示背后有三种完全不同的原因无法区分：
                    // ① 模型返回了合法 JSON 但字段不合规（被解析器逐笔丢弃）
                    // ② 模型返回了非法 JSON / 包了代码块围栏 / entries 类型不对
                    // ③ content 为空或只有空白
                    // 三者的修法完全不同，而用户在界面上看到的都是同一句话。
                    // 这是错误路径，日志长一点无所谓。
                    Log.e(TAG, "响应无法使用（${result.error}）。原始响应：${text.take(1200)}")
                }
            }
            result
        } catch (e: IOException) {
            Log.e(TAG, "DeepSeek 请求失败（网络）", e)
            AiCompletionResult.Failure(AiError.NETWORK)
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek 请求失败（未知）", e)
            AiCompletionResult.Failure(AiError.UNKNOWN)
        } finally {
            // 必须断开：否则连接会留在连接池里，长会话下会持续占用资源。
            connection?.disconnect()
        }
    }

    /** 解析 200 的响应体，取出 `choices[0].message.content` 与用量。 */
    private fun parseSuccess(text: String, elapsedMillis: Long): AiCompletionResult {
        val response = try {
            gson.fromJson(text, ChatResponse::class.java)
        } catch (_: JsonSyntaxException) {
            null
        }

        val choice = response?.choices?.firstOrNull()
            ?: return AiCompletionResult.Failure(AiError.UNKNOWN)

        // 截断优先于内容判断：这时拿到的 JSON 一定是残缺的，交给解析器只会得到「没听懂」，
        // 定位不到真正的原因（输出太长）——而这两者给用户的建议完全不同。
        if (choice.finishReason == "length") {
            return AiCompletionResult.Failure(AiError.TRUNCATED)
        }

        val content = choice.message?.content
        if (content.isNullOrBlank()) {
            return AiCompletionResult.Failure(AiError.EMPTY_RESPONSE)
        }
        return AiCompletionResult.Success(content, response.usage.toUsage(elapsedMillis))
    }

    /**
     * 把响应用量转成 [AiUsage]；字段缺失时补 0（而不是让整条成功的回复因为一个统计字段失败）。
     *
     * 统计信息拿不到不该影响功能本身 —— 用量是「顺带采集」的观测数据，不是业务数据。
     *
     * 缓存命中的两个来源按「先嵌套、后顶层」取值。注意这里**不能**只看一个：
     * 之前只看嵌套路径上一个不存在的字段名，命中率静默恒为 0（详见 [PromptTokensDetailsDto]）。
     */
    private fun UsageDto?.toUsage(elapsedMillis: Long): AiUsage? {
        if (this == null) return null
        val prompt = promptTokens ?: 0
        val completion = completionTokens ?: 0
        return AiUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = totalTokens ?: (prompt + completion),
            cachedTokens = promptTokensDetails?.cachedTokens ?: promptCacheHitTokens ?: 0,
            elapsedMillis = elapsedMillis
        )
    }

    private fun mapHttpError(code: Int): AiError = when (code) {
        401 -> AiError.UNAUTHORIZED
        402 -> AiError.INSUFFICIENT_BALANCE
        429 -> AiError.RATE_LIMITED
        400, 422 -> AiError.BAD_REQUEST
        in 500..599 -> AiError.SERVER_ERROR
        else -> AiError.UNKNOWN
    }
}
