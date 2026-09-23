package com.example.personalledger

import android.content.Context

/**
 * 预算提醒的开关与「已提醒状态」记录。
 *
 * 用 SharedPreferences 而不是 DataStore：定时任务在后台读取，需要**同步、无协程**的访问，
 * 与 [ThemeSettings] 的取舍一致。
 *
 * 去重记录按「规则 + 当期」保存上次提醒过的状态，避免每轮定时任务都重复弹通知；
 * 状态从「预警」升级到「超支」时视为变化，会再提醒一次。
 */
object BudgetAlertSettings {

    private const val PREFS = "budget_alert_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_STATE_PREFIX = "last_state_"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        // 关闭时清掉去重记录，这样下次重新开启能立刻提醒当前状态
        if (!enabled) clearNotifiedStates(context)
    }

    fun lastNotifiedState(context: Context, rulePeriodKey: String): String? =
        prefs(context).getString(KEY_LAST_STATE_PREFIX + rulePeriodKey, null)

    fun markNotified(context: Context, rulePeriodKey: String, state: String) {
        prefs(context).edit().putString(KEY_LAST_STATE_PREFIX + rulePeriodKey, state).apply()
    }

    private fun clearNotifiedStates(context: Context) {
        val preferences = prefs(context)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(KEY_LAST_STATE_PREFIX) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
