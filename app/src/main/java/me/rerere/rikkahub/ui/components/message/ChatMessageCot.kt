/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.SpecialTokenFilter

/**
 * 思考步骤类型，用于分组 Reasoning 和 Tool
 */
sealed interface ThinkingStep {
    data class ReasoningStep(
        val reasoning: UIMessagePart.Reasoning,
    ) : ThinkingStep

    data class ToolStep(
        val tool: UIMessagePart.Tool,
    ) : ThinkingStep
}

/**
 * 消息部分块类型，用于保持渲染顺序
 */
sealed interface MessagePartBlock {
    data class ThinkingBlock(val steps: List<ThinkingStep>) : MessagePartBlock
    data class ContentBlock(val part: UIMessagePart, val index: Int) : MessagePartBlock
}

/**
 * 将 parts 分组成 ThinkingBlock 和 ContentBlock
 * 连续的 Reasoning 和 Tool 会被分组到一个 ThinkingBlock 中
 */
fun List<UIMessagePart>.groupMessageParts(): List<MessagePartBlock> {
    val result = mutableListOf<MessagePartBlock>()
    var currentThinkingSteps = mutableListOf<ThinkingStep>()

    fun flushThinkingSteps() {
        if (currentThinkingSteps.isNotEmpty()) {
            result.add(MessagePartBlock.ThinkingBlock(currentThinkingSteps.toList()))
            currentThinkingSteps = mutableListOf()
        }
    }

    this.fastForEachIndexed { index, part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                currentThinkingSteps.add(ThinkingStep.ReasoningStep(part))
            }

            is UIMessagePart.Tool -> {
                currentThinkingSteps.add(ThinkingStep.ToolStep(part))
            }

            else -> {
                flushThinkingSteps()
                result.add(MessagePartBlock.ContentBlock(part, index))
            }
        }
    }
    flushThinkingSteps()
    return result
}

/**
 * 2.4.6.2 token 全层·渲染兜底：上屏前对正文/思考 part 统一做终态清洗（与落库兜底双保险）。
 * 用 sanitizeFinal——渲染时点是「当前终态」，流式中挂着的半截 token 尾巴直接隐藏，
 * 下一个 delta 到来后随累积文本重新判定；不含 "<|" 时零拷贝短路，渲染无感。
 */
fun List<UIMessagePart>.sanitizedForRender(): List<UIMessagePart> {
    val dirty = any { part ->
        when (part) {
            is UIMessagePart.Text -> SpecialTokenFilter.maybeContainsToken(part.text)
            is UIMessagePart.Reasoning -> SpecialTokenFilter.maybeContainsToken(part.reasoning)
            else -> false
        }
    }
    if (!dirty) return this
    return map { part ->
        when (part) {
            is UIMessagePart.Text -> part.copy(text = SpecialTokenFilter.sanitizeFinal(part.text))
            is UIMessagePart.Reasoning -> part.copy(reasoning = SpecialTokenFilter.sanitizeFinal(part.reasoning))
            else -> part
        }
    }
}
