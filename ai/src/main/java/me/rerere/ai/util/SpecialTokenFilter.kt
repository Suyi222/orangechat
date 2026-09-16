/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

/**
 * 模型特殊 token 清洗（2.4.6 H1）
 *
 * 背景：上游模型在「工具调用后的续写」新开 stream 时，会把 `<|begin_of_sentence|>` /
 * `<|end_of_sentence|>` 这类控制 token 原样带进正文（9-15 set_timer、9-16 calendar_tool
 * 两次复现，全仓库原先零过滤）。
 *
 * 三条约束：
 * 1. **跨 chunk 半截**：`<|begin_` 与 `of_sentence|>` 可能分两包到达。
 *    解法：每次都清洗「已累积的全文」而不是只看当前 delta——未闭合的 `<|xxx` 尾巴
 *    原样保留在文本里，下一包拼上后整体再清一次即被删除。
 * 2. **不误伤代码**：``` 围栏与行内 `code` 内的 `<|x|>` 字样是示例内容，保留。
 * 3. **廉价**：绝大多数正文不含 `<|`，一次 indexOf 短路原样返回。
 *
 * 调用点：ai/ui/Message.kt appendChunk 的 Text 分支（流式实时清洗）
 *        + ConversationRepository 落库前兜底（防其它写入路径漏网）。
 */
object SpecialTokenFilter {

    /** 形如 `<|begin_of_sentence|>`：尖括号包竖线，中间不含竖线/右尖括号，1~64 字符。 */
    private val TOKEN_REGEX = Regex("<\\|[^|>]{1,64}\\|>")

    /** 可能是被切开的半截 token：`<|xxx` 一直延伸到文本末尾且没出现 `|` / `>`。 */
    private val OPEN_TAIL_REGEX = Regex("<\\|[^|>]{0,64}$")

    /** 半截 token 的可能最大长度：2 个前缀字符 + 64 个主体字符。 */
    private const val MAX_OPEN_TAIL = 66

    /**
     * 清洗一段（通常是累积后的）正文。
     * 返回清洗结果；不含 `<|` 时原样返回同一个对象（零分配短路）。
     */
    fun sanitize(text: String): String {
        if (text.length < 4 || !text.contains("<|")) return text

        val sb = StringBuilder(text.length)
        var i = 0
        var inFence = false      // ``` 代码块
        var inInlineCode = false // ` 行内代码
        while (i < text.length) {
            val c = text[i]

            // 反引号状态机：>=3 个连续反引号切换围栏，单个切换行内代码
            if (c == '`') {
                var j = i
                while (j < text.length && text[j] == '`') j++
                val runLength = j - i
                if (runLength >= 3) {
                    inFence = !inFence
                } else if (!inFence) {
                    inInlineCode = !inInlineCode
                }
                sb.append(text, i, j)
                i = j
                continue
            }

            if (c == '<' && i + 1 < text.length && text[i + 1] == '|' && !inFence && !inInlineCode) {
                val match = TOKEN_REGEX.find(text, i)
                if (match != null && match.range.first == i) {
                    // 完整 token：整段丢弃
                    i = match.range.last + 1
                    continue
                }
                // 半截 token（本包结尾被切开）：原样保留，等下一包拼上后整体再清
                if (text.length - i <= MAX_OPEN_TAIL && OPEN_TAIL_REGEX.matches(text.substring(i))) {
                    sb.append(text, i, text.length)
                    break
                }
            }

            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** 是否含疑似特殊 token 片段（给落库兜底做廉价的 quick check）。 */
    fun maybeContainsToken(text: String): Boolean = text.contains("<|")
}
