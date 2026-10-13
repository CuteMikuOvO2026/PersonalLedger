package com.example.personalledger

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 系统提示词的构造 —— 纯函数。
 *
 * 几条硬性约束决定了这里的写法，改动前务必先读懂：
 *
 * 1. **必须出现字面量单词 `json`，并且给出完整的输出示例。** DeepSeek 的 JSON Output
 *    文档明确要求如此：只设 `response_format = json_object` 而不在提示词里提到 json 时，
 *    模型可能一直吐空白直到耗尽 token，表现为「卡住」。
 * 2. **必须注入当前日期时间。** 用户说的是「今天 / 昨天 / 上周五」，而模型本身不知道今天
 *    是几号。不注入锚点，相对日期只能靠编。
 * 3. **必须把可用分类名逐个列出来。** 账本只存分类名、没有外键，模型凭空造一个分类名会被
 *    [AiBookkeepingParser] 收敛成「其他」——列出来才能真正用上「餐饮」「交通」这些分类。
 * 4. **历史发言必须以「背景」的形式出现，并配一条禁令。** 见 [historySection]。
 * 5. **历史块用占位符替换，不要直接插值。** 见 [HISTORY_SLOT]。
 *
 * 提示词里刻意写死了「不要编造用户没有提到的消费」：这是这个功能最危险的失效模式
 * （用户说了一句话，账本里多出两笔没提过的支出），而它恰恰是模型最容易犯的错。
 */
object AiPrompt {

    /** 注入给模型的时间锚点格式，与 [AiBookkeepingParser] 期望的输出格式一致。 */
    private const val PATTERN_ANCHOR = "yyyy-MM-dd HH:mm"

    /** 星期几也要给：否则「上周五」这类说法没有参照。 */
    private const val PATTERN_WEEKDAY = "EEEE"

    /**
     * 历史块的占位符。
     *
     * 为什么不直接把历史插值进模板：模板用 `trimIndent()` 统一去掉缩进，而**插进来的多行文本
     * 自带零缩进**，会把「公共最小缩进」拉到 0，于是整份提示词每行都残留 12 个空格。
     * 模型虽然不在乎这点空白，但提示词会变得没法阅读、也没法逐行核对（我验证提示词内容时
     * 就依赖逐行比对）。
     *
     * 占位符是**单行**的，`trimIndent()` 先正常完成，之后再用 `replace` 填入真正的历史块。
     */
    private const val HISTORY_SLOT = "@@HISTORY@@"

    /**
     * 分类判定要点的占位符。与 [HISTORY_SLOT] 同样的理由：要点是多行文本，直接插值会把
     * 整份提示词的公共最小缩进拉到 0，让每一行都残留 12 个空格 —— 提示词就没法逐行核对了。
     */
    private const val CATEGORY_SLOT = "@@CATEGORY@@"

    /**
     * @param nowMillis 当前时间，用于锚定「今天 / 昨天」这类相对说法。
     * @param expenseCategories 允许的支出分类名（内置 + 自定义 + 兜底的「其他」）。
     * @param incomeCategories 允许的收入分类名（内置 + 自定义）。
     * @param recentUserInputs 用户**之前**说过的原话，仅作为理解指代的背景，**不应被解析**。
     */
    fun systemPrompt(
        nowMillis: Long,
        expenseCategories: List<String>,
        incomeCategories: List<String>,
        recentUserInputs: List<String> = emptyList()
    ): String {
        val now = SimpleDateFormat(PATTERN_ANCHOR, Locale.ROOT).format(nowMillis)
        val weekday = SimpleDateFormat(PATTERN_WEEKDAY, Locale.CHINESE).format(nowMillis)

        val template = """
            你是「轻账」（一款个人记账 App）的记账助手。你的唯一任务是把用户的中文自然语言
            转成一笔或多笔账目，并且**只输出一个 json 对象**，不要输出任何解释、前言或 Markdown 代码块。

            【当前时间】
            $now $weekday

            【允许的支出分类】
            ${expenseCategories.joinToString("、")}

            【允许的收入分类】
            ${incomeCategories.joinToString("、")}

            ${CATEGORY_SLOT}
            ${HISTORY_SLOT}
            【输出格式】
            {
              "entries": [
                {
                  "amount": 15,
                  "isExpense": true,
                  "category": "餐饮",
                  "note": "咖啡",
                  "dateTime": "2026-10-02 14:30"
                }
              ],
              "reply": "已解析出 1 笔支出"
            }

            【字段规则】
            - amount：金额，单位为元，必须是正数。方向由 isExpense 表示，不要把支出写成负数。
            - isExpense：true 表示支出，false 表示收入。
            - category：必须严格取自上方分类清单里的名称，一个字都不要改。实在无法归类时用「其他」。
              绝对不要发明清单以外的分类名。
            - note：简短描述，例如「咖啡」「打车」「地铁」。不要包含金额，控制在 20 个字以内。
            - dateTime：格式固定为 "YYYY-MM-DD HH:mm"。必须结合上方【当前时间】把「今天」「昨天」
              「前天」「上周五」这类相对说法换算成具体日期。「只说了日期没说是几点」时用当前时刻。
              用户完全没提到时间时输出 null。
            - reply：一句简短中文，说明你解析出了什么。

            【其他要求】
            - **只解析【本次输入】里那一段话**。一句话里包含多笔账目时，在 entries 里输出多条，
              不要只记第一笔。
            - 如果用户不是要记账（打招呼、提问、闲聊、问统计），把 entries 输出为空数组 []，
              并把你的回答放在 reply 里。
            - **绝对不要编造用户没有提到的账目**。宁可 entries 为空，也不要凭空多记一笔。
        """.trimIndent()

        return template
            .replace(CATEGORY_SLOT, categoryGuidance(expenseCategories, incomeCategories))
            .replace(HISTORY_SLOT, historySection(recentUserInputs))
    }

