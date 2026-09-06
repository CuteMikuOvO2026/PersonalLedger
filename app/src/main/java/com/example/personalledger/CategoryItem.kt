package com.example.personalledger

data class CategoryItem(
    val name: String,
    val iconRes: Int,
    val type: String,
    val isCustom: Boolean = false,
    /** 分类强调色（ARGB）。0 表示未指定，按分类名回退到内置色板。 */
    val color: Int = 0
)
