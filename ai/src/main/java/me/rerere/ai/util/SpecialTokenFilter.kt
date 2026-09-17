/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

/**
 * 模型特殊 token 清洗（2.4.6 H1；2.4.6.2 全层铺开升级）
 *
 * 背景：上游模型在「工具调用后的续写」新开 stream 时，会把 `<|begin_of_sentence|>` /
 * `<|end_of_sentence|>` 这类控制 token 原样带进正文（9-15 set_timer、9-16 calendar_tool
 * 两次复现，全仓库原先零过滤）。
 *
 * 四条约束：
 * 1. **跨 chunk 半截**：`<|begin_` 与 `of_sentence|>` 可能分两包到达。
 *    解法：每次都清洗「已累积的全文」而不是只看当前 delta——未闭合的 `<|xxx` 尾巴
 *    原样保留在文本里，下一包拼上后整体再清一次即被删除。
 *    2.4.6.2 追加：源头流（ChatCompletionsAPI）用 openTailLength 做跨 delta 尾巴暂扣，
 *    半截 token 根本不进 part——专治「Tool/Reasoning part 打断 Text 拼接导致拼不回完整 token」。
 * 2. **不误伤代码**：``` 围栏与行内 `code` 内的 `<|x|>` 字样是示例内容，保留。
 *    2.4.6.2 修正（逃逸假设④）：行内 code 保护**不跨行**——旧实现正文里出现奇数个反引号
 *    或未闭合 `code 时，inInlineCode 永久卡 true，其后所有 token 被当示例原样保留，
 *    命中与否取决于每条消息的反引号奇偶性 =「命中不稳定」的根源之一。
 * 3. **流尾残尾必须剥**：流以未闭合半截 token 结尾时「保留等下一包」等不到下一包，
 *    终态清洗（落库/渲染/流结束）用 sanitizeFinal 直接剥掉。
 * 4. **廉价**：绝大多数正文不含 `<|`，一次 indexOf 短路原样返回。
 *
 * 调用点（2.4.6.2 全层）：
 *   ① 源头：ChatCompletionsAPI parseMessage（delta 级清洗）+ StreamTokenBuffer（跨 delta 尾巴暂扣）
 *   ② 流式拼接：ai/ui/Message.kt appendChunk Text/Reasoning 分支（累积全文清洗）
 *   ③ 渲染兜底：ChatMessageCot.sanitizedForRender（上屏前 sanitizeFinal）
 *   ④ 落库兜底 + 存量清污：ConversationRepository（sanitizeFinal + 一次性扫脏行回写）
 */
object SpecialTokenFilter {

    /** 形如 `<|begin_of_sentence|>`：尖括号包竖线，中间不含竖线/右尖括号，1~64 字符。 */
    private val TOKEN_REGEX = Regex("<\\|[^|>]{1,64}\\|>")

    /**
     * 可能是被切开的半截 token：`<|xxx` 或 `<|xxx|`（只差收尾的 `>`）延伸到文本末尾。
     * 2.4.6.2 扩展了 `<|xxx|` 形态——它同样能被下一包的 `>` 补全成完整 token。
     */
    private val OPEN_TAIL_REGEX = Regex("<\\|(?:[^|>]{0,64}|[^|>]{1,64}\\|)$")

    /** 半截 token 的可能最大长度：2 个前缀字符 + 64 个主体字符 + 1 个收尾竖线。 */
    private const val MAX_OPEN_TAIL = 67

    /**
     * 清洗一段（通常是累积中的）正文：删完整控制 token，未闭合尾巴保留等下一包。
     * 返回清洗结果；不含 `<|` 时原样返回同一个对象（零分配短路）。
     */
    fun sanitize(text: String): String = sanitizeInternal(text, stripOpenTail = false)

    /**
     * 终态清洗（2.4.6.2）：在 [sanitize] 基础上把文末未闭合的 `<|xxx` 尾巴一并剥掉。
     * 用于流结束/落库/渲染——「等下一包」在这些时点永远等不到下一包，
     * 不剥就是永久残留（2.4.6 逃逸假设④附带发现）。
     */
    fun sanitizeFinal(text: String): String = sanitizeInternal(text, stripOpenTail = true)

    /**
     * 文本结尾是否挂着未闭合的半截 token（`<|body` 或 `<|body|`），返回其长度（0 = 没有）。
     * 给源头流的「跨 delta 尾巴暂扣」用（ChatCompletionsAPI.StreamTokenBuffer）。
     */
    fun openTailLength(text: String): Int {
        if (text.length < 2 || !text.contains("<|")) return 0
        val idx = text.lastIndexOf("<|")
        val tail = text.substring(idx)
        if (tail.length > MAX_OPEN_TAIL) return 0
        return if (OPEN_TAIL_REGEX.matches(tail)) tail.length else 0
    }

    private fun sanitizeInternal(text: String, stripOpenTail: Boolean): String {
        if (text.length < 2 || !text.contains("<|")) return text

        val sb = StringBuilder(text.length)
        var i = 0
        var inFence = false      // ``` 代码块（状态跨行，维持整块保护）
        var inInlineCode = false // ` 行内代码（2.4.6.2：不跨行，换行即复位）
        while (i < text.length) {
            val c = text[i]

            // 2.4.6.2 围栏修正（逃逸假设④）：单反引号只在同一行内配对，
            // 奇数个反引号/未闭合行内 code 不再污染其后全文的判定。
            if (c == '\n') inInlineCode = false

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
                // 半截 token（本包结尾被切开）：流式中保留等下一包；终态清洗直接剥掉
                if (text.length - i <= MAX_OPEN_TAIL && OPEN_TAIL_REGEX.matches(text.substring(i))) {
                    if (!stripOpenTail) sb.append(text, i, text.length)
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
