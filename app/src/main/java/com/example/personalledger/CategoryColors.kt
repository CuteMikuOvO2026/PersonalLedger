package com.example.personalledger

import android.graphics.Color

/**
 * 按分类名分配一套柔和、清新的主色（分类彩色方案）。
 *
 * - [accentOf] 返回分类的强调色（用于图标/前景）。
 * - [containerOf] 返回同一色系的淡色底（用于圆形图标背景），通过低透明度叠加在卡片白底上，
 *   得到柔和、不扎眼的粉彩色，风格统一且不单调。
 * - 自定义分类可通过 [registerCustomColors] 注册「分类名 → 用户所选颜色」，优先于内置色板。
 */
object CategoryColors {

    /** 低透明度，用于生成淡色底（约 13% 不透明度）。 */
    private const val CONTAINER_ALPHA = 0x21 shl 24

    private val DEFAULT = 0xFF8C98A4.toInt()

    private val accents = mapOf(
        // 支出分类
        "餐饮" to 0xFFE8895E.toInt(),   // 珊瑚橙
        "交通" to 0xFF5C9FD6.toInt(),   // 天蓝
        "购物" to 0xFFE28CA8.toInt(),   // 粉
        "娱乐" to 0xFFA08BE0.toInt(),   // 紫
        "医疗" to 0xFFCE7787.toInt(),   // 玫红
        "教育" to 0xFF46A6A0.toInt(),   // 青
        "住房" to 0xFFC4946A.toInt(),   // 赭石
        "其他" to 0xFF93A0AC.toInt(),   // 灰蓝
        // 收入分类
        "工资" to 0xFF3FAA7E.toInt(),   // 绿
        "奖金" to 0xFFDBA84D.toInt(),   // 金
        "投资" to 0xFF6C86E6.toInt(),   // 靛蓝
        "兼职" to 0xFF55AECB.toInt()    // 青蓝
    )

    /** 自定义分类颜色注册表（分类名 → 用户所选颜色）。 */
    private val customAccents = HashMap<String, Int>()

    /** 供「添加分类」对话框选择的预设配色。 */
    val pickerPalette: IntArray = intArrayOf(
        0xFFE8895E.toInt(), // 珊瑚橙
        0xFF5C9FD6.toInt(), // 天蓝
        0xFFE28CA8.toInt(), // 粉
        0xFFA08BE0.toInt(), // 紫
        0xFFCE7787.toInt(), // 玫红
        0xFF46A6A0.toInt(), // 青
        0xFFC4946A.toInt(), // 赭石
        0xFF93A0AC.toInt(), // 灰蓝
        0xFF3FAA7E.toInt(), // 绿
        0xFFDBA84D.toInt(), // 金
        0xFF6C86E6.toInt(), // 靛蓝
        0xFF55AECB.toInt()  // 青蓝
    )

    /** 用当前自定义分类列表刷新颜色注册表（供账目列表按分类名解析颜色）。 */
    fun registerCustomColors(categories: List<CategoryItem>) {
        customAccents.clear()
        categories.forEach { if (it.color != 0) customAccents[it.name] = it.color }
    }

    /** 解析某个分类的强调色：[color] 非 0 时优先（自定义），否则按分类名匹配内置色板。 */
    fun accentFor(categoryName: String, color: Int = 0): Int {
        if (color != 0) return color
        return customAccents[categoryName] ?: accents[categoryName] ?: DEFAULT
    }

    /** 按分类名取强调色（自动包含已注册的自定义颜色）。 */
    fun accentOf(categoryName: String): Int = accentFor(categoryName, 0)

    /** 同色系淡色圆底。 */
    fun containerFor(categoryName: String, color: Int = 0): Int =
        (accentFor(categoryName, color) and 0x00FFFFFF) or CONTAINER_ALPHA

    fun containerOf(categoryName: String): Int = containerFor(categoryName, 0)

    fun isFallback(categoryName: String): Boolean = accents[categoryName] == null

    fun fallbackColor(): Int = Color.rgb(0x8C, 0x98, 0xA4)
}
