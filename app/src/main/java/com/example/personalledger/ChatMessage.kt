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
 * [card] 非空表示这条助手消息挂了一张卡片 —— 可能是**待确认**的账目 / 修改 / 删除，
 * 也可能是**只读**的统计结果（见 [ChatCard.pending]）。它同时是「还有没有待办」的唯一判据，
 * 因此不需要再单独存一个状态字段（见 [ChatMessageEntity.draftJson]）。
 * 用户确认或忽略之后，该消息会被改写为普通文本，[card] 随之变成 null。
 */
data class ChatMessage(
    val id: Long = 0L,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val card: ChatCard? = null
) {
    /** 是否需要在气泡里渲染「确认 / 忽略」按钮。只读的统计卡片不显示按钮。 */
    val hasPendingCard: Boolean get() = card?.pending == true
}
