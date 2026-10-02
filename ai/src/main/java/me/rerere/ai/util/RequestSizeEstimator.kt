/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 2.4.7 D3（OOM 第二刀·体积守卫）：发送前对请求体做廉价估算（字节）。
 *
 * 目的：把「这次请求体到底多大」从黑盒变成可见数字——
 * ① ChatCompletionsAPI 发送前日志摘要带 estBody，超 [WARN_THRESHOLD_BYTES] 打 warn
 *   （只告警不截断：截断=改请求语义，范围铁律禁动）；
 * ② AILogging 摘要行 + 开发者页卡片显示「请求体约 X MB」；
 * ③ 水位取证：与落盘 req_*.json 实际大小对账（验收口径：误差 <10%）。
 *
 * 估算口径（全部 O(n) 扫描、零大分配）：
 * - 文本/思考/工具入参：逐字符精确 UTF-8 长度（CJK 3B、ASCII 1B、代理对 4B），
 *   比计划书草案「长度×3」更准（×3 对 ASCII 会高估 3 倍）；
 * - base64/data URL：字符数 ≈ 字节数（纯 ASCII）；
 * - file:// 图片：磁盘字节 × 4/3（base64 膨胀）上界——实际发送走 compressAndEncode
 *   重压缩（quality 85），大图估算可能偏大；纯文本大会话（水位取证主场景）不受影响；
 * - tools schema：首次按序列化长度算，之后按工具名缓存（schema 跨请求近似静态，
 *   即计划书「tools schema 缓存估算」口径）；
 * - JSON 结构开销：按消息/部件/工具计固定余量。
 */
object RequestSizeEstimator {

    /** >4MB 告警阈值（计划书 D3 定值；512MB 堆机器上 7-8MB 请求体 ×2 曾是 OOM 主峰）。 */
    const val WARN_THRESHOLD_BYTES = 4L * 1024 * 1024

    /** 工具 schema 估算缓存：name → 序列化后 UTF-8 字节数。 */
    private val schemaSizeCache = ConcurrentHashMap<String, Long>()

    /** 估算一次请求体大小（字节）。messages = 即将发送的完整消息列表（含 system）。 */
    fun estimate(messages: List<UIMessage>, params: TextGenerationParams): Long {
        var bytes = 256L // model/stream/temperature/top_p/max_tokens/stream_options 等骨架
        for (message in messages) {
            bytes += 48 // role + JSON 标点
            for (part in message.parts) {
                bytes += estimatePart(part)
            }
        }
        if (params.tools.isNotEmpty()) {
            bytes += 32
            for (tool in params.tools) {
                bytes += estimateTool(tool)
            }
        }
        bytes += (params.customHeaders.size + params.customBody.size) * 64L
        return bytes
    }

    @Suppress("DEPRECATION")
    private fun estimatePart(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> utf8Length(part.text) + 24
        is UIMessagePart.Reasoning -> utf8Length(part.reasoning) + 40
        is UIMessagePart.Image -> estimateUrl(part.url) + 40
        is UIMessagePart.Video -> estimateUrl(part.url) + 40
        is UIMessagePart.Audio -> estimateUrl(part.url) + 40
        is UIMessagePart.VoiceMessage -> estimateUrl(part.url) + utf8Length(part.transcript) + 56
        is UIMessagePart.Document ->
            estimateUrl(part.url) + utf8Length(part.fileName) + utf8Length(part.mime) + 64
        is UIMessagePart.Tool -> {
            var b = utf8Length(part.input) + utf8Length(part.toolName) + 96
            for (out in part.output) b += estimatePart(out)
            b
        }
        is UIMessagePart.ToolCall -> utf8Length(part.arguments) + utf8Length(part.toolName) + 64
        is UIMessagePart.ToolResult ->
            part.content.toString().length.toLong() + part.arguments.toString().length.toLong() + 64
        is UIMessagePart.Search -> 16
    }

    private fun estimateTool(tool: Tool): Long {
        val schemaBytes = schemaSizeCache.getOrPut(tool.name) {
            runCatching {
                tool.parameters()?.let { schema ->
                    utf8Length(json.encodeToString(InputSchema.serializer(), schema))
                } ?: 32L
            }.getOrElse { 256L }
        }
        return schemaBytes + utf8Length(tool.name) + utf8Length(tool.description) + 64
    }

    /**
     * URL 形态附件的发送字节估算：
     * data: → 逗号后按 ASCII 计；file:///绝对路径 → 磁盘字节×4/3（base64 膨胀上界，
     * 实际会重压缩）；http(s) → URL 本身；其余（历史里的裸 base64）→ 字符数。
     */
    private fun estimateUrl(url: String): Long = when {
        url.startsWith("data:") -> {
            val comma = url.indexOf(',')
            (if (comma >= 0) url.length - comma - 1 else url.length).toLong() + 32
        }
        url.startsWith("file:") || url.startsWith("/") -> {
            val file = runCatching {
                if (url.startsWith("file:")) File(URI(url)) else File(url)
            }.getOrNull()
            val len = file?.takeIf { it.exists() }?.length() ?: 0L
            len * 4 / 3 + 64
        }
        else -> url.length.toLong() + 16
    }

    /** 逐字符精确 UTF-8 字节长度（零分配；CJK 3B、ASCII 1B、代理对 4B）。 */
    fun utf8Length(s: String): Long {
        var n = 0L
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return n
    }

    /** 人类可读格式：如 7.4MB / 820KB / 512B。 */
    fun format(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
