package com.example.personalledger

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * 主题属性色解析。
 *
 * 界面上的颜色统一通过**主题属性**（`?attr/colorPrimary`、`?attr/colorOnSurface` …）读取，
 * 而不是直接引用 `@color/xxx`。这样做的收益是：
 *
 * 1. **支持动态取色**：Material You 的动态配色会重写主题属性；只要界面读的是属性，
 *    开启「跟随壁纸配色」后整屏颜色就能一起跟随系统。
 * 2. **深色模式天然正确**：属性在 `values/` 与 `values-night/` 各有一套取值，不需要在代码里判断主题。
 *
 * 注意：语义色（收入绿 / 支出红 / 超支琥珀）与分类配色板**刻意不迁移**——
 * 它们承载含义，不应该随壁纸变化，因此仍直接引用固定的颜色资源。
 */
object ThemeColors {

    /**
     * 读取主题属性对应的颜色。
     *
     * [fallbackColorRes] 只在主题未定义该属性时兜底（正常情况下不会走到），
     * 传入原先直接引用的颜色资源，可保证行为不因主题配置缺失而突变。
     */
    @ColorInt
    fun of(
        context: Context,
        @AttrRes attr: Int,
        @ColorRes fallbackColorRes: Int
    ): Int = MaterialColors.getColor(context, attr, ContextCompat.getColor(context, fallbackColorRes))
}
