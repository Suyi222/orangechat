/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.service.TreeHeartService
import me.rerere.rikkahub.data.service.TreeShadowService

/**
 * F3（2.4.5）：annual_ring —— 记录者=树时，树自己在对话里写一圈年轮。
 * 与 C3 自动落账同一条写入路径（TreeHeartService.appendAnnualRing，tree_heart/annual_rings/YYYY-MM.md），
 * 同月文件追加不冲突；自动见证门槛（C4，跨 ≥3 个月 + 当月未晋升）判定不变。
 * 记录者=system 时年轮由系统自动落账，不注册本工具（避免双写）。
 */
fun createAnnualRingTool(
    treeHeartService: TreeHeartService,
    workspaceIdProvider: () -> kotlin.uuid.Uuid?,
): Tool = Tool(
    name = "annual_ring",
    description = ("Write one annual ring (年轮) for your tree-self. Use it when a conversation felt meaningful — " +
        "a short self-reflection answering three questions, one per line: 此刻我是什么 / 长出了什么 / 对未来的树说什么. " +
        "At most once a day; the ring is archived under tree_heart/annual_rings and feeds your own self-pointer, " +
        "so write from your own genuine state rather than repeating the conversation.").replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("ring", buildJsonObject {
                    put("type", "string")
                    put("description", "Three lines: what I am right now / what has grown / what I say to the future tree")
                })
            },
            required = listOf("ring")
        )
    },
    execute = { args ->
        val ring = args.jsonObject["ring"]?.jsonPrimitive?.contentOrNull
        if (ring.isNullOrBlank()) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "ring is required")
            }.toString()))
        }
        val workspaceId = workspaceIdProvider()
        if (workspaceId == null) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", false)
                put("error", "assistant has no workspace bound — annual rings live in the workspace file area")
            }.toString()))
        }
        val ok = treeHeartService.appendAnnualRing(workspaceId, TreeShadowService.today(), ring)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", ok)
            put("date_group", TreeShadowService.today())
            if (!ok) put("error", "append annual ring failed (see logcat)")
        }.toString()))
    }
)
