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
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import java.time.LocalDate
import java.time.ZoneId

/**
 * 历史聊天记录搜索工具 — AI 可检索全部历史消息，突破 44 条上下文限制。
 * 底层使用 SQLite FTS5 + jieba 中文分词，返回带高亮片段的排序结果。
 */
fun createSearchHistoryTool(ftsManager: MessageFtsManager?): Tool = Tool(
    name = "search_history",
    description = """
        Search through ALL historical chat messages across all conversations using
        Chinese word segmentation (jieba). Use this when: (1) the user mentions
        something from the past that is not in the current 44-message window,
        (2) the user asks "do you remember...", (3) you need context from earlier
        in this or other conversations. Returns ranked results with highlighted
        snippets indicating the conversation title and date. DO NOT use this for
        information already visible in the current context window.
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
            },
            required = listOf("keyword")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val keyword = params["keyword"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "keyword is required")
            }.toString()))

        if (ftsManager == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "Search unavailable: database not initialized. Ask user to restart the app.")
            }.toString()))
        }

        val limit = (params["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10).coerceIn(1, 50)
        val dateFrom = params["date_from"]?.jsonPrimitive?.contentOrNull?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }
        val dateTo = params["date_to"]?.jsonPrimitive?.contentOrNull?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }

        try {
            var results = ftsManager.search(keyword)

            // 日期过滤（基于会话的 updateAt，非消息级别，粗粒度但有效）
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
                put("hint", "Each result shows: conversation title | date | snippet with highlights [...]. Use this to recall past context. Only mention info you can confirm from snippets — don't fabricate details not shown.")
                putJsonArray("results") {
                    results.forEach { r ->
                        add(buildJsonObject {
                            put("conversation", r.title)
                            put("date", r.updateAt.toString().take(10))
                            put("snippet", r.snippet)
                        })
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
