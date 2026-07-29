/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.system

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.utils.JsonInstant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 历史聊天记录搜索工具 — AI 可检索全部历史消息，突破 44 条上下文限制。
 * 底层使用 SQLite FTS5 + jieba 中文分词，返回带高亮片段的排序结果。
 * 支持 full_text 模式获取完整消息内容（更耗 token，按需使用）。
 */
fun createSearchHistoryTool(ftsManager: MessageFtsManager?, database: AppDatabase?): Tool = Tool(
    name = "search_history",
    description = """
        Search through ALL historical chat messages across all conversations using
        Chinese word segmentation (jieba). Use this when: (1) the user mentions
        something from the past that is not in the current 44-message window,
        (2) the user asks "do you remember...", (3) you need context from earlier
        in this or other conversations. Returns ranked results with highlighted
        snippets. Use full_text=true to get complete message content (higher token
        cost, only use when snippet is insufficient).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("keyword") {
                    put("type", "string")
                    put("description", "Search keyword or phrase. Use key nouns/concepts from user's question. E.g. '光遇', '考研政治', '桌面便签'. Supports Chinese word segmentation via jieba.")
                }
                putJsonObject("date_from") {
                    put("type", "string")
                    put("description", "Optional: filter results from this date (yyyy-MM-dd). Useful when user says 'last week' or 'in June'.")
                }
                putJsonObject("date_to") {
                    put("type", "string")
                    put("description", "Optional: filter results until this date (yyyy-MM-dd).")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max results to return. Default 10, max 50. Lower = fewer tokens consumed.")
                }
                putJsonObject("full_text") {
                    put("type", "boolean")
                    put("description", "Return full message content instead of snippets. COSTS MORE TOKENS. Only use when snippet is too brief to understand. Default: false.")
                }
                putJsonObject("node_id") {
                    put("type", "string")
                    put("description", "Optional: get full text of a specific message by node_id (requires message_id too). Ignores keyword search.")
                }
                putJsonObject("message_id") {
                    put("type", "string")
                    put("description", "Required when node_id is provided: the message id to fetch full text for.")
                }
            },
            required = listOf()
        )
    },
    execute = { args ->
        val params = args.jsonObject

        if (ftsManager == null || database == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "Search unavailable: database not initialized.")
            }.toString()))
        }

        // 精确消息查询模式：通过 node_id + message_id 取全文
        val nodeId = params["node_id"]?.jsonPrimitive?.contentOrNull
        val messageId = params["message_id"]?.jsonPrimitive?.contentOrNull
        if (nodeId != null && messageId != null) {
            return@Tool fetchFullMessage(database, nodeId, messageId)
        }

        // 关键词搜索模式
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "keyword or (node_id + message_id) is required")
            }.toString()))

        val limit = (params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val fullText = params["full_text"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val dateFrom = params["date_from"]?.jsonPrimitive?.contentOrNull?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }
        val dateTo = params["date_to"]?.jsonPrimitive?.contentOrNull?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }

        try {
            var results = ftsManager.search(keyword)

            if (dateFrom != null || dateTo != null) {
                results = results.filter { r ->
                    val msgDate = r.updateAt.atZone(ZoneId.systemDefault()).toLocalDate()
                    (dateFrom == null || !msgDate.isBefore(dateFrom)) &&
                    (dateTo == null || !msgDate.isAfter(dateTo))
                }
            }

            results = results.take(limit)

            if (results.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("total_found", 0)
                    put("message", "No matching messages found in history.")
                }.toString()))
            }

            val resultJson = buildJsonObject {
                put("success", true)
                put("total_found", results.size)
                put("hint", if (fullText) "Full message content returned. Use this to recall exact context." else "Snippets returned. Use node_id+message_id with full_text=true to get complete message content for any result.")
                putJsonArray("results") {
                    results.forEach { r ->
                        val item = buildJsonObject {
                            put("conversation", r.title)
                            put("date", r.updateAt.toString().take(10))
                            put("node_id", r.nodeId)
                            put("message_id", r.messageId)
                        }
                        if (fullText) {
                            val fullContent = try {
                                getMessageContent(database, r.nodeId, r.messageId)
                            } catch (_: Exception) {
                                r.snippet
                            }
                            item.put("content", fullContent)
                        } else {
                            item.put("snippet", r.snippet)
                        }
                        add(item)
                    }
                }
            }

            listOf(UIMessagePart.Text(resultJson.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "Search failed")
            }.toString()))
        }
    }
)

/**
 * 从数据库读取一条消息的完整文本内容
 */
private fun getMessageContent(database: AppDatabase, nodeId: String, messageId: String): String {
    val entity = database.messageNodeDao().getNodeById(nodeId) ?: return "(message not found)"
    val messages: List<UIMessage> = try {
        JsonInstant.decodeFromString(entity.messages)
    } catch (_: Exception) {
        return "(failed to parse message)"
    }
    val message = messages.find { it.id.toString() == messageId } ?: return "(message not found in node)"
    val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    return text.ifBlank { "(empty message)" }
}

/**
 * 精确查询一条消息的全文
 */
private fun fetchFullMessage(database: AppDatabase, nodeId: String, messageId: String): List<UIMessagePart> {
    return try {
        val content = getMessageContent(database, nodeId, messageId)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", true)
            put("node_id", nodeId)
            put("message_id", messageId)
            put("content", content)
        }.toString()))
    } catch (e: Exception) {
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", false)
            put("error", e.message ?: "Failed to fetch message")
        }.toString()))
    }
}
