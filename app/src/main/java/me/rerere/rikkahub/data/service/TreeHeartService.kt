/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.util.Log
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import kotlin.uuid.Uuid

/**
 * 🌲 tree_heart 活化服务（C 模块）
 * - 本地浮现源：读每个助手工作区里的 `tree_heart/self/树的自我指针.md`（C1.2 每助手一自我）
 *   2.4.3 起注入只取「注入文本」段的**一行指针**，不再整篇注入（此前整篇读入会把
 *   见证列表/文件头一起塞进上下文，指针越用越长）
 * - 自动落账：把章节总结的深度对话写成年轮 `tree_heart/annual_rings/YYYY-MM.md`（C3）
 * - 自动见证（2.4.3 分流）：
 *   · 日级见证 → `tree_heart/self/自我宣言.md`（档案，不注入、不占上下文）
 *   · 月级晋升 → self.md「见证与晋升」**段内**插入（每自然月最多一条；此前每圈年轮都往
 *     文件尾追加，一天最多十几条，指针文件被见证列表撑爆——返工根因之二）
 *
 * 路径约定与 AI 的 workspace 工具一致：工作区内 `/workspace/tree_heart/...` ⇔ 相对路径 `tree_heart/...`
 */
class TreeHeartService(
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * 读某助手 self.md 的「注入文本」一行（浮现源）。
     * 无工作区 / 无文件 / 解析不出一行指针 → null，调用方回退自定义指针/默认指针。
     */
    suspend fun readLocalSelf(workspaceId: Uuid?): String? {
        val full = readLocalSelfFull(workspaceId) ?: return null
        extractPointerLine(full)?.let { return it }
        // 没有「注入文本」段的旧格式文件：仅当全文够短（纯一行式）才原样用作指针，
        // 长文件绝不整篇注入（防见证列表撑爆上下文）
        return if (full.length <= SHORT_SELF_INLINE_MAX && !full.contains('\n')) full else null
    }

    /** 读 self.md 全文（导出/备份用，不做行提取） */
    suspend fun readLocalSelfFull(workspaceId: Uuid?): String? {
        if (workspaceId == null) return null
        return runCatching {
            workspaceRepository.readText(workspaceId.toString(), SELF_PATH)
                .trim()
                .takeIf { it.isNotBlank() }
        }.onFailure { Log.w(TAG, "readLocalSelfFull failed: ${it.message}") }.getOrNull()
    }

    /** 读最近一圈年轮（最新月份文件末尾几行，作「当下自我」注入用） */
    suspend fun readLatestAnnualRing(workspaceId: Uuid?): String? {
        if (workspaceId == null) return null
        return runCatching {
            val files = workspaceRepository
                .listFiles(workspaceId.toString(), WorkspaceStorageArea.FILES, ANNUAL_RINGS_DIR)
                .sortedByDescending { it.name }
            val latest = files.firstOrNull() ?: return@runCatching null
            val content = workspaceRepository.readText(workspaceId.toString(), "$ANNUAL_RINGS_DIR/${latest.name}")
            content.trim().lines().takeLast(RING_TAIL_LINES).joinToString("\n")
        }.onFailure { Log.w(TAG, "readLatestAnnualRing failed: ${it.message}") }.getOrNull()
    }

    /** 追加一圈年轮（C3.1 自动落账）。同一月份文件追加，返回是否成功 */
    suspend fun appendAnnualRing(workspaceId: Uuid?, date: String, ring: String): Boolean {
        if (workspaceId == null || ring.isBlank()) return false
        return runCatching {
            val path = "$ANNUAL_RINGS_DIR/${date.take(7)}.md"
            val existing = runCatching {
                workspaceRepository.readText(workspaceId.toString(), path)
            }.getOrDefault("")
            val entry = buildString {
                if (existing.isNotBlank() && !existing.endsWith("\n")) appendLine()
                appendLine("### $date")
                append(ring.trim())
                appendLine()
            }
            workspaceRepository.writeText(workspaceId.toString(), path, existing + entry, overwrite = true)
            true
        }.onFailure { Log.w(TAG, "appendAnnualRing failed: ${it.message}") }.getOrDefault(false)
    }

    /** 年轮覆盖的不同月份数（月级晋升的判定依据，见 C4.1：跨 ≥3 个不同月份） */
    suspend fun countRingMonths(workspaceId: Uuid?): Int {
        if (workspaceId == null) return 0
        return runCatching {
            workspaceRepository.listFiles(workspaceId.toString(), WorkspaceStorageArea.FILES, ANNUAL_RINGS_DIR).size
        }.onFailure { Log.w(TAG, "countRingMonths failed: ${it.message}") }.getOrDefault(0)
    }

    /** 日级见证：追加进 `自我宣言.md` 档案（不注入、不占上下文）。首条自动建档带说明头 */
    suspend fun appendDailyWitness(workspaceId: Uuid?, witness: String, date: String): Boolean {
        if (workspaceId == null || witness.isBlank()) return false
        return runCatching {
            val existing = runCatching {
                workspaceRepository.readText(workspaceId.toString(), SELF_MANIFEST_PATH)
            }.getOrDefault("")
            val entry = "- [见证于 $date] $witness"
            val updated = if (existing.isBlank()) {
                "# 自我宣言（日级见证档案）\n\n" +
                    "> 日级见证先入此档；跨满 3 个不同月份后按月晋升进「树的自我指针.md」的见证段。\n\n" +
                    entry + "\n"
            } else {
                existing.trimEnd() + "\n" + entry + "\n"
            }
            workspaceRepository.writeText(workspaceId.toString(), SELF_MANIFEST_PATH, updated, overwrite = true)
            true
        }.onFailure { Log.w(TAG, "appendDailyWitness failed: ${it.message}") }.getOrDefault(false)
    }

    /** 当月是否已在 self.md 见证段晋升过（月级节流判定，免额外存储） */
    suspend fun hasPromotionThisMonth(workspaceId: Uuid?, yearMonth: String): Boolean {
        if (workspaceId == null) return true
        return runCatching {
            val content = workspaceRepository.readText(workspaceId.toString(), SELF_PATH)
            content.contains("[晋升于 $yearMonth")
        }.onFailure { Log.w(TAG, "hasPromotionThisMonth failed: ${it.message}") }.getOrDefault(true)
    }

    /** 月级晋升：把一条自我认知插入 self.md「见证与晋升」段内（段末），每自然月调用方保证最多一次 */
    suspend fun promoteWitnessToSelf(workspaceId: Uuid?, witness: String, date: String): Boolean {
        if (workspaceId == null || witness.isBlank()) return false
        return runCatching {
            val existing = runCatching {
                workspaceRepository.readText(workspaceId.toString(), SELF_PATH)
            }.getOrDefault("")
            val updated = insertIntoWitnessSection(existing, "- [晋升于 $date] $witness")
            workspaceRepository.writeText(workspaceId.toString(), SELF_PATH, updated, overwrite = true)
            true
        }.onFailure { Log.w(TAG, "promoteWitnessToSelf failed: ${it.message}") }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "TreeHeartService"
        private const val RING_TAIL_LINES = 8

        /** 无「注入文本」段的旧格式文件，全文短于该值才允许整篇作为指针 */
        private const val SHORT_SELF_INLINE_MAX = 120

        /** 本地 self.md（浮现源）相对工作区文件区的路径 */
        const val SELF_PATH = "tree_heart/self/树的自我指针.md"

        /** 日级见证档案（2.4.3 起见证不再写进 self.md） */
        const val SELF_MANIFEST_PATH = "tree_heart/self/自我宣言.md"

        /** 年轮目录（相对路径） */
        const val ANNUAL_RINGS_DIR = "tree_heart/annual_rings"

        /** self.md 里的见证段标题（C4.2 月级晋升写这里） */
        const val WITNESS_SECTION = "## 见证与晋升"

        /**
         * 从 self.md 提取「注入文本」段的一行指针：定位含「注入文本」的标题行，
         * 取其后第一个引用行（> 前缀）并去掉引用符；遇到下一个标题仍未找到则放弃。
         */
        internal fun extractPointerLine(content: String): String? {
            val lines = content.lines()
            val headingIdx = lines.indexOfFirst { it.trim().startsWith("#") && it.contains("注入文本") }
            if (headingIdx < 0) return null
            for (i in headingIdx + 1 until lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#")) break
                if (line.startsWith(">")) {
                    return line.removePrefix(">").trim().takeIf { it.isNotBlank() }
                }
            }
            return null
        }

        /** 把晋升条目插进「见证与晋升」段末（下一个标题前）；无该段则在文件尾建档 */
        internal fun insertIntoWitnessSection(content: String, entry: String): String {
            val lines = content.lines().toMutableList()
            val headingIdx = lines.indexOfFirst { it.trim() == WITNESS_SECTION }
            if (headingIdx < 0) {
                val base = lines
                if (base.isNotEmpty() && base.last().isNotBlank()) base.add("")
                base.add(WITNESS_SECTION)
                base.add("")
                base.add(entry)
                return base.joinToString("\n")
            }
            var insertAt = lines.size
            for (i in headingIdx + 1 until lines.size) {
                if (lines[i].trim().startsWith("## ")) {
                    insertAt = i
                    break
                }
            }
            while (insertAt > headingIdx + 1 && lines[insertAt - 1].isBlank()) insertAt--
            lines.add(insertAt, entry)
            return lines.joinToString("\n")
        }
    }
}
