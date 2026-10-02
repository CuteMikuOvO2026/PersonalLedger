package com.example.personalledger

/** 对话角色。[value] 是持久化到 [ChatMessageEntity.role] 的取值。 */
enum class ChatRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant");

    companion object {
        /** 认不出的取值一律当作用户消息：宁可把助手消息显示成用户消息，也不要凭空少一条。 */
        fun from(value: String?): ChatRole = entries.firstOrNull { it.value == value } ?: USER
    }
}

/**
 * 界面上的一条对话消息。
 *
 * [draft] 非空表示这条助手消息带了一张**待确认的账目卡片**——它同时是「有没有待确认事项」
 * 的唯一判据，因此不需要再单独存一个状态字段（见 [ChatMessageEntity.draftJson]）。
 * 用户确认或忽略之后，该消息会被改写为普通文本，[draft] 随之变成 null。
 */
data class ChatMessage(
    val id: Long = 0L,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val draft: List<ParsedEntry>? = null
) {
    /** 是否需要在气泡里渲染确认卡片。 */
    val hasDraft: Boolean get() = !draft.isNullOrEmpty()
}
