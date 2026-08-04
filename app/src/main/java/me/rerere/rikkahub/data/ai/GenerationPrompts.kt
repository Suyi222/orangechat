/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}

/**
 * 「树影下」状态注入（决策 10/16）
 * 状态卡 + 时间线全文拼在工具提示之后；备注为空不注入；今天无任何记录则返回空串。
 */
internal suspend fun buildStatePrompt(service: me.rerere.rikkahub.data.service.TreeShadowService): String {
    val today = me.rerere.rikkahub.data.service.TreeShadowService.today()
    val card = service.getActiveStateCard(today)
    val timeline = service.getActiveTimeline(today)
    if (card == null && timeline.isEmpty()) return ""
    val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return buildString {
        appendLine()
        appendLine("## 树影下 · 今日状态")
        if (card != null && card.content.isNotBlank()) {
            appendLine("【状态卡】${card.content}")
        }
        // 备注有内容才注入，不占 token（决策 16）
        if (card != null && !card.note.isNullOrBlank()) {
            appendLine("【备注】${card.note}")
        }
        if (timeline.isNotEmpty()) {
            appendLine("【时间线】")
            timeline.forEach { entry ->
                appendLine("- ${timeFormat.format(java.util.Date(entry.createdAt))} ${entry.content}")
            }
        }
    }
}