    /**
     * 分类判定要点：把最容易混的几组用**地点与性质**讲清楚。
     *
     * 为什么需要它：实测「超市买菜 67.5」与「便利店买水 3.5」连续四次被记成「餐饮」，
     * 而这两笔的正确答案都是「购物」。模型是按「买的东西能不能吃」判的，而账本的分类
     * 习惯是按「发生在哪里」判的 —— 这是一条只给分类名、不给判定口径就学不到的业务规则。
     *
     * **每条要点只在对应分类确实存在时才输出**。理由：分类清单里没有「转账」的用户
     * （那是自定义分类，不是每个人都有），如果提示词里仍写「转账：红包、他人转账……」，
     * 模型会以为存在这个选项并选中它，然后被 [AiBookkeepingParser] 收敛成「其他」——
     * 用户看到的是「我明明说了红包，怎么记成其他」，而提示词里那句说明反而成了误导。
     * 判断「该不该说」的依据必须是实际清单，不是想当然。
     */
    private fun categoryGuidance(expense: List<String>, income: List<String>): String {
        val rules = buildList {
            if ("餐饮" in expense) {
                add("- 餐饮：餐厅、外卖、咖啡、奶茶、食堂等**即食消费**。")
            }
            if ("购物" in expense) {
                add(
                    "- 购物：超市、便利店、菜市场、商场等**零售采购**。在这类场所买的东西即使\n" +
                        "  能吃能喝（买菜、买水、买零食），也一律归「购物」，**不要归餐饮**。"
                )
            }
            if ("交通" in expense) {
                add("- 交通：地铁、公交、打车、加油、停车、高速费。")
            }
            if ("转账" in income) {
                add("- 转账：红包、他人转账、借款还款等**资金往来**，既不是工资也不是奖金。")
            }
            // 判断顺序单独成条：它是上面各条的用法，不是某个分类的定义
            if ("购物" in expense && "餐饮" in expense) {
                add("- 判断顺序：先看**发生在哪里**（超市/便利店 → 购物），再看买的是什么。")
            }
        }
        if (rules.isEmpty()) return ""
        return "\n【分类判定要点】（这几组最容易混，按地点和性质判断，不要按买的东西判断）\n" +
            rules.joinToString("\n") + "\n"
    }

    /**
     * 把历史发言包装成**显式的背景块**，并附一条明确禁令。
     *
     * 为什么不能把历史当作普通的 `user` 消息发出去：那样模型看到的是「好几条并列的用户请求」，
     * 而它的任务是「把用户的话转成账目」—— 于是会把历史里的每一句都重新解析一遍。
     * 真实表现就是：用户只说「咖啡15」，助手却把前面几轮说过的话**全部**记成了账。
     *
     * 所以历史必须在**文字上**就被标明是背景，并配一条禁令。只靠消息角色区分是不够的
     * ——那正是出问题的写法。
     *
     * 没有历史时返回空串，模板里的占位符整行会被替换掉（`trimIndent` 已先行完成，因此
     * 留下一个空行，对提示词没有影响）。
     *
     * ⚠️ **这里同样不能用插值插入多行的 `$quoted`**：那会把本段 `trimIndent()` 的公共最小缩进
     * 拉到 0，于是「【历史背景】」与「⚠️」两行各残留 12 个空格、而中间的历史行没有缩进。
     * 外层模板早就修过这个坑（见 [HISTORY_SLOT]），但内层这一段漏了 —— 是单元测试
     * （`AiPromptTest.多行插值不得让提示词每行残留缩进`）把它翻出来的。
     * 提示词缩进乱掉不影响模型理解，但会让「逐行核对提示词」这件事没法做。
     */
    private fun historySection(recentUserInputs: List<String>): String {
        if (recentUserInputs.isEmpty()) return ""
        val quoted = recentUserInputs.joinToString("\n") { "- $it" }
        val block = """
            【历史背景】（用户**之前**说过的话，仅供理解「刚才那笔」「它」这类指代）
            @@QUOTED@@
            ⚠️ 以上历史内容**都已经处理过了，不要再为它们生成任何账目**。
        """.trimIndent()
        return block.replace("@@QUOTED@@", quoted)
    }
}
