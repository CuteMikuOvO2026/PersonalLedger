package com.example.personalledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiPrompt] 的提示词构造测试 —— 纯函数，不需要网络与设备。
 *
 * 这里测的不是「提示词写得好不好」（那要靠评估集），而是**几条会导致功能静默失效的硬约束**：
 * 少一条，模型的行为就会以一种很难排查的方式变坏。
 */
class AiPromptTest {

    private val expense = listOf("餐饮", "交通", "购物", "其他")
    private val income = listOf("工资", "奖金")

    private fun prompt(
        expenseCategories: List<String> = expense,
        incomeCategories: List<String> = income,
        history: List<String> = emptyList()
    ) = AiPrompt.systemPrompt(FIXED_NOW, expenseCategories, incomeCategories, history)

    // ---------- DeepSeek JSON Output 的硬性要求 ----------

    @Test
    fun `提示词必须出现字面量 json 并给出完整示例`() {
        val text = prompt()

        // 官方要求：只设 response_format=json_object 而不在提示词里提到 json 时，
        // 模型可能一直吐空白直到耗尽 token，表现为「卡住」。
        assertTrue("必须出现字面量 json", text.contains("json"))
        assertTrue("必须给出完整输出示例", text.contains("\"entries\""))
        assertTrue(text.contains("\"reply\""))
    }

    @Test
    fun `必须注入时间锚点否则相对日期只能靠编`() {
        val text = prompt()

        assertTrue("缺少时间锚点", text.contains("【当前时间】"))
        // 锚点格式必须与解析器期望的一致（yyyy-MM-dd HH:mm），否则模型照着示例输出也会解析失败
        assertTrue(
            "锚点日期格式必须与 AiBookkeepingParser 期望的 yyyy-MM-dd HH:mm 一致",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(text)
        )
        // 星期几也要给，否则「上周五」这类说法没有参照
        assertTrue(
            "必须给出星期几",
            listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
                .any { text.contains(it) }
        )
    }

    @Test
    fun `必须逐个列出可用分类名`() {
        val text = prompt()

        // 账本只存分类名、没有外键，不列出来模型就只能凭空造，然后被收敛成「其他」
        expense.forEach { assertTrue("缺少支出分类 $it", text.contains(it)) }
        income.forEach { assertTrue("缺少收入分类 $it", text.contains(it)) }
    }

    // ---------- 分类判定要点：必须跟着实际清单走 ----------

    @Test
    fun `清单里没有转账时不得出现转账的判定要点`() {
        // 这是关键约束：分类清单里没有「转账」的用户，如果提示词仍写着「转账：红包、他人转账……」，
        // 模型会以为存在这个选项并选中它，然后被解析器收敛成「其他」——
        // 用户看到的是「我说了红包，怎么记成其他」，而那句说明反而成了误导。
        val text = prompt(incomeCategories = listOf("工资", "奖金"))

        assertFalse("清单外分类不得出现在提示词里", text.contains("转账"))
    }

    @Test
    fun `清单里有转账时才输出转账的判定要点`() {
        val text = prompt(incomeCategories = listOf("工资", "奖金", "转账"))

        assertTrue(text.contains("转账"))
        assertTrue("要说明红包属于转账", text.contains("红包"))
    }

    @Test
    fun `购物与餐饮并存时给出地点优先的判定顺序`() {
        val text = prompt(expenseCategories = listOf("餐饮", "购物", "其他"))

        // 实测「超市买菜 67.5」「便利店买水 3.5」连续多次被记成餐饮，正确答案是购物。
        // 模型按「买的东西能不能吃」判，而账本习惯按「发生在哪里」判。
        assertTrue(text.contains("超市"))
        assertTrue(text.contains("便利店"))
        assertTrue("必须点明判断顺序", text.contains("判断顺序"))
    }

    @Test
    fun `只有购物没有餐饮时不输出无意义的判定顺序`() {
        val text = prompt(expenseCategories = listOf("购物", "其他"))

        assertTrue(text.contains("购物"))
        assertFalse("没有餐饮就没有「先看地点还是先看东西」的歧义", text.contains("判断顺序"))
        // 注意不能断言「不含餐饮」：输出格式的 JSON 示例里固定写着 "category": "餐饮"，
        // 那是格式示范，与分类清单无关。要断言的是**判定要点**里没有它。
        assertFalse(
            "没有餐饮时不应给出餐饮的判定要点",
            text.contains("餐饮：餐厅、外卖、咖啡")
        )
    }

    // ---------- 缩进：插值不得破坏 trimIndent ----------

    @Test
    fun `多行插值不得让提示词每行残留缩进`() {
        // 真实踩过的坑：多行文本自带零缩进，会把 trimIndent 的「公共最小缩进」拉到 0，
        // 于是整份提示词每行都残留 12 个空格，没法逐行核对。占位符方案必须真的生效。
        val text = prompt(history = listOf("咖啡 15", "打车 23"))

        val indented = text.lines().filter { it.startsWith("            ") }
        assertTrue("不应有整行残留 12 空格缩进：$indented", indented.isEmpty())
    }

    @Test
    fun `提示词里不应残留未替换的占位符`() {
        val text = prompt(history = listOf("咖啡 15"))

        assertFalse("HISTORY_SLOT 未被替换", text.contains("@@HISTORY@@"))
        assertFalse("CATEGORY_SLOT 未被替换", text.contains("@@CATEGORY@@"))
    }

    // ---------- 历史背景 ----------

    @Test
    fun `历史必须以背景块加禁令的形式注入`() {
        val text = prompt(history = listOf("咖啡 15"))

        // 只靠消息角色区分是不够的 —— 那正是「用户只说了一句咖啡15，却把前面几轮
        // 说过的话全部记成账」的成因。历史必须在**文字上**就被标明是背景。
        assertTrue(text.contains("【历史背景】"))
        assertTrue(text.contains("- 咖啡 15"))
        assertTrue("必须配一条明确禁令", text.contains("不要再为它们生成任何账目"))
    }

    @Test
    fun `没有历史时不产生历史块`() {
        val text = prompt(history = emptyList())

        assertFalse(text.contains("【历史背景】"))
        assertFalse(text.contains("不要再为它们生成任何账目"))
    }

    @Test
    fun `反幻觉禁令始终存在`() {
        // 这是这个功能最危险的失效模式：用户说了一句话，账本里多出两笔没提过的支出
        val text = prompt()

        assertTrue(text.contains("绝对不要编造用户没有提到的账目"))
        assertTrue("非记账语句要能返回空数组", text.contains("[]"))
    }

    private companion object {
        /** 固定「现在」，让日期锚点可断言。对应 2025-10-02（星期四）14:40 本地时间。 */
        const val FIXED_NOW = 1_757_380_800_000L
    }
}
