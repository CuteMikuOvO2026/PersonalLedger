package com.example.personalledger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * [CsvLedgerParser] 的 JVM 单元测试。
 *
 * 最关键的是 [toLedgerItems_roundTripsOwnExportFormat]：用与导出完全相同的格式造数据，
 * 再解析回来必须一致——否则「导出再导入」这条最常用的路径就会悄悄丢数据或错位。
 */
class CsvLedgerParserTest {

    private var previousLocale: Locale? = null

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    // ---------- 文本解析 ----------

    @Test
    fun parse_handlesQuotedFieldsContainingCommasAndNewlines() {
        val rows = CsvLedgerParser.parse("a,\"b,c\",d\n\"x\ny\",e,f")

        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b,c", "d"), rows[0])
        assertEquals(listOf("x\ny", "e", "f"), rows[1])
    }

    @Test
    fun parse_handlesEscapedDoubleQuotes() {
        val rows = CsvLedgerParser.parse("\"他说\"\"你好\"\"\",b")

        assertEquals(1, rows.size)
        assertEquals("他说\"你好\"", rows[0][0])
        assertEquals("b", rows[0][1])
    }

    @Test
    fun parse_stripsBomAndHandlesCrlf() {
        val rows = CsvLedgerParser.parse("\uFEFF时间,金额\r\n2026-09-16,25.50\r\n")

        assertEquals(2, rows.size)
        assertEquals("时间", rows[0][0])
        assertEquals(listOf("2026-09-16", "25.50"), rows[1])
    }

    @Test
    fun parse_skipsBlankLinesAndHandlesMissingTrailingNewline() {
        val rows = CsvLedgerParser.parse("a,b\n\n\nc,d")

        assertEquals(2, rows.size)
        assertEquals(listOf("c", "d"), rows[1])
    }

    // ---------- 表头识别与列映射 ----------

    @Test
    fun suggestMapping_recognisesOwnExportHeader() {
        val mapping = CsvLedgerParser.suggestMapping(listOf("时间", "类型", "分类", "金额", "备注"))

        assertNotNull(mapping)
        assertEquals(0, mapping!!.dateIndex)
        assertEquals(1, mapping.typeIndex)
        assertEquals(2, mapping.categoryIndex)
        assertEquals(3, mapping.amountIndex)
        assertEquals(4, mapping.noteIndex)
        assertTrue(mapping.isUsable)
    }

    @Test
    fun suggestMapping_recognisesEnglishHeaderInAnyOrder() {
        val mapping = CsvLedgerParser.suggestMapping(
            listOf("Category", "Amount", "Date", "Note", "Type")
        )

        assertNotNull(mapping)
        assertEquals(2, mapping!!.dateIndex)
        assertEquals(1, mapping.amountIndex)
        assertEquals(0, mapping.categoryIndex)
        assertEquals(3, mapping.noteIndex)
        assertEquals(4, mapping.typeIndex)
    }

    @Test
    fun suggestMapping_returnsNullWhenRequiredColumnsMissing() {
        // 只有分类和备注，缺时间与金额 → 无法导入
        assertNull(CsvLedgerParser.suggestMapping(listOf("分类", "备注")))
        // 有金额但没时间
        assertNull(CsvLedgerParser.suggestMapping(listOf("金额", "备注")))
    }

    @Test
    fun looksLikeHeader_distinguishesHeaderFromData() {
        assertTrue(CsvLedgerParser.looksLikeHeader(listOf("时间", "类型", "分类", "金额", "备注")))
        assertFalse(CsvLedgerParser.looksLikeHeader(listOf("2026-09-16 12:30", "支出", "餐饮", "25.50")))
    }

    // ---------- 字段解析 ----------

    @Test
    fun parseAmountCents_stripsCurrencySymbolsAndThousandsSeparators() {
        assertEquals(2550L, CsvLedgerParser.parseAmountCents("25.50"))
        assertEquals(2550L, CsvLedgerParser.parseAmountCents("¥25.50"))
        assertEquals(2550L, CsvLedgerParser.parseAmountCents("￥25.50"))
        assertEquals(123456L, CsvLedgerParser.parseAmountCents("1,234.56"))
        // 正负号交给「类型」列判断，这里只取绝对值
        assertEquals(2550L, CsvLedgerParser.parseAmountCents("-25.50"))
        assertEquals(2550L, CsvLedgerParser.parseAmountCents("+25.50"))
        assertEquals(0L, CsvLedgerParser.parseAmountCents("0"))
    }

    @Test
    fun parseAmountCents_returnsNullForGarbage() {
        assertNull(CsvLedgerParser.parseAmountCents(null))
        assertNull(CsvLedgerParser.parseAmountCents(""))
        assertNull(CsvLedgerParser.parseAmountCents("  "))
        assertNull(CsvLedgerParser.parseAmountCents("abc"))
        assertNull(CsvLedgerParser.parseAmountCents("¥"))
    }

    @Test
    fun parseTimeMillis_acceptsCommonFormats() {
        val expected = millis(2026, 9, 16, 12, 30)
        val expectedDateOnly = millis(2026, 9, 16, 0, 0)

        assertEquals(expected, CsvLedgerParser.parseTimeMillis("2026-09-16 12:30"))
        // 带秒的格式要保留秒，不能被截断
        assertEquals(expected + 45_000L, CsvLedgerParser.parseTimeMillis("2026-09-16 12:30:45"))
        assertEquals(expectedDateOnly, CsvLedgerParser.parseTimeMillis("2026-09-16"))
        assertEquals(expected, CsvLedgerParser.parseTimeMillis("2026/09/16 12:30"))
        assertEquals(expectedDateOnly, CsvLedgerParser.parseTimeMillis("2026/09/16"))
        assertEquals(expectedDateOnly, CsvLedgerParser.parseTimeMillis("2026.09.16"))
    }

