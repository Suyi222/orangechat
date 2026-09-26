/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 请求日志（开发者页），2.4.6.4 第一刀重写。
 *
 * ⚠️ 为什么重写（B0 取证定案，详见计划书 2.4.6.4 节）：
 * 旧实现保留最近 32 条 Generation，每条持有【完整请求 messages】——而 messages 是
 * 每轮生成从数据库解码出来的新对象（不是共享引用），等于每生成一次就在堆里多囤一份
 * 「整会话副本」（5093 条会话 ≈ 29MB/份），且淘汰只看条数不看字节，MAX_LOGS=32 根本
 * 来不及生效：512MB ÷ 29MB ≈ 17 次生成即 OOM。这就是聊天 OOM 的真凶。
 *
 * 新实现三条（对应定案的三条修法）：
 * ① 日志只留「瘦身摘要」：参数元数据 + 工具名列表 + 末尾 [TAIL_SIZE] 条消息摘要
 *    （文本截断 [PREVIEW_CHARS] 字；附件只留类型/字节数/采样 hash），不再持有
 *    messages / tools schema / providerSetting 的任何引用；
 * ② 淘汰双保险：条数上限 [MAX_LOGS] + 总字节预算 [MAX_TOTAL_BYTES]（16MB，超出从最旧丢起）；
 * ③ 需要完整原始请求体的场景（网关测试/D1）走「按需落盘」：开发者页打开导出开关后，
 *    每次生成把完整请求体异步写到 filesDir/[DUMP_DIR_NAME]/（provider 密钥脱敏、永不落盘），
 *    写完即释放引用，不常驻内存。
 */
sealed class AILogging {
    /** 本条日志的驻留开销估算（字节），供字节预算淘汰用。 */
    abstract val estimatedBytes: Int

    data class Generation(
        val timestamp: Long,
        val modelId: String,
        val modelName: String,
        val temperature: Float?,
        val topP: Float?,
        val maxTokens: Int?,
        val reasoningLevel: String,
        /** 工具 schema 只留名字（旧实现整份 113 个 schema 常驻）。 */
        val toolNames: List<String>,
        val providerName: String,
        /** 仅 baseUrl 之类的非敏感字段；apiKey 永不进日志。 */
        val providerBaseUrl: String?,
        val stream: Boolean,
        val messageCount: Int,
        /** 末尾若干条消息的摘要（不是原对象）。 */
        val tailDigests: List<MessageDigest>,
        override val estimatedBytes: Int,
    ) : AILogging()

    data class MessageDigest(
        val role: String,
        val textChars: Int,
        val textPreview: String,
        /** 附件元信息：类型 + 字节数 + 采样 hash（data URL）或文件名（file）。 */
        val attachments: List<String>,
    )
}

/** 条数上限（摘要很轻，32 条 ≈ 百 KB 级；字节预算才是主闸）。 */
private const val MAX_LOGS = 32

/** 字节预算：全部日志驻留总量 ≤16MB（B0 定案值），超出从最旧一条丢起。 */
private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

/** 每条日志保留末尾几条消息的摘要。 */
private const val TAIL_SIZE = 6

/** 摘要里单条消息文本预览的截断长度（字符）。 */
private const val PREVIEW_CHARS = 160

/** 采样 hash 的取样长度（字符）：hash(长度 + 前 N 字)，避免对几十 MB base64 全量算 hash 卡生成链。 */
private const val HASH_SAMPLE_CHARS = 4096

private const val DUMP_DIR_NAME = "ai_request_dump"

class AILoggingManager(private val context: Context) {
    companion object {
        private const val TAG = "AILogging"
    }

