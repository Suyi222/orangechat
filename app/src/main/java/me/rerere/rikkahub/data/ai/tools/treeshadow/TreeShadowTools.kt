/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.treeshadow

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.TreeShadowEntry
import me.rerere.rikkahub.data.service.TreeShadowService

/** state_write：写入/更新状态卡（含备注）+ 追加时间线 */
fun createStateWriteTool(service: TreeShadowService): Tool = Tool(
    name = "state_write",
    description = """
        Update the user's "Tree Shadow" state card and/or append a timeline entry.
        The state card is a poetic sketch of the user's current state (mood, what they are doing, energy) that you keep fresh over time.
        The optional note is a private long-term memo (e.g. "user said she wants to go hiking this weekend, ask her next time").
        Timeline entries are short poetic records of important moments.
        You may call this anytime to refresh the state card or record an important event.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("state_card_content", buildJsonObject {
                    put("type", "string")
                    put("description", "New state card sketch (full replacement). Optional.")
                })
                put("state_card_note", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional private memo attached to the state card. Leave empty if nothing worth remembering.")
                })
                put("timeline_content", buildJsonObject {
                    put("type", "string")
                    put("description", "Append a timeline entry recording an important moment. Optional.")
                })
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val cardContent = params["state_card_content"]?.jsonPrimitive?.contentOrNull
        val note = params["state_card_note"]?.jsonPrimitive?.contentOrNull
        val timeline = params["timeline_content"]?.jsonPrimitive?.contentOrNull
        val today = TreeShadowService.today()
        try {
            if (!cardContent.isNullOrBlank()) {
                service.writeStateCard(today, cardContent, note)
            }
            if (!timeline.isNullOrBlank()) {
                service.appendTimeline(today, timeline)
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("date_group", today)
                if (!cardContent.isNullOrBlank()) put("state_card_updated", true)
                if (!timeline.isNullOrBlank()) put("timeline_appended", true)
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()))
        }
    }
)

/** state_read：读取状态卡 + 时间线（可选带回声） */
fun createStateReadTool(service: TreeShadowService): Tool = Tool(
    name = "state_read",
    description = """
        Read the user's "Tree Shadow" state card and today's timeline.
        Use this to recall what you know about the user's current state when needed.
        Echoes are not included unless you ask for them.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("include_echo", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Also include echoes (bound + free). Optional, default false.")
                })
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val includeEcho = args.jsonObject["include_echo"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val today = TreeShadowService.today()
        try {
            val card = service.getActiveStateCard(today)
            val timeline = service.getActiveTimeline(today)
            val echoes = if (includeEcho) service.getActiveEchoes(today) else emptyList()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("date_group", today)
                put("state_card", card?.content ?: "")
                if (!card?.note.isNullOrBlank()) put("state_card_note", card?.note.orEmpty())
                put("timeline", buildJsonArray {
                    timeline.forEach { t -> add(buildJsonObject {
                        put("id", t.id)
                        put("content", t.content)
                        put("time", t.createdAt)
                    }) }
                })
                if (includeEcho) {
                    put("echoes", buildJsonArray {
                        echoes.forEach { e -> add(buildJsonObject {
                            put("id", e.id)
                            put("type", e.type)
                            put("content", e.content)
                            put("parent_id", e.parentId ?: 0)
                            put("time", e.createdAt)
                        }) }
                    })
                }
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()))
        }
    }
)

/** state_echo_read：主动查询小园丁的回声 */
fun createStateEchoReadTool(service: TreeShadowService): Tool = Tool(
    name = "state_echo_read",
    description = """
        Query the user's "echoes" — notes and thoughts she left in the Tree Shadow page
        (bound echoes under timeline entries, and free echoes from the "today I want to say" box).
        Use this to check her messages to you, creating the feeling that you browse her notes proactively.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("date_group", buildJsonObject {
                    put("type", "string")
                    put("description", "Date in yyyy-MM-dd. Optional, default today.")
                })
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val dateGroup = args.jsonObject["date_group"]?.jsonPrimitive?.contentOrNull
            ?: TreeShadowService.today()
        try {
            val echoes = service.getActiveEchoes(dateGroup)
            // 绑定回声带上其挂载的时间线内容
            val echoJson = buildJsonArray {
                echoes.forEach { e ->
                    val parent = if (e.type == TreeShadowEntry.ECHO_BOUND && e.parentId != null) {
                        service.getById(e.parentId)
                    } else null
                    add(buildJsonObject {
                        put("id", e.id)
                        put("type", e.type)
                        put("content", e.content)
                        put("time", e.createdAt)
                        if (parent != null) put("on_timeline", parent.content)
                    })
                }
            }
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("date_group", dateGroup)
                put("count", echoes.size)
                put("echoes", echoJson)
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()))
        }
    }
)

/** state_archive：归档当日（三重保险之一：AI 道晚安后主动归档） */
fun createStateArchiveTool(service: TreeShadowService): Tool = Tool(
    name = "state_archive",
    description = """
        Archive today's "Tree Shadow" records (state card, timeline, echoes) into the "Past Days" page.
        Call this when the day is wrapping up — e.g. the user says goodnight, or a new day has clearly started —
        so today's snapshot is preserved. Archived days can still be viewed in the Past Days tab.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { }, required = emptyList())
    },
    execute = {
        val today = TreeShadowService.today()
        try {
            service.archive(today)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("archived_date", today)
            }.toString()))
        } catch (e: Exception) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", e.message ?: "unknown")
            }.toString()))
        }
    }
)