    @Test
    fun parseTimeMillis_returnsNullForGarbage() {
        assertNull(CsvLedgerParser.parseTimeMillis(null))
        assertNull(CsvLedgerParser.parseTimeMillis(""))
        assertNull(CsvLedgerParser.parseTimeMillis("昨天"))
        // 非法日期不能被宽容解析成别的日子
        assertNull(CsvLedgerParser.parseTimeMillis("2026-13-45"))
    }

    @Test
    fun parseIsExpense_prefersTypeColumnThenFallsBackToSign() {
        assertTrue(CsvLedgerParser.parseIsExpense("支出", "25.50"))
        assertFalse(CsvLedgerParser.parseIsExpense("收入", "25.50"))
        assertTrue(CsvLedgerParser.parseIsExpense("Expense", "25.50"))
        assertFalse(CsvLedgerParser.parseIsExpense("income", "25.50"))

        // 类型列识别不出时看金额符号
        assertTrue(CsvLedgerParser.parseIsExpense("", "-25.50"))
        assertFalse(CsvLedgerParser.parseIsExpense("", "+25.50"))
        assertFalse(CsvLedgerParser.parseIsExpense("未知", "+25.50"))

        // 都没有明确信号时按支出处理
        assertTrue(CsvLedgerParser.parseIsExpense("", "25.50"))
        assertTrue(CsvLedgerParser.parseIsExpense("未知", null))
    }

    // ---------- 端到端：与导出格式对拍 ----------

    @Test
    fun toLedgerItems_roundTripsOwnExportFormat() {
        // 造一个与 LedgerRepository.getCsvString() 完全同构的文件
        val sourceItems = listOf(
            LedgerItem(
                id = "a",
                amountCents = 2550,
                note = "午饭",
                timeMillis = millis(2026, 9, 16, 12, 30),
                isExpense = true,
                categoryName = "餐饮"
            ),
            LedgerItem(
                id = "b",
                amountCents = 1200000,
                note = "含\"引号\"的备注",
                timeMillis = millis(2026, 9, 1, 9, 0),
                isExpense = false,
                categoryName = "工资"
            )
        )
        val csv = buildString {
            append('\uFEFF')
            append("时间,类型,分类,金额,备注\n")
            sourceItems.forEach { item ->
                val type = if (item.isExpense) "支出" else "收入"
                val escaped = item.note.replace("\"", "\"\"")
                append("${item.time},$type,${item.categoryName},\"${item.amount}\",\"$escaped\"\n")
            }
        }

        val rows = CsvLedgerParser.parse(csv)
        val mapping = CsvLedgerParser.suggestMapping(rows.first())
        assertNotNull(mapping)
        val result = CsvLedgerParser.toLedgerItems(rows.drop(1), mapping!!)

        assertEquals(0, result.skipped)
        assertEquals(2, result.items.size)

        result.items.zip(sourceItems).forEach { (parsed, source) ->
            assertEquals(source.amountCents, parsed.amountCents)
            assertEquals(source.note, parsed.note)
            assertEquals(source.timeMillis, parsed.timeMillis)
            assertEquals(source.isExpense, parsed.isExpense)
            assertEquals(source.categoryName, parsed.categoryName)
        }
    }

    @Test
    fun toLedgerItems_skipsUnparseableRowsAndCountsThem() {
        val mapping = CsvColumnMapping(
            dateIndex = 0,
            typeIndex = 1,
            categoryIndex = 2,
            amountIndex = 3,
            noteIndex = 4
        )
        val rows = listOf(
            listOf("2026-09-16 12:30", "支出", "餐饮", "25.50", "午饭"),
            listOf("不是日期", "支出", "餐饮", "25.50", "脏行"),
            listOf("2026-09-17 08:00", "支出", "餐饮", "abc", "金额是文字"),
            listOf("2026-09-18 08:00", "支出", "餐饮", "0", "金额为零"),
            listOf("2026-09-19 08:00", "收入", "工资", "100", "")
        )

        val result = CsvLedgerParser.toLedgerItems(rows, mapping)

        assertEquals(2, result.items.size)
        assertEquals(3, result.skipped)
    }

    @Test
    fun toLedgerItems_fallsBackToDefaultCategoryWhenBlank() {
        val mapping = CsvColumnMapping(0, 1, 2, 3, 4)
        val rows = listOf(listOf("2026-09-16 12:30", "支出", "  ", "25.50", ""))

        val result = CsvLedgerParser.toLedgerItems(rows, mapping, defaultCategory = "其他")

        assertEquals(1, result.items.size)
        assertEquals("其他", result.items[0].categoryName)
    }

    @Test
    fun toLedgerItems_worksWithoutOptionalColumns() {
        // 只有时间与金额两列（类型 / 分类 / 备注缺失），也要能导入
        val mapping = CsvColumnMapping(
            dateIndex = 0,
            typeIndex = -1,
            categoryIndex = -1,
            amountIndex = 1,
            noteIndex = -1
        )
        val rows = listOf(listOf("2026-09-16 12:30", "25.50"))

        val result = CsvLedgerParser.toLedgerItems(rows, mapping)

        assertEquals(1, result.items.size)
        assertEquals(2550L, result.items[0].amountCents)
        // 没有类型列 → 默认按支出
        assertTrue(result.items[0].isExpense)
        assertEquals("其他", result.items[0].categoryName)
        assertEquals("", result.items[0].note)
    }
}