    private val logs = MutableStateFlow<List<AILogging>>(emptyList())
    private val dumpEnabled = MutableStateFlow(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun getLogs(): StateFlow<List<AILogging>> = logs

    /** 「请求体落盘」开关状态（网关取证用，默认关）。 */
    fun isDumpEnabled(): StateFlow<Boolean> = dumpEnabled

    fun setDumpEnabled(enabled: Boolean) {
        dumpEnabled.value = enabled
        Log.i(TAG, "request dump ${if (enabled) "ENABLED -> ${dumpDirPath()}" else "DISABLED"}")
    }

    /** 落盘目录路径（仅展示用，不创建目录；真正写文件时才 mkdirs）。 */
    fun dumpDirPath(): String = File(context.filesDir, DUMP_DIR_NAME).absolutePath

    /**
     * 生成链唯一入口（替代旧 addLog）：
     * ③ 开关打开时先把完整请求体丢给 IO 线程落盘（瞬时引用，写完即可回收）；
     * ① 入列表的只有瘦身摘要；② 条数 + 字节预算双重淘汰。
     */
    fun addGeneration(
        params: TextGenerationParams,
        messages: List<UIMessage>,
        providerSetting: ProviderSetting,
        stream: Boolean,
    ) {
        if (dumpEnabled.value) {
            ioScope.launch { dumpFullRequest(params, messages, providerSetting, stream) }
        }
        val slim = slimOf(params, messages, providerSetting, stream)
        var next = logs.value + slim
        if (next.size > MAX_LOGS) {
            next = next.drop(next.size - MAX_LOGS)
        }
        var totalBytes = next.sumOf { it.estimatedBytes.toLong() }
        while (totalBytes > MAX_TOTAL_BYTES && next.size > 1) {
            totalBytes -= next.first().estimatedBytes
            next = next.drop(1)
        }
        logs.value = next
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    // ------------------------------------------------------------------
    // ① 瘦身摘要
    // ------------------------------------------------------------------

    private fun slimOf(
        params: TextGenerationParams,
        messages: List<UIMessage>,
        providerSetting: ProviderSetting,
        stream: Boolean,
    ): AILogging.Generation {
        val toolNames = params.tools.map { it.name }
        val tailDigests = messages.takeLast(TAIL_SIZE).map { digestOf(it) }
        val providerBaseUrl = when (providerSetting) {
            is ProviderSetting.OpenAI -> providerSetting.baseUrl
            is ProviderSetting.Google -> providerSetting.baseUrl
            is ProviderSetting.Claude -> providerSetting.baseUrl
        }
        val generation = AILogging.Generation(
            timestamp = System.currentTimeMillis(),
            modelId = params.model.modelId,
            modelName = params.model.displayName.ifBlank { params.model.modelId },
            temperature = params.temperature,
            topP = params.topP,
            maxTokens = params.maxTokens,
            reasoningLevel = params.reasoningLevel.name,
            toolNames = toolNames,
            providerName = providerSetting.name,
            providerBaseUrl = providerBaseUrl,
            stream = stream,
            messageCount = messages.size,
            tailDigests = tailDigests,
            estimatedBytes = 0, // 下面按实际持有字符串重算
        )
        return generation.copy(estimatedBytes = estimateBytes(generation))
    }

    private fun digestOf(message: UIMessage): AILogging.MessageDigest {
        val text = StringBuilder()
        val attachments = mutableListOf<String>()
        message.parts.forEach { part ->
            @Suppress("DEPRECATION")
            when (part) {
                is UIMessagePart.Text -> text.append(part.text)
                is UIMessagePart.Reasoning -> text.append(part.reasoning)
                is UIMessagePart.Image -> attachments.add(describeUrl("image", part.url))
                is UIMessagePart.Video -> attachments.add(describeUrl("video", part.url))
                is UIMessagePart.Audio -> attachments.add(describeUrl("audio", part.url))
                is UIMessagePart.VoiceMessage -> attachments.add(describeUrl("voice", part.url))
                is UIMessagePart.Document -> attachments.add("document(${part.fileName}) ${describeUrl(part.mime, part.url)}")
                is UIMessagePart.Search -> attachments.add("search")
                is UIMessagePart.ToolCall -> attachments.add("tool_call:${part.toolName}")
                is UIMessagePart.ToolResult -> attachments.add("tool_result:${part.toolName}")
                is UIMessagePart.Tool -> attachments.add("tool:${part.toolName}")
            }
        }
        val full = text.toString()
        return AILogging.MessageDigest(
            role = message.role.name,
            textChars = full.length,
            textPreview = full.take(PREVIEW_CHARS),
            attachments = attachments,
        )
    }

    /**
     * 附件元信息：data URL → 类型 + 估算字节数 + 采样 hash；file → 类型 + 磁盘字节数 + 文件名；
     * http(s) → 类型 + URL 前缀。全程不持有、不复制原内容。
     */
    private fun describeUrl(kind: String, url: String): String = when {
        url.startsWith("data:") -> {
            val comma = url.indexOf(',')
            val payloadLen = if (comma >= 0) url.length - comma - 1 else url.length
            val approxBytes = payloadLen * 3L / 4
            "$kind ${fmtBytes(approxBytes)} sha1:${sampleHash(url)}"
        }

        url.startsWith("file:") || url.startsWith("/") -> {
            val file = runCatching { File(URI(url)) }.getOrElse { File(url.removePrefix("file://")) }
            if (file.exists()) "$kind ${fmtBytes(file.length())} ${file.name}"
            else "$kind missing ${file.name}"
        }

        else -> "$kind url:${url.take(120)}"
    }

    private fun sampleHash(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = ("${s.length}:${s.take(HASH_SAMPLE_CHARS)}").toByteArray(Charsets.UTF_8)
        return md.digest(bytes).joinToString("") { "%02x".format(it) }.take(8)
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", b / 1024.0 / 1024.0)
        b >= 1024 -> String.format(Locale.US, "%.1fKB", b / 1024.0)
        else -> "${b}B"
    }

    /** 粗估驻留开销：所有持有字符串按 UTF-16 ≈2B/char + 每条 128B 对象头。 */
    private fun estimateBytes(g: AILogging.Generation): Int {
        var chars = g.modelId.length + g.modelName.length + g.providerName.length +
            (g.providerBaseUrl?.length ?: 0) + g.reasoningLevel.length
        chars += g.toolNames.sumOf { it.length }
        g.tailDigests.forEach { d ->
            chars += d.role.length + d.textPreview.length + d.attachments.sumOf { it.length } + 16
        }
        return 128 + chars * 2
    }

    // ------------------------------------------------------------------
    // ③ 按需落盘：完整请求体导出（网关测试 / D1）
    // ------------------------------------------------------------------

    /**
     * 把这一轮生成的完整请求体写成 JSON 文件。密钥脱敏规则：provider 只写
     * 类型/名称/baseUrl，apiKey、privateKey 等一律不落盘。写失败只记日志，
     * 绝不反噬生成链。
     */
    private fun dumpFullRequest(
        params: TextGenerationParams,
        messages: List<UIMessage>,
        providerSetting: ProviderSetting,
        stream: Boolean,
    ) {
        runCatching {
            val dir = File(context.filesDir, DUMP_DIR_NAME).apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "req_${ts}.json")
            val payload = buildJsonObject {
                put("timestamp", System.currentTimeMillis())
                put("stream", stream)
                putJsonObject("provider") {
                    put("type", providerSetting::class.simpleName ?: "unknown")
                    put("name", providerSetting.name)
                    when (providerSetting) {
                        is ProviderSetting.OpenAI -> put("baseUrl", providerSetting.baseUrl)
                        is ProviderSetting.Google -> put("baseUrl", providerSetting.baseUrl)
                        is ProviderSetting.Claude -> put("baseUrl", providerSetting.baseUrl)
                    }
                    put("note", "apiKey redacted（脱敏：密钥永不落盘）")
                }
                putJsonObject("params") {
                    put("modelId", params.model.modelId)
                    put("modelDisplayName", params.model.displayName)
                    put("temperature", params.temperature)
                    put("topP", params.topP)
                    put("maxTokens", params.maxTokens)
                    put("reasoningLevel", params.reasoningLevel.name)
                    put("customHeaders", JsonInstant.encodeToJsonElement(ListSerializer(CustomHeader.serializer()), params.customHeaders))
                    put("customBody", JsonInstant.encodeToJsonElement(ListSerializer(CustomBody.serializer()), params.customBody))
                    // 工具 schema 手工展开：Tool 的 lambda 字段不可序列化，且这正是发给网关的实际形态
                    put("tools", JsonArray(params.tools.map { tool ->
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters()?.let { schema ->
                                put("parameters", JsonInstant.encodeToJsonElement(me.rerere.ai.core.InputSchema.serializer(), schema))
                            }
                        }
                    }))
                }
                put("messages", JsonInstant.encodeToJsonElement(ListSerializer(UIMessage.serializer()), messages))
            }
            file.writeText(JsonInstantPretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload))
            Log.i(TAG, "request dumped: ${file.name} (${fmtBytes(file.length())})")
        }.onFailure { Log.w(TAG, "dump request failed", it) }
    }
}
