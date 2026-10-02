package com.example.personalledger

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToLong

/**
 * 解析出的一笔账目。[amountCents] 单位为「分」，与 [LedgerItem] 口径一致。
 *
 * 这个类型同时承担两个角色：AI 解析的中间结果，以及**待确认草稿**的序列化形态
 * （见 [ChatMessageEntity.draftJson]）。因此它必须能被 Gson 无损往返 —— 全是基础类型，
 * 没有默认值依赖。
 */
data class ParsedEntry(
    val amountCents: Long,
    val isExpense: Boolean,
    val categoryName: String,
    val timeMillis: Long,
    val note: String
)

/** 模型回复的解析结果：[entries] 为空表示这句话里没有账目（纯对话），[reply] 是要展示的助手回复。 */
data class AiReply(
    val entries: List<ParsedEntry>,
    val reply: String
)

/**
 * DeepSeek 返回内容的解析与校验 —— **纯函数**，不依赖任何 Android 类型（连 `R` 都不碰）。
 *
 * 与 [NotificationPaymentParser] 同样的取舍，理由也一样：这段代码直接决定「AI 记成什么」，
 * 金额算错、方向判反、分类不存在都会静默写进账本；而且它面对的是一个**会编造内容**的模型，
 * 比解析通知文本更需要可独立审阅。不碰 `R` 意味着可以在 JVM 上用一段真实模型输出直接复现。
 *
 * 分类只返回**名称**，图标由调用方走 [CategoryColors.iconResFor] 解析，
 * 与列表渲染、通知自动记账走同一条路径。
 *
 * 校验策略是「宁可少记，不可错记」：任何一项不满足就**丢掉那一笔**而不是猜一个值补齐。
 */
object AiBookkeepingParser {

    /** 未命中任何规则 / 分类名不在清单内时的兜底分类。与 [NotificationPaymentParser] 一致。 */
    const val DEFAULT_CATEGORY = DefaultCategories.FALLBACK

    /** 备注截断长度，与 [NotificationPaymentParser] 保持一致。 */
    private const val NOTE_MAX_LENGTH = 60

    private const val MIN_AMOUNT_CENTS = 1L

    /** 金额上限（100 万元）。挡的是模型把「15」写成「150000000」这类离谱幻觉。 */
    private const val MAX_AMOUNT_CENTS = 100_000_000L

    /** 解析出的年份合理区间，挡掉模型给出 1970 / 2099 之类的日期。 */
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2100

    private const val PATTERN_DATE_TIME = "yyyy-MM-dd HH:mm"
    private const val PATTERN_DATE_ONLY = "yyyy-MM-dd"

    private val gson = Gson()

    /**
     * 模型回复对应的 JSON 结构。
     *
     * 全部字段可空：Gson 反序列化 Kotlin data class 时绕过构造器与默认值
     * （与 [LedgerRepository.BackupData] 同一个坑），缺失字段会是 `null` 而不是声明处的默认值。
     * 因此这里的「可空」是**真实的运行时状态**，不是防御性冗余，每个字段都必须显式判空。
     */
    private data class RawReply(
        val entries: List<RawEntry>?,
        val reply: String?
    )

    private data class RawEntry(
        val amount: Double?,
        val isExpense: Boolean?,
        val category: String?,
        val note: String?,
        val dateTime: String?
    )

    /**
     * 代码块围栏，形如 ```` ```json ... ``` ````。
     *
     * 已经用 `response_format = json_object` 约束过输出，正常不会出现围栏；但模型偶尔仍会
     * 顺手包一层，这里兜住，避免整条回复因一个围栏被判失败。
     */
    private val CODE_FENCE = Regex("""^\s*```(?:json)?\s*|\s*```\s*$""", RegexOption.IGNORE_CASE)

    private val WHITESPACE = Regex("""\s+""")

    /** 剥掉可能的 Markdown 代码块围栏。 */
    fun stripCodeFences(raw: String): String =
        raw.replace(CODE_FENCE, "").trim()

