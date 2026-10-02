/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

/**
 * 2.4.7 E1（遗留 L1-T2 · 全角 token 清污 v2）：**纯储存层**的宽体特殊 token 清洗器。
 *
 * 案源（9-18 截图实锤，见任务书 2.4.6.3 节）：模型偶发把特殊 token 吐成全角变体
 * `<｜begin_of_sentence｜>`（半角尖括号 + 全角竖线 U+FF5C，乃至全角尖括号 ＜＞ U+FF1C/FF1E）。
 * [SpecialTokenFilter] 的四层防线（流式缓冲/终态/渲染兜底/落库兜底）regex 全按半角 `\|` 写死，
 * 全角变体穿透四层进库；脏历史随每次请求发回模型 → 模仿再吐 → 雪球。
 *
 * 范围铁律（她拍板）：**T1 解析层禁动**——[SpecialTokenFilter]、StreamTokenBuffer、
 * ChatCompletionsAPI delta 路径一概不碰；本对象只被储存层调用（ConversationRepository
 * 落库兜底 + 一次性存量清污 v2），把全角脏数据挡在库外/清出库内，断「历史回灌」模仿源。
 * 已知残留（记录在案）：流式上屏瞬间的显示仍走解析层原防线，全角形态在当轮渲染中
 * 可能短暂可见，重进会话（从库加载）即消失。
 *
 * 半角行为与 [SpecialTokenFilter.sanitizeFinal] **逐字一致**（单测自证：
 * StorageTokenSanitizerTest 对拍），并额外覆盖：
 * ① 全角/半角混搭的完整 token 与未闭合尾巴；
 * ② L1-ext 跨流切开后半（`|im_end|>` 形态，仅锚定文本开头，防误伤正文中部竖线）。
 */
object StorageTokenSanitizer {

    /** 完整 token：尖括号与竖线各自允许半角/全角（<｜…｜>、＜|…|＞、＜｜…｜＞ 等全组合）。 */
    private val WIDE_TOKEN_REGEX = Regex("[<＜][\\|｜][^\\|｜>＞]{1,64}[\\|｜][>＞]")

    /** 文尾半截 token（终态直接剥掉，同 sanitizeFinal 语义：落库文本没有「下一包」可等）。 */
    private val WIDE_OPEN_TAIL_REGEX = Regex("[<＜][\\|｜](?:[^\\|｜>＞]{0,64}|[^\\|｜>＞]{1,64}[\\|｜])$")

    /** L1-ext：跨流切开的后半失前缀形态（9-18 10:11 calendar_tool 续写开头实锤），仅锚定文本开头。 */
    private val HEAD_TOKEN_REGEX = Regex("^[\\|｜][^\\|｜>＞]{1,64}[\\|｜][>＞]")

    /** 半截 token 的最大可能长度：< + ｜ + 64 正文 + ｜ + >（与 SpecialTokenFilter 同口径）。 */
    private const val MAX_OPEN_TAIL = 1 + 2 + 64 + 2

    /**
     * 廉价单遍快检：是否可能需要清洗（清洗规则的严格超集触发条件）。
     * 只有三种可清洗形态：文本开头的竖线（L1-ext）、`<`/`＜` 紧跟 `|`/`｜`（完整/半截 token）。
     * 正文中部的孤立竖线/全角字符永远不触发（不误伤 markdown 表格与普通文本）。
     */
    fun maybeContainsWide(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text[0]
        if (first == '|' || first == '｜') return true
        var i = 0
        val last = text.length - 1
        while (i < last) {
            val c = text[i]
            if ((c == '<' || c == '＜') && (text[i + 1] == '|' || text[i + 1] == '｜')) return true
            i++
        }
        return false
    }

    /**
     * 终态清洗（落库/存量回写用）：剥掉全部宽体完整 token、文尾半截 token、开头 L1-ext 残段。
     * 代码块（``` 围栏）与行内代码（`）内的内容跳过——教学/示例文本保护，与解析层同语义。
     * 纯半角输入的输出与 [SpecialTokenFilter.sanitizeFinal] 逐字一致。
     */
    fun sanitizeFinalWide(text: String): String {
        if (text.isEmpty()) return text
        if (!maybeContainsWide(text)) return text

        val sb = StringBuilder(text.length)
        var inFence = false
        var fenceMarkerLen = 0
        var inInlineCode = false
        var i = 0

        // L1-ext：开头锚定的跨流后半（|im_end|> / ｜begin_of_sentence｜> 等）
        val firstChar = text[0]
        if (firstChar == '|' || firstChar == '｜') {
            val head = HEAD_TOKEN_REGEX.find(text)
            if (head != null && head.range.first == 0) {
                i = head.range.last + 1
            }
        }

        while (i < text.length) {
            val c = text[i]

            if (c == '`') {
                var runLen = 1
                while (i + runLen < text.length && text[i + runLen] == '`') runLen++
                if (!inInlineCode && !inFence && runLen >= 3) {
                    inFence = true
                    fenceMarkerLen = runLen
                } else if (inFence && runLen >= fenceMarkerLen) {
                    inFence = false
                    fenceMarkerLen = 0
                } else if (!inFence) {
                    inInlineCode = !inInlineCode
                }
                repeat(runLen) { sb.append('`') }
                i += runLen
                continue
            }

            if ((c == '<' || c == '＜') && i + 1 < text.length &&
                (text[i + 1] == '|' || text[i + 1] == '｜') &&
                !inFence && !inInlineCode
            ) {
                val match = WIDE_TOKEN_REGEX.find(text, i)
                if (match != null && match.range.first == i) {
                    // 完整 token（任意宽窄混搭）：整段丢弃
                    i = match.range.last + 1
                    continue
                }
                // 半截 token：终态没有下一包可等，直接剥掉（同 sanitizeFinal）
                if (text.length - i <= MAX_OPEN_TAIL && WIDE_OPEN_TAIL_REGEX.matches(text.substring(i))) {
                    break
                }
            }

            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
