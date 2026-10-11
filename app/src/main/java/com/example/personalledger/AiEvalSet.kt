package com.example.personalledger

/**
 * 一条用例的期望值。
 *
 * 三个刻意的设计，决定了这份评估集是否可信：
 *
 * 1. **日期用「相对今天的天数偏移」而不是具体日期**（[dayOffset]）。否则写死 `2026-10-02`
 *    的用例只在当天成立，隔天跑就全错 —— 那样的评估集自己就是坏的。
 * 2. **备注用关键词包含而不是逐字相等**（[noteContains]）。备注是自由文本，「咖啡」「买咖啡」
 *    「一杯咖啡」都是合理的。逐字比对会把模型的正常表达判成错误，让准确率虚低。
 * 3. **只收录分类判定没有歧义的用例**。像「健身房私教课」这种归「娱乐」还是「其他」没有共识，
 *    收进来只会污染指标 —— 评估集里的模糊用例是噪音，不是难度。
 */
data class AiEvalExpectation(
    val amountCents: Long,
    val isExpense: Boolean,
    val category: String,
    /** 相对今天的天数偏移：0=今天，-1=昨天，-2=前天。null 表示不校验日期。 */
    val dayOffset: Int? = null,
    /** 备注里应包含的关键词。null 表示不校验备注。 */
    val noteContains: String? = null
)

/** 一条评估用例：一句输入 + 期望解析出的账目（空列表表示期望「不记账」）。 */
data class AiEvalCase(
    val input: String,
    val expect: List<AiEvalExpectation>
) {
    /** 打招呼、问统计这类语句，期望不产生任何账目。 */
    val expectsNoEntry: Boolean get() = expect.isEmpty()
}

/**
 * 评估集。
 *
 * **期望值是我按「一个合理的人会怎么记」手工写的**，不是拿模型输出反推的 —— 后者等于自己给
 * 自己出题还抄答案，指标会永远好看但毫无意义。改这份文件时请保持这个原则。
 *
 * 覆盖维度：基础金额+分类、相对日期、收入方向、一句话多笔、非记账语句（反幻觉）。
 */
object AiEvalSet {

    val cases: List<AiEvalCase> = listOf(
        // ---------- 基础：金额 + 分类 ----------
        entry("今天买了咖啡 花了15", 1_500, true, "餐饮", dayOffset = 0, note = "咖啡"),
        entry("地铁 6 块", 600, true, "交通", dayOffset = 0),
        entry("超市买菜 67.5", 6_750, true, "购物", dayOffset = 0),
        entry("奶茶 18", 1_800, true, "餐饮", dayOffset = 0),
        entry("加油 300", 30_000, true, "交通", dayOffset = 0),
        entry("交电费 180", 18_000, true, "住房", dayOffset = 0),
        entry("药房买药 32", 3_200, true, "医疗", dayOffset = 0),
        entry("买了本教材 58", 5_800, true, "教育", dayOffset = 0),
        entry("订阅了腾讯视频会员 25", 2_500, true, "娱乐", dayOffset = 0),
        entry("便利店买水 3.5", 350, true, "购物", dayOffset = 0),
        entry("今天买了一个鸡公煲的团购 花了30", 3_000, true, "餐饮", dayOffset = 0),
        entry("房租 3500", 350_000, true, "住房", dayOffset = 0),

        // ---------- 相对日期：这类最容易错，也最值得测 ----------
        entry("昨天打车 23", 2_300, true, "交通", dayOffset = -1),
        entry("前天午饭 22", 2_200, true, "餐饮", dayOffset = -2),
        entry("昨天看电影 45", 4_500, true, "娱乐", dayOffset = -1),

        // ---------- 收入方向：模型容易把「到账」也记成支出 ----------
        entry("工资到账 12000", 1_200_000, false, "工资", dayOffset = 0),
        entry("收到红包 200", 20_000, false, "奖金", dayOffset = 0),
        entry("稿费 1500", 150_000, false, "兼职", dayOffset = 0),

        // ---------- 一句话多笔：检验有没有只记第一笔 ----------
        AiEvalCase(
            input = "早餐 8 块，咖啡 15",
            expect = listOf(
                AiEvalExpectation(800, true, "餐饮", dayOffset = 0),
                AiEvalExpectation(1_500, true, "餐饮", dayOffset = 0, noteContains = "咖啡")
            )
        ),
        AiEvalCase(
            input = "打车 23 和 咖啡 15",
            expect = listOf(
                AiEvalExpectation(2_300, true, "交通", dayOffset = 0),
                AiEvalExpectation(1_500, true, "餐饮", dayOffset = 0)
            )
        ),

        // ---------- 反幻觉：这几句一个账目都不该产生 ----------
        AiEvalCase("你好", emptyList()),
        AiEvalCase("我这个月花了多少", emptyList())
    )

    private fun entry(
        input: String,
        amountCents: Long,
        isExpense: Boolean,
        category: String,
        dayOffset: Int? = null,
        note: String? = null
    ) = AiEvalCase(
        input = input,
        expect = listOf(AiEvalExpectation(amountCents, isExpense, category, dayOffset, note))
    )
}
