/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「树影下」状态系统实体
 * 单表存储：状态卡 / 时间线 / 绑定回声 / 自由回声
 * - 状态卡：type=STATE_CARD，content=素描散文，note=备注（AI 可选项，为空则不注入）
 * - 时间线：type=TIMELINE，content=记录正文
 * - 绑定回声：type=ECHO_BOUND，content=感受，parentId 指向某条时间线
 * - 自由回声：type=ECHO_FREE，content=自由发言
 * 每天一张状态卡，多条时间线；归档 = 把当天记录置 archived
 */
@Entity(tableName = "tree_shadow")
data class TreeShadowEntry(
    @PrimaryKey(true)
    val id: Int = 0,

    /** 所属日期，格式 yyyy-MM-dd */
    @ColumnInfo("date_group")
    val dateGroup: String,

    /** 记录类型: state_card / timeline / echo_bound / echo_free */
    @ColumnInfo("type")
    val type: String,

    /** 正文（状态卡素描、时间线记录、回声内容） */
    @ColumnInfo("content")
    val content: String,

    /** 状态卡备注（AI 可选项），仅 state_card 使用 */
    @ColumnInfo("note")
    val note: String? = null,

    /** 绑定回声挂载的时间线记录 id */
    @ColumnInfo("parent_id")
    val parentId: Int? = null,

    /** 是否已归档（进入「往日」页） */
    @ColumnInfo("archived")
    val archived: Boolean = false,

    /** 创建时间戳 */
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATE_CARD = "state_card"
        const val TIMELINE = "timeline"
        const val ECHO_BOUND = "echo_bound"
        const val ECHO_FREE = "echo_free"
    }
}
