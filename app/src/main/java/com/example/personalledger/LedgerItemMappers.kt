package com.example.personalledger

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

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
        amount = String.format(Locale.getDefault(), "%.2f", entity.amountCents / 100.0),
        note = entity.note,
        time = formatMillis(entity.timeMillis),
        isExpense = entity.isExpense,
        categoryName = entity.categoryName,
        categoryIconRes = entity.categoryIconRes
    )

    fun itemToEntity(item: LedgerItem): LedgerEntryEntity = LedgerEntryEntity(
        id = item.id,
        amountCents = (item.amount.toDoubleOrNull() ?: 0.0).let { (it * 100).roundToLong() },
        note = item.note,
        timeMillis = parseTimeMillis(item.time),
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
