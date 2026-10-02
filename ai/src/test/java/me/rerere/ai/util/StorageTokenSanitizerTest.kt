/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.4.7 E1 自证单测：
 * 1. 半角行为与 SpecialTokenFilter.sanitizeFinal 逐字一致（验收口径「半角行为逐字不变」）；
 * 2. 全角/混搭变体被剥掉（9-18 实锤形态）；
 * 3. L1-ext 开头残段被剥、正文中部竖线不误伤；
 * 4. 代码块/行内代码保护对宽体同样生效；
 * 5. 快检与清洗结果一致（不漏检）。
 *
 * 注：完整特殊 token 字面量会被部分工具链/日志层过滤（L1 同源问题），测试内用拼接构造。
 */
class StorageTokenSanitizerTest {

    private val bos = "<" + "|begin_of_sentence|" + ">"
    private val imEnd = "<" + "|im_end|" + ">"
    private val user = "<" + "|user|" + ">"

    private fun halfwidthSamples() = listOf(
        "普通文本，什么也没有",
        "head" + bos + "tail",
        "尾部未闭合 <" + "|abc",
        "尾部未闭合带竖线 <" + "|im_end|",
        user + "开头就是 token",
        "multi " + bos + " middle " + imEnd + " end",
        "markdown table | col | and quote > normal text",
        "fence ```" + bos + "``` after",
        "inline `" + imEnd + "` protection",
        "a < b and c | d normal",
        "< short | not token",
    )

    @Test
    fun halfwidthBehaviorIdenticalToSpecialTokenFilter() {
        for (s in halfwidthSamples()) {
            assertEquals(
                "半角行为必须与解析层终态清洗逐字一致: <$s>",
                SpecialTokenFilter.sanitizeFinal(s),
                StorageTokenSanitizer.sanitizeFinalWide(s),
            )
        }
    }

    @Test
    fun wideVariantsStripped() {
        assertEquals("", StorageTokenSanitizer.sanitizeFinalWide("<｜begin_of_sentence｜>"))
        assertEquals("正文", StorageTokenSanitizer.sanitizeFinalWide("正文<｜im_end｜>"))
        assertEquals("正文", StorageTokenSanitizer.sanitizeFinalWide("正文＜|im_end|＞"))
        assertEquals("正文", StorageTokenSanitizer.sanitizeFinalWide("正文＜｜user｜＞"))
        // 混搭开尾（正文里未闭合）
        assertEquals("正文", StorageTokenSanitizer.sanitizeFinalWide("正文<｜im_end"))
        assertEquals("正文", StorageTokenSanitizer.sanitizeFinalWide("正文＜|im_end｜"))
    }

    @Test
    fun headFormStrippedOnlyAtStart() {
        // L1-ext：跨流切开、失去前缀的后半，锚定文本开头
        assertEquals("续写正文", StorageTokenSanitizer.sanitizeFinalWide("|im_end|>续写正文"))
        assertEquals("续写正文", StorageTokenSanitizer.sanitizeFinalWide("｜begin_of_sentence｜>续写正文"))
        // 正文中部同形态不动手（防误伤 markdown 表格/竖线引用）
        assertEquals("表格 |a|> b 原样", StorageTokenSanitizer.sanitizeFinalWide("表格 |a|> b 原样"))
        assertEquals("x |im_end|> y", StorageTokenSanitizer.sanitizeFinalWide("x |im_end|> y"))
    }

    @Test
    fun codeProtectionAppliesToWide() {
        assertEquals("```<｜begin_of_sentence｜>```", StorageTokenSanitizer.sanitizeFinalWide("```<｜begin_of_sentence｜>```"))
        assertEquals("`<｜im_end｜>`", StorageTokenSanitizer.sanitizeFinalWide("`<｜im_end｜>`"))
        // 围栏外的宽体照剥
        assertEquals("```code``` 正文", StorageTokenSanitizer.sanitizeFinalWide("```code```<｜user｜> 正文"))
    }

    @Test
    fun quickCheckConsistency() {
        val all = halfwidthSamples() + listOf(
            "<｜x｜>", "|im_end|>rest", "＜|a|＞", "正文<｜im_end",
            "plain text", "表格 |a|> b", "全角尖括号没有竖线 ＜", "plain ｜ fullwidth bar 正文",
        )
        for (s in all) {
            val changed = StorageTokenSanitizer.sanitizeFinalWide(s) != s
            if (changed) {
                assertTrue("快检漏报（清洗生效但快检未触发）: <$s>", StorageTokenSanitizer.maybeContainsWide(s))
            }
        }
        // 无 token 文本不得误触发快检（省掉无谓清洗遍历）
        assertFalse(StorageTokenSanitizer.maybeContainsWide("plain text"))
        assertFalse(StorageTokenSanitizer.maybeContainsWide("表格 |a|> b"))
        assertFalse(StorageTokenSanitizer.maybeContainsWide("全角尖括号没有竖线 ＜"))
    }
}
