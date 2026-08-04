/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.system

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.ProactiveMessageTriggerService

/**
 * 工作流主动唤醒工具 — 让工作流能唤醒 AI 查岗。
 */
fun createTriggerProactiveMessageTool(context: Context): Tool = Tool(
    name = "trigger_proactive_message",
    description = """
        Trigger the AI to proactively send a message to the user. Designed for
        workflow automation: when a workflow detects a condition (e.g. user opened
        a game), call this tool to wake the AI with a custom context. The AI will
        generate a natural reply based on the provided message.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "What happened and what the AI should know. E.g. '皇上打开了光遇，今日第2次，快去查岗！'")
                })
                put("include_usage", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Auto-include screen usage data. Default: true")
                })
            },
            required = listOf("message")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val message = params["message"]?.jsonPrimitive?.contentOrNull ?: error("message is required")
        val includeUsage = params["include_usage"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

        // 唤醒卡：醒因 + 要做什么，注入 AI 上下文顶部，让 AI 明确知道自己是"被工作流主动唤醒"的
        val ctx = StringBuilder()
        ctx.appendLine("【醒因】你是被工作流主动唤醒的（不是定时提醒，也不是用户主动找你）。")
        ctx.appendLine("【要做什么】$message")
        if (includeUsage) ctx.appendLine("（设备使用数据将自动附加）")
        ctx.appendLine()
        ctx.appendLine("重要规则：根据上述醒因与要做什么，以自然语气对用户说话，不提及技术细节。")

        val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
            putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
            // 工作流唤醒独立标志：与激进模式设备事件（EXTRA_DEVICE_EVENT_CONTEXT）区分开，
            // 避免被 buildSystemPrompt 误判为"用户手机动向（设备事件触发）"。
            putExtra(ProactiveMessageTriggerService.EXTRA_WORKFLOW_WAKEUP, true)
            putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, ctx.toString())
        }
        try {
            context.startForegroundService(intent)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("message", "Proactive message triggered: $message")
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()))
        }
    }
)
