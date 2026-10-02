package com.example.personalledger

/**
 * 内置分类清单的**唯一来源**。
 *
 * 原先这份清单内联在 [MainViewModel] 里，只有记账表单一个使用方。AI 助手需要把同一份
 * 分类名交给模型做约束（见 [AiPrompt]），再抄一份必然漂移——模型返回了清单外的分类名，
 * 账本里就会出现一个没有配色、没有图标的「野生分类」。因此抽到这里共用。
 *
 * 注意与 [AutoBookkeepingRules] 的分工：那边是**通知关键词 → 分类**的规则表（一张表同时
 * 描述关键词与类别），职责是匹配；这里只是「有哪些内置分类」。两者名字相同是当前的事实，
 * 但改这里不需要同步改那边——除非新增的分类也希望被通知自动记账识别，那才需要一起加关键词。
 */
object DefaultCategories {

    val expense: List<CategoryItem> = listOf(
        CategoryItem("餐饮", R.drawable.ic_food, "expense"),
        CategoryItem("交通", R.drawable.ic_transport, "expense"),
        CategoryItem("购物", R.drawable.ic_shopping, "expense"),
        CategoryItem("娱乐", R.drawable.ic_entertainment, "expense"),
        CategoryItem("医疗", R.drawable.ic_medical, "expense"),
        CategoryItem("教育", R.drawable.ic_education, "expense"),
        CategoryItem("住房", R.drawable.ic_housing, "expense")
    )

    val income: List<CategoryItem> = listOf(
        CategoryItem("工资", R.drawable.ic_salary, "income"),
        CategoryItem("奖金", R.drawable.ic_bonus, "income"),
        CategoryItem("投资", R.drawable.ic_investment, "income"),
        CategoryItem("兼职", R.drawable.ic_side_job, "income")
    )

    /** 未命中任何规则时的兜底分类名，与 [NotificationPaymentParser] / [AiBookkeepingParser] 保持一致。 */
    const val FALLBACK = "其他"
}
