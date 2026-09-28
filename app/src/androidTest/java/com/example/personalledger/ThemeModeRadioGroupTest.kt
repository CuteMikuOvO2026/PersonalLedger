package com.example.personalledger

import android.content.Context
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「外观」弹窗里主题模式单选项的仪器化回归测试。
 *
 * 守住的是一条很容易被写坏的约束：**RadioGroup 的互斥只在子项「先有 id、后 addView」
 * 时才成立**。历史 bug 就是子项既没有 id、又在 addView 前就被勾选，导致 RadioGroup 内部
 * `mCheckedId` 停在 -1，之后点其它项时跳过「取消上一项勾选」——表现为
 * 「在切换深浅外观时始终显示跟随系统」。
 *
 * 因此这里不测「存了什么」，只测「点了之后到底有几项是勾上的、是哪一项」。
 */
@RunWith(AndroidJUnit4::class)
class ThemeModeRadioGroupTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 当前被勾选的下标集合。正常必须恰好只有一个元素，多于一个即互斥失效。 */
    private fun checkedIndices(group: RadioGroup): List<Int> =
        (0 until group.childCount).filter { (group.getChildAt(it) as RadioButton).isChecked }

    private fun click(group: RadioGroup, index: Int) {
        (group.getChildAt(index) as RadioButton).performClick()
    }

    @Test
    fun 三档模式下初始勾选都与已保存的模式一致() {
        ThemeSettings.labels().forEachIndexed { index, _ ->
            val group = buildThemeModeRadioGroup(context, ThemeSettings.fromIndex(index))
            assertEquals(listOf(index), checkedIndices(group))
        }
    }

    @Test
    fun 非法取值收敛为勾选跟随系统() {
        val group = buildThemeModeRadioGroup(context, "unknown")
        assertEquals(listOf(0), checkedIndices(group))
    }

    @Test
    fun 从跟随系统切到深色后跟随系统不再被勾选() {
        val group = buildThemeModeRadioGroup(context, ThemeSettings.SYSTEM)

        click(group, 2)

        assertEquals(listOf(2), checkedIndices(group))
    }

    @Test
    fun 从浅色切回跟随系统后浅色不再被勾选() {
        val group = buildThemeModeRadioGroup(context, ThemeSettings.LIGHT)

        click(group, 0)

        assertEquals(listOf(0), checkedIndices(group))
    }

    @Test
    fun 连续切换始终只剩一项被勾选() {
        val group = buildThemeModeRadioGroup(context, ThemeSettings.SYSTEM)

        listOf(1, 2, 0, 2, 1).forEach { index ->
            click(group, index)
            assertEquals(listOf(index), checkedIndices(group))
        }
    }

    @Test
    fun 点选回调拿到的是被点那一档的下标() {
        val picked = intArrayOf(-1)
        val group = buildThemeModeRadioGroup(context, ThemeSettings.DARK) { picked[0] = it }

        click(group, 1)

        assertEquals(1, picked[0])
        assertEquals(ThemeSettings.LIGHT, ThemeSettings.fromIndex(picked[0]))
    }
}
