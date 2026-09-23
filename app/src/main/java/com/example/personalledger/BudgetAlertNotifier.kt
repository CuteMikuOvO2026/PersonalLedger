package com.example.personalledger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 预算预警通知。
 *
 * 只在预算进入 [BudgetState.WARNING]（达到 80%）或 [BudgetState.OVER]（超支）时提醒，
 * 并按「规则 + 当期」记录已提醒状态做去重——否则每轮定时任务都会重复弹同一条通知。
 */
object BudgetAlertNotifier {

    private const val CHANNEL_ID = "budget_alert"

    /** 通知 id 需要稳定且互不冲突，用规则键的哈希，避免同一规则的通知互相覆盖。 */
    private fun notificationIdOf(rule: BudgetRule): Int = rule.key.hashCode()

    fun notifyIfNeeded(context: Context, progressList: List<BudgetProgress>) {
        val alerts = progressList.filter { it.state != BudgetState.NORMAL }
        if (alerts.isEmpty()) return
        // 用户关闭了系统通知权限时直接跳过，不要静默失败后还记下去重状态
        if (!notificationsEnabled(context)) return

        ensureChannel(context)
        alerts.forEach { progress ->
            val dedupKey = "${progress.rule.key}@${LedgerStats.periodKey(progress.rule.period)}"
            if (BudgetAlertSettings.lastNotifiedState(context, dedupKey) == progress.state.name) {
                return@forEach
            }
            BudgetAlertSettings.markNotified(context, dedupKey, progress.state.name)
            post(context, progress)
        }
    }

    private fun post(context: Context, progress: BudgetProgress) {
        val scope = progress.rule.categoryName ?: context.getString(R.string.budget_scope_overall)
        val periodLabel = context.getString(
            if (progress.rule.period == BudgetPeriod.MONTH) R.string.budget_alert_this_month
            else R.string.budget_alert_this_week
        )

        val title = context.getString(
            if (progress.state == BudgetState.OVER) R.string.budget_alert_over_title
            else R.string.budget_alert_warning_title,
            scope
        )
        val text = if (progress.state == BudgetState.OVER) {
            context.getString(
                R.string.budget_alert_over_text,
                periodLabel,
                LedgerStats.formatAmount(progress.spentCents),
                LedgerStats.formatAmount(progress.overspentCents)
            )
        } else {
            context.getString(
                R.string.budget_alert_warning_text,
                periodLabel,
                LedgerStats.formatAmount(progress.spentCents),
                LedgerStats.formatAmount(progress.rule.limitCents),
                progress.percent
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_report)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationIdOf(progress.rule), notification)
        } catch (_: SecurityException) {
            // 权限在检查之后被撤销时忽略即可，下一轮定时任务会重新判断
        }
    }

    private fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.budget_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.budget_alert_channel_desc)
            }
        )
    }
}
