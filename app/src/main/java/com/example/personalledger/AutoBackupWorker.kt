package com.example.personalledger

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 自动本地备份的开关（同步读写，定时任务在后台直接判断）。 */
object AutoBackupSettings {

    private const val PREFS = "auto_backup_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

/**
 * 自动备份文件的存放与轮转。
 *
 * 放在**应用专属的外部目录**（`Android/data/<包名>/files/backups`）：
 * 不需要任何存储权限，卸载即随之清理，同时又能通过文件管理器 / USB 取出来；
 * 外部存储不可用（未挂载）时退回应用内部目录，保证备份不会静默失败。
 */
object AutoBackupStore {

    private const val DIR_NAME = "backups"
    private const val FILE_PREFIX = "auto_backup_"
    private const val FILE_SUFFIX = ".json"

    /** 最多保留的备份份数，超出后删除最旧的。 */
    const val KEEP_COUNT = 5

    fun backupDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /** 全部备份文件，按时间**从新到旧**排列。 */
    fun list(context: Context): List<File> =
        backupDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 写入一份新备份并轮转，返回写入的文件。 */
    fun write(context: Context, json: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(backupDir(context), "$FILE_PREFIX$stamp$FILE_SUFFIX")
        file.writeText(json, Charsets.UTF_8)
        prune(context)
        return file
    }

    /** 只保留最近 [KEEP_COUNT] 份，删除更旧的，避免长期占用空间。 */
    private fun prune(context: Context) {
        list(context).drop(KEEP_COUNT).forEach { it.delete() }
    }
}

/**
 * 自动本地备份的定时任务。
 *
 * 每天把当前的完整备份（账目 + 预算 + 自定义分类）写入应用专属目录并轮转保留最近几份。
 * 选择定时任务而不是「退出时备份」：定时任务由系统在设备空闲时调度，
 * 不会在用户操作路径上增加 IO，也能覆盖「忘记手动导出」的情况。
 */
class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AutoBackupSettings.isEnabled(applicationContext)) return Result.success()

        return try {
            val repository = LedgerRepository(applicationContext)
            AutoBackupStore.write(applicationContext, repository.getBackupJson())
            Result.success()
        } catch (e: Exception) {
            Log.e("AutoBackupWorker", "自动备份失败", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "auto_local_backup"

        /** 每天一份足够；备份是「防手滑」用的，不需要更频繁。 */
        private const val INTERVAL_HOURS = 24L

        /** 按开关状态同步定时任务；用 KEEP 策略，重复调用不会重建已有任务。 */
        fun sync(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!AutoBackupSettings.isEnabled(context)) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoBackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
            )
        }
    }
}
