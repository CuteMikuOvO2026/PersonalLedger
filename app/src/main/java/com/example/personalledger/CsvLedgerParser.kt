package com.example.personalledger

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** CSV 的列映射：每一列在文件中的下标；-1 表示该字段不存在。 */
data class CsvColumnMapping(
    val dateIndex: Int,
    val typeIndex: Int,
    val categoryIndex: Int,
    val amountIndex: Int,
    val noteIndex: Int
) {
    /** 时间与金额是导入的必需列，缺任意一个都无法导入。 */
    val isUsable: Boolean get() = dateIndex >= 0 && amountIndex >= 0
}

/** CSV 导入结果：成功转换的账目 + 被跳过的行数（时间/金额无法解析）。 */
data class CsvImportResult(
    val items: List<LedgerItem>,
    val skipped: Int
)

/**
 * 账本 CSV 的解析与转换（纯函数，不依赖 Android，便于单元测试）。
 *
 * 目标不只是「能读回自己导出的文件」，还要能接住其他记账 App 导出的 CSV，
 * 因此解析与「列含义」是分开的两步：
 * 1. [parse] 只负责把文本切成二维表；
 * 2. [suggestMapping] 依据表头关键词猜列；猜不准时由界面让用户手工指定。
 *
 * 字段解析刻意做得宽容：金额容忍 `¥` / `￥` / 千分位逗号与正负号；
 * 时间支持常见的几种格式；收支方向优先看「类型」列，识别不出时退回看金额正负。
 */
object CsvLedgerParser {

    private val DATE_KEYS = listOf("时间", "日期", "date", "time")
    private val TYPE_KEYS = listOf("类型", "收支", "type")
    private val CATEGORY_KEYS = listOf("分类", "类别", "category")
    private val AMOUNT_KEYS = listOf("金额", "amount", "money", "价格")
    private val NOTE_KEYS = listOf("备注", "说明", "note", "remark", "memo")

    private val INCOME_WORDS = listOf("收入", "income", "入账", "转入")
    private val EXPENSE_WORDS = listOf("支出", "expense", "消费")

