package com.example.personalledger

/**
 * 自动记账的分类规则：根据通知文本里的“收款方/商户”关键词，映射到预设分类。
 *
 * 说明：账本条目在 Room 中只存分类名称（`categoryName`，无外键），因此这里只需给出
 * 与 [MainViewModel] 预设分类一致的名字与图标资源即可正常展示。
 */
object AutoBookkeepingRules {

    data class Rule(
        val name: String,
        val iconRes: Int,
        val type: String,
        val keywords: List<String>
    )

    val expenseRules: List<Rule> = listOf(
        Rule("餐饮", R.drawable.ic_food, "expense", listOf(
            "美团", "饿了么", "外卖", "肯德基", "麦当劳", "星巴克", "瑞幸", "必胜客",
            "汉堡", "餐厅", "食堂", "咖啡", "奶茶", "火锅", "烧烤", "小吃", "美食"
        )),
        Rule("交通", R.drawable.ic_transport, "expense", listOf(
            "滴滴", "打车", "12306", "铁路", "高铁", "机票", "飞猪", "曹操", "T3出行",
            "地铁", "公交", "加油", "停车", "高德", "导航"
        )),
        Rule("购物", R.drawable.ic_shopping, "expense", listOf(
            "淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁", "超市", "便利店", "商场",
            "优衣库", "得物", "抖音商城"
        )),
        Rule("娱乐", R.drawable.ic_entertainment, "expense", listOf(
            "腾讯视频", "爱奇艺", "优酷", "哔哩哔哩", "bilibili", "网易云", "QQ音乐",
            "电影", "影院", "游戏", "手游", "剧场", "会员"
        )),
        Rule("医疗", R.drawable.ic_medical, "expense", listOf(
            "医院", "药房", "药店", "挂号", "诊所", "口腔", "体检", "医保"
        )),
        Rule("教育", R.drawable.ic_education, "expense", listOf(
            "学费", "培训", "课程", "网课", "书店", "教材", "考试", "教育", "图书馆", "学习"
        )),
        Rule("住房", R.drawable.ic_housing, "expense", listOf(
            "房租", "物业", "水费", "电费", "燃气", "水电", "停车费", "公寓", "还款"
        ))
    )

    val incomeRules: List<Rule> = listOf(
        Rule("工资", R.drawable.ic_salary, "income", listOf("工资", "薪资", "代发", "薪")),
        Rule("奖金", R.drawable.ic_bonus, "income", listOf("奖金", "红包", "奖励")),
        Rule("投资", R.drawable.ic_investment, "income", listOf("理财", "基金", "股票", "利息", "收益")),
        Rule("兼职", R.drawable.ic_side_job, "income", listOf("兼职", "劳务", "外包", "稿费"))
    )

    fun matchExpense(text: String): Rule? =
        expenseRules.firstOrNull { rule -> rule.keywords.any { text.contains(it) } }

    fun matchIncome(text: String): Rule? =
        incomeRules.firstOrNull { rule -> rule.keywords.any { text.contains(it) } }
}
