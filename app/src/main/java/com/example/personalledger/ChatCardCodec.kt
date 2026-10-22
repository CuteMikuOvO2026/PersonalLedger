package com.example.personalledger

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken

/**
 * 卡片与 JSON 的互转 —— 纯函数，可在 JVM 上直接测。
 *
 * 为什么需要它：卡片要跟着对话一起持久化（关掉 App 再回来，待确认的修改/删除还在），
 * 而 Room 那一列是 TEXT。改造前它只存 `List<ParsedEntry>`（新建草稿），现在要装四类卡片，
 * 于是引入一个带 `kind` 判别字段的信封。
 *
 * **为什么不上 Room 迁移**：`draftJson` 是 TEXT，语义是「这条消息挂着什么待办」，
 * 四类卡片都只是这个语义的不同取值，列本身不需要变。加一列 `actionJson` 就要升 schema 到 v5、
 * 补迁移、补 schema 快照 —— 为一个纯展示用的载荷付出这个代价不值。代价是这一列的名字
 * （`draftJson`）比它现在承载的东西窄了一点，故在此说明。
 *
 * **向后兼容**：老版本存的是裸数组（`[{...}]`），[decode] 认得出来并当作 [ChatCard.NewEntries]。
 * 升级时用户手上那张没确认的记账卡片不会丢。
 *
 * 解码是**宽容**的：任何一处不合法（缺字段、id 为空、类型对不上）都只丢掉那一部分，
 * 整体退化成 `null`（消息变成一条普通文本），绝不抛异常 —— 一段坏 JSON 不该让整个助手页打不开。
 */
object ChatCardCodec {

    private const val KIND_NEW = "new"
    private const val KIND_MODIFY = "modify"
    private const val KIND_REMOVE = "remove"
    private const val KIND_ANSWER = "answer"

    private val gson = Gson()

    /** 老格式：裸的 `List<ParsedEntry>`。 */
    private val legacyType = object : TypeToken<List<ParsedEntry>>() {}.type

    fun encode(card: ChatCard?): String? = when (card) {
        null -> null

        is ChatCard.NewEntries -> {
            if (card.entries.isEmpty()) null
            else gson.toJson(
                CardDto(kind = KIND_NEW, entries = card.entries.map { it.toDto() })
            )
        }

        is ChatCard.Modify -> gson.toJson(
            CardDto(
                kind = KIND_MODIFY,
                before = card.before.toDto(),
                after = card.after.toDto()
            )
        )

        is ChatCard.Remove -> {
            if (card.entries.isEmpty()) null
            else gson.toJson(CardDto(kind = KIND_REMOVE, entries = card.entries.map { it.toDto() }))
        }

        is ChatCard.Answer -> {
            if (card.lines.isEmpty()) null
            else gson.toJson(CardDto(kind = KIND_ANSWER, title = card.title, lines = card.lines))
        }
    }

    fun decode(json: String?): ChatCard? {
        val text = json?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 老格式先试：它以 `[` 开头，新版信封一定以 `{` 开头，两者不会混淆
        if (text.startsWith("[")) return decodeLegacy(text)

        val dto = try {
            gson.fromJson(text, CardDto::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return null

        return when (dto.kind) {
            KIND_NEW -> dto.entries.orEmpty()
                .mapNotNull { it.toParsedEntry() }
                .takeIf { it.isNotEmpty() }
                ?.let { ChatCard.NewEntries(it) }

            KIND_MODIFY -> {
                val before = dto.before?.toEntryRef()
                val after = dto.after?.toParsedEntry()
                if (before != null && after != null) ChatCard.Modify(before, after) else null
            }

            KIND_REMOVE -> dto.entries.orEmpty()
                .mapNotNull { it.toEntryRef() }
                .takeIf { it.isNotEmpty() }
                ?.let { ChatCard.Remove(it) }

            KIND_ANSWER -> {
                val lines = dto.lines.orEmpty().filter { it.isNotBlank() }
                if (lines.isEmpty()) null
                else ChatCard.Answer(title = dto.title.orEmpty(), lines = lines)
            }

            else -> null
        }
    }

    private fun decodeLegacy(text: String): ChatCard? = try {
        val entries = gson.fromJson<List<ParsedEntry>>(text, legacyType).orEmpty()
        entries.takeIf { it.isNotEmpty() }?.let { ChatCard.NewEntries(it) }
    } catch (_: Exception) {
        null
    }

    // ---------- 内部 DTO ----------

    /**
     * 全部字段可空：Gson 反序列化 Kotlin data class 时绕过构造器与默认值
     * （与 [LedgerRepository.BackupData] 同一个坑），缺失字段就是 `null`，
     * 因此这里的「可空」是真实的运行时状态，每个字段都必须显式判空。
     */
    private data class CardDto(
        val kind: String? = null,
        val entries: List<EntryDto>? = null,
        val before: EntryDto? = null,
        val after: EntryDto? = null,
        val title: String? = null,
        val lines: List<String>? = null
    )

    private data class EntryDto(
        val id: String? = null,
        val amountCents: Long? = null,
        val isExpense: Boolean? = null,
        val categoryName: String? = null,
        val timeMillis: Long? = null,
        val note: String? = null
    )

    private fun ParsedEntry.toDto(): EntryDto = EntryDto(
        id = null,
        amountCents = amountCents,
        isExpense = isExpense,
        categoryName = categoryName,
        timeMillis = timeMillis,
        note = note
    )

    private fun EntryRef.toDto(): EntryDto = EntryDto(
        id = id,
        amountCents = amountCents,
        isExpense = isExpense,
        categoryName = categoryName,
        timeMillis = timeMillis,
        note = note
    )

    private fun EntryDto.toParsedEntry(): ParsedEntry? {
        val cents = amountCents ?: return null
        val time = timeMillis ?: return null
        if (cents <= 0) return null
        return ParsedEntry(
            amountCents = cents,
            isExpense = isExpense ?: true,
            categoryName = categoryName.orEmpty(),
            timeMillis = time,
            note = note.orEmpty()
        )
    }

    private fun EntryDto.toEntryRef(): EntryRef? {
        val key = id?.takeIf { it.isNotBlank() } ?: return null
        val cents = amountCents ?: return null
        val time = timeMillis ?: return null
        return EntryRef(
            id = key,
            amountCents = cents,
            isExpense = isExpense ?: true,
            categoryName = categoryName.orEmpty(),
            timeMillis = time,
            note = note.orEmpty()
        )
    }
}
