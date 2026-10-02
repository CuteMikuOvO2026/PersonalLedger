package com.example.personalledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * AI 助手对话的读写。
 *
 * 刻意不提供「按条件分页 / 搜索」：对话是一段连续的时间线，界面永远从头读到尾，
 * 记录量也在几百条量级，一次读完即可（与 `ledger_entries` 必须分页的情况不同）。
 */
@Dao
interface ChatMessageDao {

    /** 全部对话，按写入顺序（自增主键即写入顺序）。 */
    @Query("SELECT * FROM chat_messages ORDER BY id ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    /** @return 新行的 id。 */
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /**
     * 「确认 / 忽略」时占用草稿：把 `draftJson` 置空并改写展示文本。
     *
     * **返回受影响行数是本方法的关键**：条件里的 `draftJson IS NOT NULL` 让这次更新成为一次
     * 原子的「占位」——两次并发调用只有一次能拿到 1，另一次拿到 0。调用方据此判断
     * 「这笔是不是已经被处理过了」，从根源上杜绝连点两次「确认记账」导致重复入账。
     */
    @Query("UPDATE chat_messages SET content = :content, draftJson = NULL WHERE id = :id AND draftJson IS NOT NULL")
    suspend fun consumeDraft(id: Long, content: String): Int

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}
