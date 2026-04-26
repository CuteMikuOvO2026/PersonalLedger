package com.example.personalledger

data class Category(
    val name: String,
    val iconRes: Int,
    val isExpense: Boolean
) {
    companion object {
        val EXPENSE_CATEGORIES = listOf(
            Category("餐饮", R.drawable.ic_food, true),
            Category("交通", R.drawable.ic_transport, true),
            Category("购物", R.drawable.ic_shopping, true),
            Category("娱乐", R.drawable.ic_entertainment, true),
            Category("医疗", R.drawable.ic_medical, true),
            Category("教育", R.drawable.ic_education, true),
            Category("住房", R.drawable.ic_housing, true),
            Category("其他", R.drawable.ic_other, true)
        )

        val INCOME_CATEGORIES = listOf(
            Category("工资", R.drawable.ic_salary, false),
            Category("奖金", R.drawable.ic_bonus, false),
            Category("投资", R.drawable.ic_investment, false),
            Category("兼职", R.drawable.ic_side_job, false),
            Category("其他", R.drawable.ic_other, false)
        )

        fun getDefaultCategory(isExpense: Boolean): Category {
            return if (isExpense) EXPENSE_CATEGORIES.last() else INCOME_CATEGORIES.last()
        }
    }
}
