package com.example.personalledger

data class Category(
    val name: String,
    val iconRes: Int,
    val isExpense: Boolean
) {
    companion object {
        // 支出分类
        val EXPENSE_CATEGORIES = listOf(
            Category("餐饮", android.R.drawable.ic_menu_compass, true),
            Category("交通", android.R.drawable.ic_menu_directions, true),
            Category("购物", android.R.drawable.ic_menu_myplaces, true),
            Category("娱乐", android.R.drawable.ic_menu_my_calendar, true),
            Category("医教", android.R.drawable.ic_menu_info_details, true),
            Category("其他", android.R.drawable.ic_menu_agenda, true)
        )

        // 收入分类
        val INCOME_CATEGORIES = listOf(
            Category("工资", android.R.drawable.ic_menu_myplaces, false),
            Category("奖金", android.R.drawable.ic_menu_compass, false),
            Category("理财", android.R.drawable.ic_menu_call, false),
            Category("其他", android.R.drawable.ic_menu_agenda, false)
        )

        // 获取默认分类（其他）
        fun getDefaultCategory(isExpense: Boolean): Category {
            return if (isExpense) {
                EXPENSE_CATEGORIES.last()
            } else {
                INCOME_CATEGORIES.last()
            }
        }
    }
}
