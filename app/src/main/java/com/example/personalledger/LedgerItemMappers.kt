package com.example.personalledger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [LedgerEntryEntity] 与界面模型 [LedgerItem] 之间的双向转换。
 *
 * 时间/金额的 SimpleDateFormat 按调用即时创建（线程安全），
 * 且这些转换都在后台线程执行，不会阻塞主线程。
 */
object LedgerItemMappers {

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

    fun entityToItem(entity: LedgerEntryEntity): LedgerItem = LedgerItem(
        id = entity.id,
        amountCents = entity.amountCents,
        note = entity.note,
        timeMillis = entity.timeMillis,
        isExpense = entity.isExpense,
        categoryName = entity.categoryName,
        categoryIconRes = entity.categoryIconRes
    )

    fun itemToEntity(item: LedgerItem): LedgerEntryEntity = LedgerEntryEntity(
        id = item.id,
        amountCents = item.amountCents,
        note = item.note,
        timeMillis = item.timeMillis,
        isExpense = item.isExpense,
        categoryName = item.categoryName,
        categoryIconRes = item.categoryIconRes
    )

    fun formatMillis(millis: Long): String =
        SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))

    fun parseTimeMillis(time: String): Long = try {
        SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).parse(time)?.time ?: System.currentTimeMillis()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}
