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
        val parsed = NotificationPaymentParser.parse(full) ?: return

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
                // 图标按分类名解析，与列表渲染走同一条路径（见 CategoryColors.iconResFor）
                categoryIconRes = CategoryColors.iconResFor(parsed.categoryName)
            )
            repository.add(item)
            markRecent(contentKey, now)
        }
    }

    // ---------- 解析 ----------
    //
    // 解析逻辑（金额提取 / 收支方向判定 / 分类匹配 / 备注生成）已抽到
    // [NotificationPaymentParser]，以便用真实通知文本做 JVM 单测。

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
