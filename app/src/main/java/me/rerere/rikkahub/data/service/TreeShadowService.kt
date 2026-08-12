/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.db.dao.TreeShadowDao
import me.rerere.rikkahub.data.db.entity.TreeShadowEntry
import java.time.LocalDate

/**
 * 「树影下」状态系统服务
 * - 状态卡（含备注）：每天一张，AI 持续刷新
 * - 时间线：当天重要事件逐条追加
 * - 回声：绑定回声（挂时间线下）+ 自由回声
 * - 归档：把某日全部记录置为已归档，进入「往日」页
 */
class TreeShadowService(
    private val dao: TreeShadowDao,
) {
    /** 写入/更新状态卡（含备注）。备注为空字符串/空白视为不写。 */
    suspend fun writeStateCard(dateGroup: String, content: String, note: String?) {
        val existing = dao.getActiveStateCard(dateGroup)
        val entry = TreeShadowEntry(
            id = existing?.id ?: 0,
            dateGroup = dateGroup,
            type = TreeShadowEntry.STATE_CARD,
            content = content,
            note = note?.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        if (existing != null) dao.update(entry) else dao.insert(entry)
    }

    /** 追加一条时间线记录 */
    suspend fun appendTimeline(dateGroup: String, content: String) {
        if (content.isBlank()) return
        dao.insert(
            TreeShadowEntry(
                dateGroup = dateGroup,
                type = TreeShadowEntry.TIMELINE,
                content = content,
            )
        )
    }

    /** 添加绑定回声（挂在某条时间线记录下） */
    suspend fun addBoundEcho(dateGroup: String, timelineId: Int, content: String) {
        if (content.isBlank()) return
        dao.insert(
            TreeShadowEntry(
                dateGroup = dateGroup,
                type = TreeShadowEntry.ECHO_BOUND,
                content = content,
                parentId = timelineId,
            )
        )
    }

    /** 添加自由回声（页面「今天我想说」） */
    suspend fun addFreeEcho(dateGroup: String, content: String) {
        if (content.isBlank()) return
        dao.insert(
            TreeShadowEntry(
                dateGroup = dateGroup,
                type = TreeShadowEntry.ECHO_FREE,
                content = content,
            )
        )
    }

    /** 归档某日（三重保险共用） */
    suspend fun archive(dateGroup: String) = dao.archiveDate(dateGroup)

    /** 兜底归档：上次活跃日期不是今天时，把旧日归档（决策 13 保险三） */
    suspend fun maybeAutoArchive(lastActiveDate: String?) {
        val today = today()
        val stale = lastActiveDate?.takeIf { it.isNotBlank() && it != today } ?: return
        dao.archiveDate(stale)
    }

    suspend fun getActiveStateCard(dateGroup: String): TreeShadowEntry? = dao.getActiveStateCard(dateGroup)

    suspend fun getActiveTimeline(dateGroup: String): List<TreeShadowEntry> = dao.getActiveTimeline(dateGroup)

    suspend fun getActiveEchoes(dateGroup: String): List<TreeShadowEntry> = dao.getActiveEchoes(dateGroup)

    /** 某日全部记录（含归档，往日页详情用） */
    suspend fun getDay(dateGroup: String): List<TreeShadowEntry> = dao.getByDate(dateGroup)

    suspend fun getById(id: Int): TreeShadowEntry? = dao.getById(id)

    /** 按 id 更新某条记录（时间线/状态卡通用）。content 为 null 保持原内容；note 仅状态卡生效 */
    suspend fun updateEntryContent(id: Int, content: String?, note: String?) {
        val existing = dao.getById(id) ?: return
        val newContent = content?.takeIf { it.isNotBlank() } ?: existing.content
        val newNote = if (existing.type == TreeShadowEntry.STATE_CARD && note != null) {
            note.takeIf { it.isNotBlank() }
        } else {
            existing.note
        }
        dao.update(existing.copy(content = newContent, note = newNote))
    }

    /** 按 id 删除单条记录；若删除的是时间线/状态卡，其下绑定的回声一并清除（避免孤儿数据） */
    suspend fun deleteById(id: Int) {
        dao.deleteBoundEchoesOf(id)
        dao.deleteById(id)
    }

    /** 已归档日期列表（时间倒序） */
    suspend fun getArchivedDates(): List<String> = dao.getArchivedDates()

    companion object {
        fun today(): String = LocalDate.now().toString()
    }
}
