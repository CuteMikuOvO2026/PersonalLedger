package com.example.personalledger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.roundToLong

/**
 * 自动记账：监听微信 / 支付宝的支付成功通知，解析金额与收支方向，
 * 并按关键词映射到分类，写入 Room。
 *
 * 使用前提：
 * 1. 用户在系统「通知使用权」中为本应用开启权限；
 * 2. 在本应用「自动记账」设置中打开开关。
 *
 * 说明：仅用于个人自用，无法上架或商用（受平台隐私条款限制）。
 */
class LedgerNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: LedgerRepository
    private lateinit var dataStore: DataStoreManager

    /** 近期已处理通知的内存去重（防同一通知被重复回调）。 */
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 64
    }

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        repository = LedgerRepository(appContext)
        dataStore = DataStoreManager(appContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val pkg = notification.packageName
        if (pkg != PACKAGE_WECHAT && pkg != PACKAGE_ALIPAY) return

        val extras = notification.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        var sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        if (sub.isBlank()) sub = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()

        val full = "$title\n$text\n$bigText\n$sub"
        val parsed = parsePayment(full) ?: return

        val now = System.currentTimeMillis()
        val contentKey = "$pkg:${parsed.amountCents}:${parsed.isExpense}:${parsed.note}"
        if (isRecent(contentKey, now)) return

        scope.launch {
            // 每次插入前再确认开关打开，且做一次数据库级去重（防止进程重启后重复入库）。
            if (!dataStore.isAutoBookkeepingEnabled()) return@launch
            if (repository.existsRecentEntry(parsed.amountCents, parsed.isExpense, now - DEDUP_WINDOW_MS)) return@launch

            val item = LedgerItem(
                id = UUID.randomUUID().toString(),
                amountCents = parsed.amountCents,
                note = parsed.note,
                timeMillis = now,
                isExpense = parsed.isExpense,
                categoryName = parsed.categoryName,
                categoryIconRes = parsed.iconRes
            )
            repository.add(item)
            markRecent(contentKey, now)
        }
    }

    // ---------- 解析 ----------

    private data class Parsed(
        val amountCents: Long,
        val isExpense: Boolean,
        val categoryName: String,
        val iconRes: Int,
        val note: String
    )

    private fun parsePayment(text: String): Parsed? {
        val amountCents = extractAmountCents(text) ?: return null
        val isExpense = detectDirection(text)
        val rule = if (isExpense) AutoBookkeepingRules.matchExpense(text)
            else AutoBookkeepingRules.matchIncome(text)
        val categoryName = rule?.name ?: "其他"
        val iconRes = rule?.iconRes ?: R.drawable.ic_other
        val note = buildNote(text)
        return Parsed(amountCents, isExpense, categoryName, iconRes, note)
    }

    private fun extractAmountCents(text: String): Long? {
        val cleaned = text.replace(",", "")
        val patterns = listOf(
            Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)"""),
            Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*元""")
        )
        for (pattern in patterns) {
            val match = pattern.find(cleaned) ?: continue
            val amount = match.groupValues[1].toDoubleOrNull() ?: continue
            if (amount > 0) return (amount * 100).roundToLong()
        }
        return null
    }

    /** 返回 true 表示支出，false 表示收入；无明确信号时默认支出。 */
    private fun detectDirection(text: String): Boolean {
        val expenseWords = listOf("支付", "付款", "支出", "消费", "已付款", "付款成功", "成功付款", "已支付", "支付成功", "转账支出", "扣款")
        val incomeWords = listOf("收款", "到账", "入账", "收到", "已收款", "收入", "进账", "红包", "转入", "退回")
        val expenseHits = expenseWords.count { text.contains(it) }
        val incomeHits = incomeWords.count { text.contains(it) }
        return !(incomeHits > expenseHits)
    }

    private fun buildNote(text: String): String {
        // 优先取方括号/书名号内的“收款方名称”。
        val bracketMatch = Regex("""[\[\u3010]([^\]\u3011]{1,24})[\]\u3011]""").find(text)
        if (bracketMatch != null) {
            val name = bracketMatch.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        val cleaned = text.replace("\n", " ").replace(Regex("""\s+"""), " ").trim()
        return cleaned.take(60)
    }

    // ---------- 去重 ----------

    private fun isRecent(key: String, now: Long): Boolean {
        val cutoff = now - RECENT_TTL_MS
        recent.entries.removeAll { it.value < cutoff }
        return recent.containsKey(key)
    }

    private fun markRecent(key: String, now: Long) {
        recent[key] = now
    }

    companion object {
        const val PACKAGE_WECHAT = "com.tencent.mm"
        const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        /** 内存去重的时间窗。 */
        private const val RECENT_TTL_MS = 60_000L

        /** 数据库去重的时间窗。 */
        private const val DEDUP_WINDOW_MS = 3 * 60_000L
    }
}
