/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * 桌面便签 Widget — AI 可在桌面 Widget 写/删提醒。
 * 用户长按桌面 → 小部件 → 「橘瓣·桌面便签」拖到桌面。
 */
class DeskNoteWidgetProvider : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME = "desk_note_widget_prefs"
        const val KEY_NOTE_CONTENT = "note_content"
        const val KEY_NOTE_EXPIRE_AT = "note_expire_at"
        private const val DEFAULT_TEXT = "🌲 橘瓣在这里陪你"

        fun updateWidgets(context: Context, appWidgetManager: AppWidgetManager, widgetIds: IntArray) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val content = prefs.getString(KEY_NOTE_CONTENT, null)
            val expireAt = prefs.getLong(KEY_NOTE_EXPIRE_AT, 0L)

            val displayText = if (content != null && !content.isBlank() && (expireAt == 0L || System.currentTimeMillis() < expireAt)) {
                content
            } else {
                DEFAULT_TEXT
            }

            for (widgetId in widgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_desk_note)
                views.setTextViewText(R.id.widget_note_text, displayText)

                val intent = Intent(context, RouteActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, widgetId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, widgetIds: IntArray) {
        updateWidgets(context, appWidgetManager, widgetIds)
    }
}
