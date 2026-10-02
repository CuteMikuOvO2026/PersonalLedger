package com.example.personalledger

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * AI 助手页的视图模型：把「一句话」变成「一条待确认的账目卡片」。
 *
 * 分层上刻意与 [MainViewModel] 完全解耦（与 [ReportViewModel] 各自持有 repository 同一取舍）：
 * 助手有自己的一整套状态（发送中、待确认草稿），塞进 MainViewModel 只会让它继续膨胀。
 * 两者共享的是 [LedgerRepository] —— 写进同一张 `ledger_entries` 表，Room 的 Flow 会
 * 自动让首页列表与报表刷新，不需要任何跨 ViewModel 的通知。
 *
 * **助手不直接写账本**：解析结果一律先落成一条带草稿的消息，等用户在卡片上点确认
 * （[confirmDraft]）才写库。原因见 [AiBookkeepingParser] 的说明——模型会编造内容，
 * 而账本里一笔错账用户很难事后发现。
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LedgerRepository(application)

    /** 全部对话。持久化在 Room，重启后仍在。 */
    val messages: LiveData<List<ChatMessage>> = repository.chatMessages.asLiveData()

    private val _sending = MutableLiveData(false)

    /** 是否正在等待模型响应（驱动进度指示与发送按钮的禁用）。 */
    val sending: LiveData<Boolean> = _sending

    private val _hint = MutableLiveData<String?>(null)

    /** 一次性提示文案，由界面消费后调用 [clearHint] 复位，避免旋转屏幕后重复弹。 */
    val hint: LiveData<String?> = _hint

    init {
        // 与 MainViewModel 的 init 重复是**刻意的**：助手页可能经 Activity 重建直接恢复
        // （那时 MainViewModel 根本没被创建，自定义分类颜色就没人注册了）。
        // 注册是幂等的（内部先 clear 再填），重复调用没有副作用。
        viewModelScope.launch {
            repository.customCategories.collect { CategoryColors.registerCustomColors(it) }
        }
    }

    // ---------- 发送 ----------

    /**
     * 发送一句自然语言。
     *
     * 流程：先落库用户消息（哪怕后面请求失败，用户也知道自己说过什么）→ 组装提示词 →
     * 请求模型（失败自动重试一次）→ 把结果（或错误说明）作为助手消息落库。
     *
     * 错误也写进对话而不是只弹 Toast：网络问题、余额不足这些提示一闪而过更容易被忽略，
     * 而「刚才那句话为什么没记账」需要在对话里留痕。
     */
    fun send(text: String) {
        val content = text.trim().take(MAX_INPUT_LENGTH)
        if (content.isEmpty()) return
        if (_sending.value == true) return

        val app = getApplication<Application>()
        if (!AiSettings.isConfigured(app)) {
            _hint.value = app.getString(R.string.ai_not_configured)
            return
        }

        _sending.value = true
        viewModelScope.launch {
            try {
                // 取历史要在写入本条之前：否则本次输入既在历史里又被单独追加一次，模型会看到两遍
                val history = recentTurns()
                repository.appendChatMessage(ChatRole.USER, content)

                val now = System.currentTimeMillis()
                val categories = loadCategoryNames()
                val systemPrompt = AiPrompt.systemPrompt(
                    nowMillis = now,
                    expenseCategories = categories.expense,
                    incomeCategories = categories.income
                )

                when (val outcome = requestReply(
                    apiKey = AiSettings.apiKey(app),
                    model = AiSettings.model(app),
                    systemPrompt = systemPrompt,
                    turns = history + AiChatTurn(ROLE_USER, content),
                    allowedCategories = (categories.expense + categories.income).toSet(),
                    nowMillis = now
                )) {
                    is AiOutcome.Parsed -> repository.appendChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = outcome.reply.reply.ifEmpty {
                            app.getString(R.string.ai_reply_empty)
                        },
                        draft = outcome.reply.entries
                    )

                    is AiOutcome.Failed -> repository.appendChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = app.getString(errorMessageRes(outcome.error))
                    )
                }
            } finally {
                // 放在 finally：任何异常都不该把发送按钮永久卡在禁用状态
                _sending.value = false
            }
        }
    }

    /**
     * 请求一次模型并解析，失败时**只对「偶发」的两类失败重试一次**。
     *
     * 重试范围是刻意收窄的：DeepSeek 的 JSON Output 已知会偶发返回空内容，模型偶尔也会
     * 返回不合法的 JSON，这两种重试有意义。而 401 / 402 / 429 / 断网重试只是白白多等一次
     * 往返、甚至更容易触发限流，直接告诉用户怎么办更快。
     */
    private suspend fun requestReply(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<AiChatTurn>,
        allowedCategories: Set<String>,
        nowMillis: Long
    ): AiOutcome {
        var lastError = AiError.UNKNOWN
        repeat(MAX_ATTEMPTS) {
            when (val result = DeepSeekClient.complete(apiKey, model, systemPrompt, turns)) {
                is AiCompletionResult.Success -> {
                    val parsed = AiBookkeepingParser.parse(result.content, allowedCategories, nowMillis)
                    if (parsed != null) return AiOutcome.Parsed(parsed)
                    lastError = AiError.PARSE_ERROR
                }

                is AiCompletionResult.Failure -> {
                    if (result.error != AiError.EMPTY_RESPONSE) return AiOutcome.Failed(result.error)
                    lastError = result.error
                }
            }
        }
        return AiOutcome.Failed(lastError)
    }

    // ---------- 确认 / 忽略 ----------

    /**
     * 确认一张待确认卡片：把草稿写进账本。
     *
     * 幂等由 [LedgerRepository.confirmDraft] 的原子占位保证（连点两次只会入账一次），
     * 这里只负责在「已经被处理过」时给出提示，而不是静默成功。
     */
    fun confirmDraft(message: ChatMessage) {
        val draft = message.draft ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val items = draft.map { it.toLedgerItem() }
            val text = app.getString(R.string.ai_confirmed, describe(draft))
            if (!repository.confirmDraft(message.id, items, text)) {
                _hint.value = app.getString(R.string.ai_already_confirmed)
            }
        }
    }

    /** 忽略一张待确认卡片：只清掉草稿，不写账本。 */
    fun discardDraft(message: ChatMessage) {
        if (message.draft == null) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            if (!repository.discardDraft(message.id, app.getString(R.string.ai_discarded))) {
                _hint.value = app.getString(R.string.ai_already_confirmed)
            }
        }
    }

    fun clearConversation() {
        viewModelScope.launch { repository.clearChat() }
    }

    fun clearHint() {
        _hint.value = null
    }

    // ---------- 组装提示词所需的数据 ----------

    private class CategoryNames(val expense: List<String>, val income: List<String>)

    /**
     * 交给模型的分类清单：内置 + 用户自定义，另外给支出补上兜底的「其他」。
     *
     * 必须让模型看到「其他」：它是 [AiBookkeepingParser] 对清单外分类的收敛目标，
     * 不列出来的话模型会以为没有这个选项，于是硬凑一个不存在的分类名。
     */
    private suspend fun loadCategoryNames(): CategoryNames {
        val custom = repository.customCategories.first()
        val expense = (DefaultCategories.expense + custom.filter { it.type == "expense" })
            .map { it.name } + AiBookkeepingParser.DEFAULT_CATEGORY
        val income = (DefaultCategories.income + custom.filter { it.type == "income" })
            .map { it.name }
        return CategoryNames(expense.distinct(), income.distinct())
    }

    /**
     * 最近若干轮对话作为上下文，用于「刚才那笔改成 20」这类追问。
     *
     * 只取末尾 [HISTORY_TURNS] 条：完整历史会让每次请求的 token 数随对话长度无限增长，
     * 而记账这个场景对久远上下文的依赖很弱。
     */
    private suspend fun recentTurns(): List<AiChatTurn> =
        repository.chatMessages.first()
            .takeLast(HISTORY_TURNS)
            .map { AiChatTurn(it.role.value, it.content) }

    // ---------- 映射与文案 ----------

    /**
     * 草稿条目 → 账本条目。
     *
     * 图标按分类名解析（[CategoryColors.iconResFor]），与列表渲染、通知自动记账走同一条路径，
     * 因此模型给出的分类名一旦被收敛成「其他」，图标也会跟着落到兜底图标，不会出现
     * 「分类写餐饮、图标是其他」的错配。
     */
    private fun ParsedEntry.toLedgerItem(): LedgerItem = LedgerItem(
        id = UUID.randomUUID().toString(),
        amountCents = amountCents,
        note = note,
        timeMillis = timeMillis,
        isExpense = isExpense,
        categoryName = categoryName,
        categoryIconRes = CategoryColors.iconResFor(categoryName)
    )

    /** 确认后留在气泡里的摘要，例如「餐饮 -15.00（咖啡）」。 */
    private fun describe(entries: List<ParsedEntry>): String =
        entries.joinToString("、") { entry ->
            val sign = if (entry.isExpense) "-" else "+"
            val amount = String.format(Locale.getDefault(), "%.2f", entry.amountCents / 100.0)
            buildString {
                append(entry.categoryName).append(' ').append(sign).append(amount)
                if (entry.note.isNotEmpty()) append('（').append(entry.note).append('）')
            }
        }

    private fun errorMessageRes(error: AiError): Int = when (error) {
        AiError.NO_API_KEY, AiError.UNAUTHORIZED -> R.string.ai_error_unauthorized
        AiError.INSUFFICIENT_BALANCE -> R.string.ai_error_balance
        AiError.RATE_LIMITED -> R.string.ai_error_rate_limit
        AiError.BAD_REQUEST -> R.string.ai_error_bad_request
        AiError.SERVER_ERROR -> R.string.ai_error_server
        AiError.NETWORK -> R.string.ai_error_network
        AiError.TRUNCATED -> R.string.ai_error_truncated
        AiError.EMPTY_RESPONSE, AiError.PARSE_ERROR, AiError.UNKNOWN -> R.string.ai_error_parse
    }

    /** [requestReply] 的内部结果，避免用异常表达「模型没听懂」这种正常情况。 */
    private sealed interface AiOutcome {
        data class Parsed(val reply: AiReply) : AiOutcome

        data class Failed(val error: AiError) : AiOutcome
    }

    companion object {
        /** 与 [AiChatTurn] 的 role 取值对齐。 */
        private const val ROLE_USER = "user"

        /** 单次输入上限，避免误粘贴一大段文本烧掉 token。 */
        private const val MAX_INPUT_LENGTH = 500

        /** 随请求发送的历史轮数。 */
        private const val HISTORY_TURNS = 6

        /** 总尝试次数（含首次）。 */
        private const val MAX_ATTEMPTS = 2
    }
}
