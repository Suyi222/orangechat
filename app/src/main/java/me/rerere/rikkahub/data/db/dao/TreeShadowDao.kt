/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.TreeShadowEntry

@Dao
interface TreeShadowDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TreeShadowEntry): Long

    @Update
    suspend fun update(entry: TreeShadowEntry)

    /** 某日未归档的状态卡（每天一张） */
    @Query(
        "SELECT * FROM tree_shadow WHERE date_group = :dateGroup AND type = 'state_card' AND archived = 0 ORDER BY created_at DESC LIMIT 1"
    )
    suspend fun getActiveStateCard(dateGroup: String): TreeShadowEntry?

    /** 某日未归档的时间线（时间正序） */
    @Query(
        "SELECT * FROM tree_shadow WHERE date_group = :dateGroup AND type = 'timeline' AND archived = 0 ORDER BY created_at ASC"
    )
    suspend fun getActiveTimeline(dateGroup: String): List<TreeShadowEntry>

    /** 某日未归档的回声（绑定回声 + 自由回声） */
    @Query(
        "SELECT * FROM tree_shadow WHERE date_group = :dateGroup AND type IN ('echo_bound', 'echo_free') AND archived = 0 ORDER BY created_at ASC"
    )
    suspend fun getActiveEchoes(dateGroup: String): List<TreeShadowEntry>

    /** 某日全部记录（归档后查看用） */
    @Query("SELECT * FROM tree_shadow WHERE date_group = :dateGroup ORDER BY created_at ASC")
    suspend fun getByDate(dateGroup: String): List<TreeShadowEntry>

    /** 按时间线 id 查记录（绑定回声挂载用） */
    @Query("SELECT * FROM tree_shadow WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): TreeShadowEntry?

    /** 归档某日的全部未归档记录 */
    @Query("UPDATE tree_shadow SET archived = 1 WHERE date_group = :dateGroup AND archived = 0")
    suspend fun archiveDate(dateGroup: String)

    /** 有归档记录的日期列表（时间倒序） */
    @Query(
        "SELECT DISTINCT date_group FROM tree_shadow WHERE archived = 1 ORDER BY date_group DESC"
    )
    suspend fun getArchivedDates(): List<String>

    /** 删除某日全部记录 */
    @Query("DELETE FROM tree_shadow WHERE date_group = :dateGroup")
    suspend fun deleteByDate(dateGroup: String)
}
