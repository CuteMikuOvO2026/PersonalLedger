package com.example.personalledger

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 主题模式（浅色 / 深色 / 跟随系统）的常量与转换。
 *
 * 主题需要再次启动时无闪烁、且不阻塞主线程地应用，因此用 SharedPreferences 即时读写；
 * DataStore 侧保留一份用于 LiveData 的同步展示（见 [DataStoreManager]）。
 */
object ThemeSettings {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "theme_prefs"
    private const val KEY = "theme_mode"

    fun toNightMode(mode: String): Int = when (mode) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun labels(): Array<String> = arrayOf("跟随系统", "浅色", "深色")

    fun indexOf(mode: String): Int = when (mode) {
        LIGHT -> 1
        DARK -> 2
        else -> 0
    }

    fun fromIndex(index: Int): String = when (index) {
        1 -> LIGHT
        2 -> DARK
        else -> SYSTEM
    }

    /** 启动时同步读取（非阻塞，供 Application.onCreate 直接应用）。 */
    fun savedMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    /** 与 [savedMode] 对应的即时写入。 */
    fun saveMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }
}
