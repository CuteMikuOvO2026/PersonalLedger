package com.example.personalledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 预算预警的定时检查。
 *
 * 每 6 小时跑一次：读取全部预算规则 + 各周期当期支出，算出执行情况，
 * 对达到预警 / 超支的规则发通知（去重逻辑在 [BudgetAlertNotifier] 内）。
 *
 * 选择定时任务而不是「打开 App 时检查」：预算提醒的价值就在于用户没打开 App 时也能收到，
 * 只在打开时检查就退化成了「事后告知」。
 */
class BudgetAlertWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!BudgetAlertSettings.isEnabled(context)) return Result.success()

        val repository = LedgerRepository(context)
        val rules = repository.budgets.first()
        if (rules.isEmpty()) return Result.success()

        // 只为实际用到的周期查询，避免无谓的数据库访问
        val totalsByPeriod = rules.map { it.period }.distinct().associateWith { period ->
            repository.periodCategoryTotals(LedgerStats.currentPeriodRange(period)).first()
        }
        val progressList = rules.map { rule ->
            BudgetStats.buildProgress(rule, totalsByPeriod.getValue(rule.period))
        }

        BudgetAlertNotifier.notifyIfNeeded(context, progressList)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "budget_alert_check"

        /** 检查间隔：预算变化是「天」级别的，6 小时足够及时，也不会过度唤醒设备。 */
        private const val INTERVAL_HOURS = 6L

        /**
         * 按当前开关状态同步定时任务：开启则排期，关闭则取消。
         *
         * 用 `KEEP` 策略：重复调用不会重建已有任务，因此在 [App.onCreate] 里
         * 无条件调用也是安全的（既能保证开机后恢复排期，又不会反复重置计时）。
         */
        fun sync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!BudgetAlertSettings.isEnabled(context)) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BudgetAlertWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            )
        }
    }
}
