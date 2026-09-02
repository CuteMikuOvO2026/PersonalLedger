package com.example.personalledger

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * [LedgerItem] 的 Gson 序列化适配器，保证与旧格式（字段为 amount/time 字符串）双向兼容：
 * - 新格式：写入 amountCents / timeMillis；
 * - 旧格式（历史 JSON、旧备份）：读取 amount / time 字符串并转换。
 *
 * 注册到 DataStoreManager 与 LedgerRepository 的 Gson 上即可。
 */
class LedgerItemJsonAdapter : JsonDeserializer<LedgerItem>, JsonSerializer<LedgerItem> {

    override fun serialize(
        src: LedgerItem,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement = JsonObject().apply {
        addProperty("id", src.id)
        addProperty("amountCents", src.amountCents)
        addProperty("timeMillis", src.timeMillis)
        addProperty("isExpense", src.isExpense)
        addProperty("categoryName", src.categoryName)
        addProperty("categoryIconRes", src.categoryIconRes)
        addProperty("note", src.note)
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): LedgerItem {
        val obj = json.asJsonObject

        val amountCents = when {
            obj.has("amountCents") && !obj["amountCents"].isJsonNull -> obj["amountCents"].asLong
            obj.has("amount") -> (obj["amount"].asString.toDoubleOrNull()?.times(100)?.roundToLong() ?: 0L)
            else -> 0L
        }

        val timeMillis = when {
            obj.has("timeMillis") && !obj["timeMillis"].isJsonNull -> obj["timeMillis"].asLong
            obj.has("time") -> parseOldTime(obj["time"].asString)
            else -> 0L
        }

        return LedgerItem(
            id = obj["id"]?.takeIf { !it.isJsonNull }?.asString ?: "",
            amountCents = amountCents,
            note = obj["note"]?.takeIf { !it.isJsonNull }?.asString ?: "",
            timeMillis = timeMillis,
            isExpense = obj["isExpense"]?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
            categoryName = obj["categoryName"]?.takeIf { !it.isJsonNull }?.asString ?: "其他",
            categoryIconRes = obj["categoryIconRes"]?.takeIf { !it.isJsonNull }?.asInt ?: 0
        )
    }

    private fun parseOldTime(timeStr: String): Long {
        if (timeStr.isBlank()) return 0L
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            try {
                return SimpleDateFormat(pattern, Locale.getDefault()).parse(timeStr)?.time ?: 0L
            } catch (_: Exception) {
                // try next format
            }
        }
        return 0L
    }
}