    /**
     * 解析模型返回的内容。
     *
     * @param allowedCategories 允许的分类名（内置 + 自定义）。不在其中的分类名会被收敛到
     *   [DEFAULT_CATEGORY]，**绝不会**把模型编造的分类名写进账本（账本只存分类名、无外键，
     *   凭空多出的分类会渲染成兜底色和兜底图标，用户既选不到也删不掉）。
     * @param nowMillis 当前时间，用于兜住缺失 / 无法解析的 [RawEntry.dateTime]。
     * @return JSON 无法解析时返回 `null`（调用方据此提示「没听懂」并允许重试）。
     */
    fun parse(rawContent: String, allowedCategories: Set<String>, nowMillis: Long): AiReply? {
        val cleaned = stripCodeFences(rawContent)
        if (cleaned.isEmpty()) return null

        val raw = try {
            gson.fromJson(cleaned, RawReply::class.java)
        } catch (_: JsonSyntaxException) {
            return null
        } ?: return null

        val entries = raw.entries.orEmpty().mapNotNull { toEntry(it, allowedCategories, nowMillis) }
        val reply = raw.reply?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return AiReply(entries = entries, reply = reply)
    }

    /** 单笔账目的校验与换算；任何一项不合格返回 `null` 表示丢弃这一笔。 */
    private fun toEntry(raw: RawEntry, allowedCategories: Set<String>, nowMillis: Long): ParsedEntry? {
        val amount = raw.amount ?: return null
        if (!amount.isFinite() || amount <= 0) return null

        val cents = (amount * 100).roundToLong()
        if (cents < MIN_AMOUNT_CENTS || cents > MAX_AMOUNT_CENTS) return null

        return ParsedEntry(
            amountCents = cents,
            // 模型没给方向时按支出处理：与 NotificationPaymentParser.detectDirection 同一取舍
            // （平票按支出，误判成支出的代价更小），且用户能在确认卡片上当场改。
            isExpense = raw.isExpense ?: true,
            categoryName = normalizeCategory(raw.category, allowedCategories),
            timeMillis = parseDateTime(raw.dateTime, nowMillis),
            note = normalizeNote(raw.note)
        )
    }

    /** 分类名收敛：只认清单内的名字。 */
    private fun normalizeCategory(category: String?, allowedCategories: Set<String>): String {
        val name = category?.trim().orEmpty()
        return if (name.isNotEmpty() && name in allowedCategories) name else DEFAULT_CATEGORY
    }

    private fun normalizeNote(note: String?): String =
        note?.replace(WHITESPACE, " ")?.trim().orEmpty().take(NOTE_MAX_LENGTH)

    /**
     * 把模型给的本地时间字符串换算成 epoch 毫秒，支持两种粒度：
     * - `yyyy-MM-dd HH:mm`：精确到分钟；
     * - `yyyy-MM-dd`：只给了日期，时分沿用 [nowMillis] 的时刻。
     *
     * 空值 / 格式错误 / 年份离谱都回退到 [nowMillis]，而不是抛异常或记成 1970 年。
     *
     * `Locale.ROOT` 是刻意的：`SimpleDateFormat` 的历法来自 Locale，某些默认地区
     * （如泰语区的佛历）会把 `yyyy` 解析成偏移 543 年的年份。ROOT 对应公历，结果稳定。
     *
     * 注意这里**不使用** `setLenient(false)` 之外的宽容：宁可回退到「现在」并在确认卡片上
     * 显示出来让用户核对，也不要「猜」一个时间戳。
     */
    fun parseDateTime(dateTime: String?, nowMillis: Long): Long {
        val text = dateTime?.trim().orEmpty()
        if (text.isEmpty()) return nowMillis

        parseWith(PATTERN_DATE_TIME, text)?.let { millis ->
            if (isYearSane(millis)) return millis
        }

        parseWith(PATTERN_DATE_ONLY, text)?.let { dateMillis ->
            if (!isYearSane(dateMillis)) return nowMillis
            // 只给了日期：把「现在」的时分秒并进去，避免所有只写日期的记录都落在 00:00
            val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
            return Calendar.getInstance().apply {
                timeInMillis = dateMillis
                set(Calendar.HOUR_OF_DAY, now.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, now.get(Calendar.MINUTE))
                set(Calendar.SECOND, now.get(Calendar.SECOND))
                set(Calendar.MILLISECOND, now.get(Calendar.MILLISECOND))
            }.timeInMillis
        }

        return nowMillis
    }

    private fun parseWith(pattern: String, text: String): Long? = try {
        SimpleDateFormat(pattern, Locale.ROOT).apply { isLenient = false }.parse(text)?.time
    } catch (_: Exception) {
        null
    }

    private fun isYearSane(millis: Long): Boolean {
        val year = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)
        return year in MIN_YEAR..MAX_YEAR
    }
}
