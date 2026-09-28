package com.example.personalledger

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主题模式在真机 / 模拟器上的验证：覆盖「用户选择 → 落盘 → 重新进入（重建 ViewModel）→ 回显」整条链。
 *
 * 这条链正是出 bug 的地方：回显曾经读的是没有常驻观察者的 `Flow.asLiveData()`，
 * 拿不到值就一律退回「跟随系统」。这里重建一个 ViewModel 模拟「重新打开设置页 / 重启应用后」的场景。
 */
@RunWith(AndroidJUnit4::class)
class ThemeSettingsInstrumentedTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun 选择浅色后重建ViewModel仍回显浅色() {
        ThemeSettings.saveMode(app, ThemeSettings.LIGHT)

        val viewModel = MainViewModel(app)
        assertEquals(ThemeSettings.LIGHT, viewModel.themeMode.value)
        assertEquals(1, ThemeSettings.indexOf(viewModel.themeMode.value))
    }

    @Test
    fun 选择深色后重建ViewModel仍回显深色() {
        ThemeSettings.saveMode(app, ThemeSettings.DARK)

        val viewModel = MainViewModel(app)
        assertEquals(ThemeSettings.DARK, viewModel.themeMode.value)
        assertEquals(2, ThemeSettings.indexOf(viewModel.themeMode.value))
    }

    @Test
    fun 通过ViewModel切换时同时更新落盘值与夜间模式() {
        val viewModel = MainViewModel(app)

        viewModel.setThemeMode(ThemeSettings.DARK)
        assertEquals(ThemeSettings.DARK, ThemeSettings.savedMode(app))
        assertEquals(ThemeSettings.DARK, viewModel.themeMode.value)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.getDefaultNightMode())

        viewModel.setThemeMode(ThemeSettings.LIGHT)
        assertEquals(ThemeSettings.LIGHT, ThemeSettings.savedMode(app))
        assertEquals(ThemeSettings.LIGHT, viewModel.themeMode.value)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.getDefaultNightMode())
    }

    @Test
    fun 只有跟随系统时才映射到跟随系统的夜间模式() {
        val viewModel = MainViewModel(app)

        viewModel.setThemeMode(ThemeSettings.SYSTEM)
        assertEquals(ThemeSettings.SYSTEM, ThemeSettings.savedMode(app))
        assertEquals(0, ThemeSettings.indexOf(viewModel.themeMode.value))
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.getDefaultNightMode()
        )
    }
}
