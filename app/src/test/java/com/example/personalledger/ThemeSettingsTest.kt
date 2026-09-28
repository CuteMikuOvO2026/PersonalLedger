package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ThemeSettings] 纯逻辑（模式 ↔ 弹窗选项下标）的 JVM 单元测试。
 *
 * 这里守住「外观」设置能正确回显的关键一环：用户选中第 N 个选项 → 存下的模式 →
 * 再次打开弹窗时必须解析回同一个下标。曾经 value 取不到时会一律落到 0（跟随系统）。
 */
class ThemeSettingsTest {

    @Test
    fun `选项下标与模式一一对应`() {
        assertEquals(0, ThemeSettings.indexOf(ThemeSettings.SYSTEM))
        assertEquals(1, ThemeSettings.indexOf(ThemeSettings.LIGHT))
        assertEquals(2, ThemeSettings.indexOf(ThemeSettings.DARK))

        assertEquals(ThemeSettings.SYSTEM, ThemeSettings.fromIndex(0))
        assertEquals(ThemeSettings.LIGHT, ThemeSettings.fromIndex(1))
        assertEquals(ThemeSettings.DARK, ThemeSettings.fromIndex(2))
    }

    @Test
    fun `选中某一档后回显仍是同一档`() {
        ThemeSettings.labels().forEachIndexed { index, _ ->
            val mode = ThemeSettings.fromIndex(index)
            assertEquals(index, ThemeSettings.indexOf(mode))
        }
    }

    @Test
    fun `非法或缺失的取值一律收敛为跟随系统`() {
        assertEquals(ThemeSettings.SYSTEM, ThemeSettings.normalize(null))
        assertEquals(ThemeSettings.SYSTEM, ThemeSettings.normalize(""))
        assertEquals(ThemeSettings.SYSTEM, ThemeSettings.normalize("unknown"))
        assertEquals(ThemeSettings.LIGHT, ThemeSettings.normalize(ThemeSettings.LIGHT))
        assertEquals(ThemeSettings.DARK, ThemeSettings.normalize(ThemeSettings.DARK))
    }
}
