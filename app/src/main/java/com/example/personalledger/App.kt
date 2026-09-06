package com.example.personalledger

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 启动时应用已保存的主题模式（浅色 / 深色 / 跟随系统）
        // 使用 SharedPreferences 同步读取，避免在主线程 runBlocking 导致卡顿/ANR。
        AppCompatDelegate.setDefaultNightMode(ThemeSettings.toNightMode(ThemeSettings.savedMode(this)))
    }
}
