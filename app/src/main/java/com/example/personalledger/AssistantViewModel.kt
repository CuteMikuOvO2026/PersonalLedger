package com.example.personalledger

import android.app.Application
import android.util.Log
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

    // ---------- 解析自测（评估集） ----------

    private val _evalRunning = MutableLiveData(false)

    /**
     * 是否正在评估。
     *
     * 它是进度弹窗的**唯一权威状态**：进度值本身不足以决定「该不该有弹窗」，因为 `postValue`
     * 发出的值可能在评估结束之后才被主线程执行 —— 只按进度值判断，迟到的旧值就会把已经关掉的
     * 弹窗重新弹出来（并叠在结果弹窗上）。
     */
    val evalRunning: LiveData<Boolean> = _evalRunning

    private val _evalProgress = MutableLiveData<Pair<Int, Int>?>(null)

    /** 评估进度「已完成 to 总数」；null 表示当前没有在评估。 */
    val evalProgress: LiveData<Pair<Int, Int>?> = _evalProgress

    private val _evalReport = MutableLiveData<AiEvalReport?>(null)

    /** 一次评估的汇总结果，由界面消费后调用 [clearEvalReport] 复位。 */
    val evalReport: LiveData<AiEvalReport?> = _evalReport

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
                    incomeCategories = categories.income,
                    // 历史作为**背景**注入提示词，而不是作为多条 user 消息发出去。
                    // 后者会让模型把历史里每一句都当成新的记账请求重新解析一遍
                    // ——「只说了一句咖啡15，却把前面几轮的话全记成账」就是这么来的。
                    recentUserInputs = history.map { it.content }
                )

                when (val outcome = AiRequestPolicy.requestAndParse(
                    apiKey = AiSettings.apiKey(app),
                    model = AiSettings.model(app),
                    systemPrompt = systemPrompt,
                    // 只发本次输入这一条 user 消息：模型看到的必须是「背景 + 一个待处理请求」
                    turns = listOf(AiChatTurn(ROLE_USER, content)),
                    allowedCategories = (categories.expense + categories.income).toSet(),
                    nowMillis = now
                )) {
                    is AiReplyOutcome.Parsed -> repository.appendChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = outcome.reply.reply.ifEmpty {
                            app.getString(R.string.ai_reply_empty)
                        },
                        draft = outcome.reply.entries
                    )

                    is AiReplyOutcome.Failed -> repository.appendChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = describeFailure(app, outcome)
                    )
                }
            } finally {
                // 放在 finally：任何异常都不该把发送按钮永久卡在禁用状态
                _sending.value = false
            }
        }
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

    // ---------- 解析自测 ----------

    /**
     * 跑一遍评估集，统计当前提示词与模型下的解析准确率。
     *
     * 为什么做成 App 内的功能而不是一次性脚本：它跑的是**生产同一条管线**
     * （同一个系统提示词、同一个客户端、同一个解析器），指标才反映线上真实表现。
     * 写脚本必然要复制一份逻辑，那份副本一旦和线上漂移，测出来的准确率就没有意义了。
     *
     * 代价是评估集进了 APK —— 对个人项目可以接受；若将来要上架，应移到 debug 构建里。
     *
     * @param extended true 跑[扩展集][AiEvalSet.extendedCases]，用于检查主集上的成绩是否过拟合；
     *   false（默认）跑[主集][AiEvalSet.cases]。两份用例共用同一套评分、重试与展示逻辑。
     */
    fun runEval(extended: Boolean = false) {
        // 防重入：评估要花一两分钟，重复触发会把同一批用例跑两遍、白花两遍钱
        if (_evalRunning.value == true) return
        if (_sending.value == true) return

        val app = getApplication<Application>()
        if (!AiSettings.isConfigured(app)) {
            _hint.value = app.getString(R.string.ai_not_configured)
            return
        }

        val cases = if (extended) AiEvalSet.extendedCases else AiEvalSet.cases
        _evalRunning.value = true
        _evalProgress.value = 0 to cases.size
        viewModelScope.launch {
            try {
                // ⚠️ 评估用 [AiEvalSet] 里**冻结**的分类清单，不读用户自定义分类 —— 见 [AiEvalSet.expenseCategories]。
                val report = AiEvalRunner(
                    apiKey = AiSettings.apiKey(app),
                    model = AiSettings.model(app),
                    expenseCategories = AiEvalSet.expenseCategories,
                    incomeCategories = AiEvalSet.incomeCategories
                ).evaluate(cases) { done, total ->
                    // 这里可以放心用 postValue：迟到的旧值会被界面的 evalRunning 闸门挡掉，
                    // 不会再把进度弹窗弹回来（这正是之前那个「结果出来了还叠着进度弹窗」的成因）
                    _evalProgress.postValue(done to total)
                }
                logEvalReport(report, extended)
                _evalReport.value = report
            } finally {
                // 顺序要紧：先关掉 running（界面据此收掉进度弹窗），再清进度值。
                // 反过来会让界面先收到一次「进度为空、但仍在运行」的矛盾状态。
                _evalRunning.value = false
                _evalProgress.value = null
            }
        }
    }

    fun clearEvalReport() {
        _evalReport.value = null
    }

    /**
     * 逐条明细写进 logcat。
     *
     * 弹窗只放得下汇总，而「哪几句错了、错成什么样」才是改提示词的依据 —— 那部分必须落到
     * 能复制、能留档的地方，也就是日志。
     */
    private fun logEvalReport(report: AiEvalReport, extended: Boolean) {
        val setName = if (extended) "扩展集" else "主集"
        Log.i(TAG_EVAL, "===== 解析自测汇总（$setName）=====")
        report.summaryLines().forEach { Log.i(TAG_EVAL, it) }
        report.results.forEachIndexed { index, result ->
            val mark = if (result.fullyCorrect) "OK" else "NG"
            val failure = result.failure?.let { " 失败:$it" }.orEmpty()
            // 重试救回的标出来：这类用例 [failure] 为 null（看着是干净的成功），
            // 但模型首次并没有答对。不标出来就没法从明细里看出模型到底稳不稳。
            val retried = result.firstAttemptFailure
                ?.takeIf { result.failure == null }
                ?.let { " 重试救回（首次:$it）" }
                .orEmpty()
            Log.i(TAG_EVAL, "${index + 1}. [$mark] ${result.case.input} → ${result.actualSummary}$failure$retried")
        }
        Log.i(TAG_EVAL, "===== 汇总结束 =====")
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
     * 最近若干轮**用户**发言，作为上下文。
     *
     * ⚠️ 刻意**不把助手消息**放进历史 —— 这是一个真实 bug 的修复。
     *
     * 助手的 `content` 是给人看的散文（「已解析出 1 笔支出」「没能理解这句话…」），
     * **不是**模型当初输出的 JSON。把它当作 assistant 轮次回喂，等于在提示词里现场演示
     * 「助手应该用散文回答」，而 system 里明明写着「只输出一个 json 对象」。
     * 这种自相矛盾的示范会诱导模型改用散文，于是这一轮解析失败；而失败信息又会作为助手
     * 消息进入下一轮历史，**自我强化**，对话越用越糟。
     *
     * 这也正好解释了「评估 22 条几乎全过、实际聊天却逐渐失败」的反差：评估里每条用例
     * 都是无历史的独立请求（见 [AiEvalRunner]），而聊天会不断累积污染。
     *
     * 只保留用户发言的代价很小：助手的散文本来就不含结构化信息，对「改成 20」这类追问
     * 也帮不上忙（真正要支持追问，得把模型原始 JSON 也存下来回喂，那是另一件事）。
     */
    private suspend fun recentTurns(): List<AiChatTurn> =
        repository.chatMessages.first()
            .filter { it.role == ChatRole.USER }
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
        // 三种失败的原因与修法完全不同，不能共用一句「没能理解」：
        // 空回复是偶发、重发即可；解析失败是模型输出不合规、要改提示词；UNKNOWN 多为响应结构异常。
        AiError.EMPTY_RESPONSE -> R.string.ai_error_empty
        AiError.PARSE_ERROR -> R.string.ai_error_parse
        AiError.UNKNOWN -> R.string.ai_error_unknown
    }

    /**
     * 把失败原因整理成气泡文案；解析失败时**附上模型原话**。
     *
     * 附原话是为了省掉「连电脑抓 adb 日志」这一步：截图就能定位。诊断信息贴在对话里
     * 也符合这个功能一贯的取舍 —— 错误要留痕，不能一闪而过。
     */
    private fun describeFailure(app: Application, outcome: AiReplyOutcome.Failed): String {
        val base = app.getString(errorMessageRes(outcome.error))
        val raw = outcome.rawContent?.takeIf { it.isNotBlank() } ?: return base
        return base + "\n\n" + app.getString(R.string.ai_model_returned, raw.take(500))
    }

    companion object {
        /** 与 [AiChatTurn] 的 role 取值对齐。 */
        private const val ROLE_USER = "user"

        /** 单次输入上限，避免误粘贴一大段文本烧掉 token。 */
        private const val MAX_INPUT_LENGTH = 500

        /** 随请求发送的历史轮数。 */
        private const val HISTORY_TURNS = 6

        /** 评估明细的日志标签，`adb logcat -s AiEval` 可直接过滤。 */
        private const val TAG_EVAL = "AiEval"
    }
}
