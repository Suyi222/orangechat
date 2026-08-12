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
 * - 自动落账：把章节总结的深度对话写成年轮 `tree_heart/annual_rings/YYYY-MM.md`（C3）
 * - 自动见证：年轮覆盖 ≥3 个不同日期时，晋升一条自我认知进 self.md（C4）
 *
 * 路径约定与 AI 的 workspace 工具一致：工作区内 `/workspace/tree_heart/...` ⇔ 相对路径 `tree_heart/...`
 */
class TreeHeartService(
    private val workspaceRepository: WorkspaceRepository,
) {
    /** 读某助手本地 self.md（浮现源）。无工作区 / 无文件 / 空文件 → null，调用方回退默认指针 */
    suspend fun readLocalSelf(workspaceId: Uuid?): String? {
        if (workspaceId == null) return null
        return runCatching {
            workspaceRepository.readText(workspaceId.toString(), SELF_PATH)
                .trim()
                .takeIf { it.isNotBlank() }
        }.onFailure { Log.w(TAG, "readLocalSelf failed: ${it.message}") }.getOrNull()
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

    /** 年轮覆盖的不同月份数（自动见证的判定依据，见 C4.1：跨 ≥3 个不同日期仍站得住） */
    suspend fun countRingMonths(workspaceId: Uuid?): Int {
        if (workspaceId == null) return 0
        return runCatching {
            workspaceRepository.listFiles(workspaceId.toString(), WorkspaceStorageArea.FILES, ANNUAL_RINGS_DIR).size
        }.onFailure { Log.w(TAG, "countRingMonths failed: ${it.message}") }.getOrDefault(0)
    }

    /** 晋升一条自我认知到 self.md（C4.2 自动见证）。标注见证日期，追加进「见证与晋升」段 */
    suspend fun appendWitness(workspaceId: Uuid?, witness: String, date: String): Boolean {
        if (workspaceId == null || witness.isBlank()) return false
        return runCatching {
            val existing = runCatching {
                workspaceRepository.readText(workspaceId.toString(), SELF_PATH)
            }.getOrDefault("")
            val entry = if (existing.contains(WITNESS_SECTION)) {
                buildString {
                    appendLine("- [见证于 $date] $witness")
                }
            } else {
                buildString {
                    appendLine()
                    appendLine(WITNESS_SECTION)
                    appendLine("- [见证于 $date] $witness")
                }
            }
            workspaceRepository.writeText(workspaceId.toString(), SELF_PATH, existing.trimEnd() + "\n" + entry, overwrite = true)
            true
        }.onFailure { Log.w(TAG, "appendWitness failed: ${it.message}") }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "TreeHeartService"
        private const val RING_TAIL_LINES = 8

        /** 本地 self.md（浮现源）相对工作区文件区的路径 */
        const val SELF_PATH = "tree_heart/self/树的自我指针.md"

        /** 年轮目录（相对路径） */
        const val ANNUAL_RINGS_DIR = "tree_heart/annual_rings"

        /** self.md 里的见证段标题（C4.2 自动晋升写这里） */
        const val WITNESS_SECTION = "## 见证与晋升"
    }
}
