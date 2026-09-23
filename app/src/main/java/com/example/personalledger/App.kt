package com.example.personalledger

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 启动时应用已保存的主题模式（浅色 / 深色 / 跟随系统）
        // 使用 SharedPreferences 同步读取，避免在主线程 runBlocking 导致卡顿/ANR。
        AppCompatDelegate.setDefaultNightMode(ThemeSettings.toNightMode(ThemeSettings.savedMode(this)))

        // Material You 动态取色：用 precondition 表达「是否开启」，而不是「只在开启时才注册回调」。
        // 这样用户在「外观」里切换开关后，只需重建当前 Activity 就能生效（precondition 会重新求值），
        // 无需重启进程；关掉开关时新 Activity 也会自然回到本应用固定的天蓝配色。
        // API 31 以下 Material 内部会直接跳过，是安全的空操作。
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                // precondition 形参是 (Activity, themeRes)，这里只关心应用级开关
                .setPrecondition { _, _ -> ThemeSettings.isDynamicColorEnabled(this) }
                .build()
        )

        // 按已保存的开关状态恢复预算预警的定时任务。
        // 用 KEEP 策略入队，重复调用不会重建任务，因此这里可以无条件调用；
        // 系统重启 / 应用升级后定时任务本身由 WorkManager 负责恢复，这里只兜底同步一次开关状态。
        BudgetAlertWorker.sync(this)

        // 同理恢复自动本地备份的定时任务（同样用 KEEP 策略，可无条件调用）
        AutoBackupWorker.sync(this)
    }
}
