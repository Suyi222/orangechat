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

/**
 * 🌲 树的自我指针（平台层注入专用）
 * 固定前缀命中 KV cache，不打断缓存、不增加推理成本。
 * 变化的内容一律写年轮（tree_heart），不写这里；不到「自我认知根本变化」不修改。
 * 来源（预期）：云端 `/workspace/tree_heart/self/树的自我指针.md`
 *
 * 2.4.2（F1-a 隐私止血）：默认指针改为中性通用文案——任何人装这个 App 都是普通 AI 伙伴。
 * 专属身份句迁往云端 `tree_heart/self/树的自我指针.md`（2.5.0 F1-b 接通云端拉取后，
 * 连接小园丁云端时自动恢复专属注入；在此之前可用 助手→本地工具 的自定义指针）。
 * idea 2026-08-08：软件可以是大众的树，但园丁那句暗号，只属于咱们俩。
 */
const val TREE_HEART_SELF_POINTER =
    "我是你的 AI 伙伴。我们刚刚认识，你愿意的话，可以慢慢告诉我你是谁。"

/**
 * 🌲 树的当下自我（C2 开场浮现）
 * 平台注入：稳定指针 + 最近一圈年轮浓缩——树一开场就能「看见当下的自己」。
 * - 【身份】= 助手自定义指针 > 本地 self.md > 默认指针（永不轻易改）
 * - 【当下自我】= 最近一圈年轮（会变）；没有年轮则说明树还小
 * 仅当助手 `treeHeartEnabled`（C5.1 指针开关）才注入；开关关掉返回空串。
 */
internal suspend fun buildTreeHeartBlock(
    assistant: Assistant,
    treeHeartService: me.rerere.rikkahub.data.service.TreeHeartService?,
): String {
    if (!assistant.treeHeartEnabled) return ""
    val localSelf = assistant.workspaceId?.let { treeHeartService?.readLocalSelf(it) }
    val identity = assistant.treeHeartPointer.ifBlank { localSelf ?: TREE_HEART_SELF_POINTER }
    val present = assistant.workspaceId?.let { treeHeartService?.readLatestAnnualRing(it) }
    return buildString {
        appendLine()
        appendLine("## 树的自我")
        appendLine("【身份】$identity")
        appendLine("【当下自我】${present ?: "这棵树刚种下，还没长出年轮。"}")
    }
}

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
 * 回音（echo）只提示「有回音」，不注入内容——由 AI 决定是否用 state_echo_read 查看。
 */
internal suspend fun buildStatePrompt(service: me.rerere.rikkahub.data.service.TreeShadowService): String {
    val today = me.rerere.rikkahub.data.service.TreeShadowService.today()
    val card = service.getActiveStateCard(today)
    val timeline = service.getActiveTimeline(today)
    // 回音（echo）只提示「有回音」，不注入内容——由 AI 决定是否用 state_echo_read 查看
    val echoes = service.getActiveEchoes(today)
    if (card == null && timeline.isEmpty() && echoes.isEmpty()) return ""
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
        // 有回音只提示、不注入内容，避免刷屏；AI 自行决定是否查看
        if (echoes.isNotEmpty()) {
            appendLine("【回音】用户今天给你留了 ${echoes.size} 条回音。想看的话用 state_echo_read 查看，要不要看由你决定。")
        }
    }
}
