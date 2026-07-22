/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools.system

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.widget.DeskNoteWidgetProvider

/**
 * 桌面便签工具 — AI 可在桌面 Widget 写/删提醒。
 */
fun createDeskNoteTool(context: Context): Tool = Tool(
    name = "post_desk_note",
    description = """
        Write or clear a note on the user's home screen widget. Provide content to
        set a new note; provide an empty string to clear it. The widget updates
        immediately. Content should be short (under 100 chars) for best display.
        Use for morning greetings, study reminders, or quick notes.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("content", buildJsonObject {
                    put("type", "string")
                    put("description", "Note content. Empty string clears the note. Keep under 100 chars.")
                })
                put("duration_hours", buildJsonObject {
                    put("type", "integer")
                    put("description", "Hours to display. After this the widget resets to default. Default: 24, max: 168 (7 days)")
                })
            },
            required = listOf("content")
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
        val durationHours = (params["duration_hours"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 24).coerceIn(1, 168)

        try {
            val prefs = context.getSharedPreferences(DeskNoteWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
            if (content.isBlank()) {
                // 删除：清空内容，立即过期
                prefs.edit()
                    .remove(DeskNoteWidgetProvider.KEY_NOTE_CONTENT)
                    .remove(DeskNoteWidgetProvider.KEY_NOTE_EXPIRE_AT)
                    .apply()
            } else {
                val expireAt = System.currentTimeMillis() + durationHours * 3600_000L
                prefs.edit()
                    .putString(DeskNoteWidgetProvider.KEY_NOTE_CONTENT, content)
                    .putLong(DeskNoteWidgetProvider.KEY_NOTE_EXPIRE_AT, expireAt)
                    .apply()
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, DeskNoteWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds.isNotEmpty()) {
                DeskNoteWidgetProvider.updateWidgets(context, appWidgetManager, widgetIds)
            }

            val isClear = content.isBlank()
            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("action", if (isClear) "cleared" else "set")
                if (!isClear) {
                    put("content", content)
                    put("duration_hours", durationHours)
                }
                put("widget_count", widgetIds.size)
                if (widgetIds.isEmpty()) {
                    put("hint", "No widget on home screen yet. Note saved, will appear when widget is added.")
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