    /** 由长到短排列：先匹配更具体的格式，避免 "yyyy-MM-dd" 抢先匹配掉带时分的文本。 */
    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
        "yyyy.MM.dd"
    )

    /**
     * 把 CSV 文本切成二维表。
     *
     * 支持：双引号包裹的字段（内部可含逗号与换行）、`""` 表示一个双引号、
     * CRLF / LF 换行、开头的 UTF-8 BOM（Excel 导出常见）、以及被忽略的空行。
     */
    fun parse(text: String): List<List<String>> {
        val content = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) rows.add(row)
            row = mutableListOf()
        }

        while (index < content.length) {
            val ch = content[index]
            when {
                inQuotes -> when {
                    ch == '"' -> {
                        if (index + 1 < content.length && content[index + 1] == '"') {
                            field.append('"')
                            index++
                        } else {
                            inQuotes = false
                        }
                    }

                    else -> field.append(ch)
                }

                ch == '"' -> inQuotes = true
                ch == ',' -> endField()
                ch == '\r' -> Unit // 交给 \n 收尾，兼容 CRLF
                ch == '\n' -> endRow()
                else -> field.append(ch)
            }
            index++
        }
        // 文件末尾没有换行符时也要把最后一行收进来
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows
    }

    /** 依据表头关键词推断列映射；时间或金额找不到时返回 null，交由用户手工指定。 */
    fun suggestMapping(header: List<String>): CsvColumnMapping? {
        fun findIndex(keys: List<String>): Int = header.indexOfFirst { cell ->
            val normalized = cell.trim().lowercase()
            keys.any { normalized.contains(it) }
        }

        val mapping = CsvColumnMapping(
            dateIndex = findIndex(DATE_KEYS),
            typeIndex = findIndex(TYPE_KEYS),
            categoryIndex = findIndex(CATEGORY_KEYS),
            amountIndex = findIndex(AMOUNT_KEYS),
            noteIndex = findIndex(NOTE_KEYS)
        )
        return mapping.takeIf { it.isUsable }
    }

    /** 首行是否像表头（能被识别出时间与金额列即认为是表头，否则按纯数据行处理）。 */
    fun looksLikeHeader(row: List<String>): Boolean = suggestMapping(row) != null

    /**
     * 按 [mapping] 把数据行转换成账目。
     *
     * 时间或金额解析不出来的行会被**跳过并计数**（[CsvImportResult.skipped]），
     * 而不是整批失败——从别处导出的文件常有少量脏行，不该因此拒绝导入全部数据。
     */
    fun toLedgerItems(
        rows: List<List<String>>,
        mapping: CsvColumnMapping,
        defaultCategory: String = "其他"
    ): CsvImportResult {
        val items = mutableListOf<LedgerItem>()
        var skipped = 0

        rows.forEach { row ->
            val amountCents = parseAmountCents(row.getOrNull(mapping.amountIndex))
            val timeMillis = parseTimeMillis(row.getOrNull(mapping.dateIndex))
            if (amountCents == null || amountCents == 0L || timeMillis == null) {
                skipped++
                return@forEach
            }

            val rawAmount = row.getOrNull(mapping.amountIndex)
            val category = row.getOrNull(mapping.categoryIndex)
                ?.trim()
                .orEmpty()
                .ifEmpty { defaultCategory }

            items.add(
                LedgerItem(
                    id = java.util.UUID.randomUUID().toString(),
                    amountCents = amountCents,
                    note = row.getOrNull(mapping.noteIndex)?.trim().orEmpty(),
                    timeMillis = timeMillis,
                    isExpense = parseIsExpense(row.getOrNull(mapping.typeIndex).orEmpty(), rawAmount),
                    categoryName = category,
                    // 列表渲染会按分类名解析图标（CategoryColors.iconResFor），
                    // 因此这里不必（也无法）依赖 Android 资源，留 0 即可。
                    categoryIconRes = 0
                )
            )
        }

        return CsvImportResult(items = items, skipped = skipped)
    }

    /** 解析金额为「分」；容忍 `¥` / `￥` / 千分位逗号与正负号，无法解析返回 null。 */
    fun parseAmountCents(raw: String?): Long? {
        val cleaned = raw.orEmpty()
            .trim()
            .replace("¥", "")
            .replace("￥", "")
            .replace(",", "")
            .replace(" ", "")
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        // 正负号交给「类型」列判断，这里只取绝对值
        return (abs(value) * 100).roundToLong()
    }

    /**
     * 解析时间为 epoch 毫秒；逐个尝试常见格式，都不匹配返回 null。
     *
     * 注意必须校验「整串被完整消费」：`SimpleDateFormat.parse` 接受**部分匹配**，
     * 只校验开头能解析就返回结果。若不检查，`2026.09.16` 会被第一个
     * `yyyy-MM-dd HH:mm:ss` 匹配成「2026 年 1 月 1 日」，静默写错日期。
     */
    fun parseTimeMillis(raw: String?): Long? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) return null

        TIME_PATTERNS.forEach { pattern ->
            try {
                val format = SimpleDateFormat(pattern, Locale.getDefault()).apply { isLenient = false }
                val position = ParsePosition(0)
                val parsed = format.parse(text, position)
                if (parsed != null && position.index == text.length) return parsed.time
            } catch (_: Exception) {
                // 换下一个格式继续试
            }
        }
        return null
    }

    /**
     * 判断是否为支出。
     *
     * 优先看「类型」列的文字；识别不出（列缺失或写了别的词）时退回看金额符号，
     * 都没有明确信号时按支出处理（记账场景里支出占绝大多数）。
     */
    fun parseIsExpense(typeText: String, amountText: String?): Boolean {
        val type = typeText.trim().lowercase()
        if (type.isNotEmpty()) {
            if (INCOME_WORDS.any { type.contains(it) }) return false
            if (EXPENSE_WORDS.any { type.contains(it) }) return true
        }
        val amount = amountText.orEmpty().trim()
        return when {
            amount.startsWith("-") -> true
            amount.startsWith("+") -> false
            else -> true
        }
    }
}
