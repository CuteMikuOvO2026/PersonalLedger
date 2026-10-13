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

    /**
     * 评估使用的**固定**分类清单（fixture）。
     *
     * 为什么写死在这里，而不是读 [DefaultCategories] 或用户自定义分类：
     * - **读用户自定义分类** → 指标随设备数据漂移。真实发生过：「收到红包 200」在一台设备上
     *   被判成「其他」、在另一台上被判成「转账」，因为后者加过一个叫「转账」的分类。
     *   同一份代码、同一个模型跑出两个准确率，**这样的指标不能写进简历，也不能用来比较两次改动**。
     * - **读 [DefaultCategories]** → 内置清单一旦增删，历史指标就再也无法与新结果对比。
     *
     * 所以评估清单在这里**冻结**：它与 [cases] 的期望值是一个整体，改动其中任何一个都会让
     * 新旧数字失去可比性 —— 请把两者当成同一份基准一起看待。
     *
     * 清单里含「转账」：它是本应用真实在用的收入分类，评估必须覆盖到，
     * 否则「收到红包 200」这条用例永远不可能通过（模型看不到这个选项，只能落到兜底值）。
     * 这正说明「评估要隔离设备数据」不等于「评估要砍掉自定义分类」——
     * 前者要求**可复现**，后者要求**贴近真实**，冻结一份清单同时满足两者。
     */
    val expenseCategories: List<String> = listOf(
        "餐饮", "交通", "购物", "娱乐", "医疗", "教育", "住房", "其他"
    )

    val incomeCategories: List<String> = listOf(
        "工资", "奖金", "投资", "兼职", "转账"
    )

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
        // 期望「转账」：红包到账是**他人转入**，不是奖励性质的「奖金」。这个区分由使用者确认过，
        // 因此它属于「有共识」的用例，可以留在评估集里。
        entry("收到红包 200", 20_000, false, "转账", dayOffset = 0),
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

    // ==================================================================================
    // 扩展集（[extendedCases]）：用来**验证主集上的成绩是不是真的**
    // ==================================================================================

    /**
     * 扩展集 —— 独立于 [cases]，**刻意不并入主集**。
     *
     * 为什么单独一份：主集的 22 条有完整的运行历史（见 README 的对比表），往里面加用例会让
     * 新旧数字失去可比性。而这一份的用途完全不同 —— 它是一次**过拟合检查**。
     *
     * 起因：主集从 19/22 提升到 22/22（100%）之后，必须回答一个更难的问题 ——
     * **这个 100% 是真的学会了一条规则，还是只是把那三句错题背下来了？**
     * 改提示词时是看着那三条失败用例写的（「超市买菜」「便利店买水」都归购物），
     * 这在方法论上叫**测试集污染**：改完再跑同一批用例，涨分是必然的，不构成证据。
     *
     * 因此这份扩展集包含三类用例，缺一不可：
     *
     * 1. **同规则、新表述**（[cases] 里没出现过的商家与说法）——
     *    检验「地点优先」这条规则是否泛化到了新模式，而不只是那两句。
     * 2. **对照组：真正的餐饮** —— 检验规则**没有过度泛化**。只知道「超市→购物」是不够的，
     *    还得确认「面馆吃面」没被误判成购物。**只测前者会漏掉过度纠正这种失效**，
     *    而过度纠正往往比原来的错误更糟：它会静默地把正确的账记错。
     * 3. **新维度**：三笔一句、更远的相对日期、还款 / 挂号 / 物业费这类新分类边界。
     *
     * 判读方式：两类用例的通过率要**分开看**。
     * - 同规则组高、对照组也高 → 规则真的泛化了，可以放心写进简历；
     * - 同规则组高、对照组低 → 规则写过头了，把餐饮也吞进了购物；
     * - 两组都低 → 主集那 100% 是背题背出来的，提示词改动没有泛化。
     */
    val extendedCases: List<AiEvalCase> = listOf(
        // ---------- 第 1 类：同规则（地点优先）、全新表述 ----------
        // 主集里出现的是「超市」「便利店」，这里换成从没测过的商家与说法
        entry("沃尔玛买洗衣液 89", 8_900, true, "购物", dayOffset = 0),
        entry("菜市场买排骨 42", 4_200, true, "购物", dayOffset = 0),
        entry("盒马买了点水果 55.8", 5_580, true, "购物", dayOffset = 0),
        entry("便利店买瓶酸奶 6", 600, true, "购物", dayOffset = 0),
        entry("永辉超市采购 156", 15_600, true, "购物", dayOffset = 0),

        // ---------- 第 2 类：对照组，真正的餐饮绝不能被吞掉 ----------
        // 这几句是「地点优先」规则最容易误伤的：它们都在「吃」，但发生在餐饮场所
        entry("楼下面馆吃了碗面 18", 1_800, true, "餐饮", dayOffset = 0),
        entry("点了份外卖 32", 3_200, true, "餐饮", dayOffset = 0),
        entry("星巴克拿铁 35", 3_500, true, "餐饮", dayOffset = 0),
        entry("中午食堂吃饭 15", 1_500, true, "餐饮", dayOffset = 0),

        // ---------- 第 3 类：新维度 ----------
        // 三笔一句：主集最多两笔，这里检验条数上限
        AiEvalCase(
            input = "早饭 6，打车 18，晚上看电影 40",
            expect = listOf(
                AiEvalExpectation(600, true, "餐饮", dayOffset = 0),
                AiEvalExpectation(1_800, true, "交通", dayOffset = 0),
                AiEvalExpectation(4_000, true, "娱乐", dayOffset = 0)
            )
        ),
        // 更远的相对日期：主集只测到「前天」
        entry("三天前理发 45", 4_500, true, "其他", dayOffset = -3),
        // 收入侧的新边界
        entry("朋友还我钱 500", 50_000, false, "转账", dayOffset = 0),
        // 其他分类边界
        entry("医院挂号 50", 5_000, true, "医疗", dayOffset = 0),
        entry("交物业费 320", 32_000, true, "住房", dayOffset = 0),

        // ---------- 反幻觉：换两句没测过的说法 ----------
        AiEvalCase("谢谢", emptyList()),
        AiEvalCase("帮我看看这个月餐饮花了多少", emptyList())
    )
}
