package com.example.personalledger

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 助手对话消息的 Room 实体（schema v4 新增）。
 *
 * **不加额外索引**：`id` 是自增主键，SQLite 里自增 INTEGER PRIMARY KEY 就是 rowid，
 * `ORDER BY id` 天然有序、无需临时排序；对话量级（几十到几百条）也用不上索引。
 * 少一个索引，迁移时就要少对齐一个对象。
 *
 * [draftJson] 承载助手消息挂的**卡片**（[ChatCard] 的 JSON 信封，由 [ChatCardCodec] 编解码）。
 * 它的非空性本身就是状态：**非空 ⟺ 这条消息还挂着一张卡片**。用户点「确认」或「忽略」后
 * 统一置为 NULL（见 [ChatMessageDao.consumeDraft]），因此不需要额外的状态列，
 * 也不会出现「状态列与卡片内容互相矛盾」的中间态。
 *
 * 列名保留 `draftJson`（叫 `cardJson` 更准确）是刻意的：改名要升 schema 到 v5、补迁移、
 * 补 schema 快照，而这一列的类型（TEXT）与语义（「挂着什么待办」）都没变。
 * 老版本存的是裸数组，[ChatCardCodec] 仍能解码成新建卡片，用户手上没确认的记账不会丢。
 *
 * 与 [LedgerEntryEntity] 的区别：助手消息**只有助手消息**带卡片，用户消息的该列为 NULL，
 * 这在 SQLite 里是普通的可空列。
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** [ChatRole.USER] / [ChatRole.ASSISTANT] 的持久化取值。 */
    val role: String,
    /** 展示文本：用户原话，或助手的回复 / 错误说明。 */
    val content: String,
    /** 写入时间（epoch 毫秒），仅用于排查与排序兜底。 */
    val createdAt: Long,
    /** 卡片的 JSON 信封；NULL 表示这条消息没有卡片，或卡片已经被确认 / 忽略。 */
    val draftJson: String?
)
