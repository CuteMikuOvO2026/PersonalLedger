package com.example.personalledger

import android.content.Context

/**
 * AI 助手的配置：DeepSeek API Key 与模型选择。
 *
 * **为什么用 SharedPreferences 而不是 DataStore**（与 [ThemeSettings] 等技术选型看似不一致，
 * 但理由不同）：DataStore 只有**一个** `settings` 文件，里面同时存着自定义分类与自动记账开关
 * （见 [DataStoreManager]）。而 API Key 属于凭据，必须排除在自动备份与换机迁移之外
 * （见 `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml`）——
 * 按文件排除时，排掉 `settings.preferences_pb` 会连带丢掉用户的自定义分类。
 * 因此 Key 需要**独立文件**，而 SharedPreferences 天然一个名字一个文件。
 *
 * 明文存储：DataStore / SharedPreferences 都不加密，Key 以明文躺在应用私有目录里。
 * 本项目不引入 `androidx.security` 来做加密（依赖成本与收益不成比例），改为靠
 * 「私有目录 + 排除备份」控制暴露面；这个取舍在 README 里已写明。
 */
object AiSettings {

    private const val PREFS = "ai_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"

    /** DeepSeek 的 OpenAI 兼容端点。写死为常量：本项目只对接官方端点，不支持自建代理。 */
    const val BASE_URL = "https://api.deepseek.com/chat/completions"

    /** 快而便宜，作为默认模型。 */
    const val MODEL_FLASH = "deepseek-flash"

    /** 更强的推理模型，按 token 计费约为 Flash 的 4 倍。 */
    const val MODEL_PRO = "deepseek-v4-pro"

    /** 模型展示名，供设置弹窗的单选项使用。顺序必须与 [modelValues] 一致。 */
    fun modelLabels(): Array<String> = arrayOf("deepseek-flash（快、便宜）", "deepseek-v4-pro（更强）")

    fun modelValues(): Array<String> = arrayOf(MODEL_FLASH, MODEL_PRO)

    fun modelIndexOf(model: String): Int = modelValues().indexOf(model).takeIf { it >= 0 } ?: 0

    fun modelFromIndex(index: Int): String = modelValues().getOrElse(index) { MODEL_FLASH }

    /** 把任意外部传入的值收敛成两个合法取值之一，避免脏数据让请求打到不存在的模型上。 */
    fun normalizeModel(model: String?): String =
        if (model == MODEL_PRO) MODEL_PRO else MODEL_FLASH

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun model(context: Context): String = normalizeModel(prefs(context).getString(KEY_MODEL, MODEL_FLASH))

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, normalizeModel(model)).apply()
    }

    /** 只有 Key 非空才允许发请求——这是「未配置时绝不联网」的单一判据。 */
    fun isConfigured(context: Context): Boolean = apiKey(context).isNotBlank()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
