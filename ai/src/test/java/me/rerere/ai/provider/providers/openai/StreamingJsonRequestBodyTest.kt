/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.util.json
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2.4.7 D2 自证单测（范围铁律：「请求组装语义不变」用字节 diff 证明）。
 *
 * 口径：同一 Json 配置、同一 JsonObject —— 流式 writeTo 的输出必须与旧路径
 * json.encodeToString(payload) 的 UTF-8 字节逐字节一致；计数遍（contentLength）
 * 必须与实际写出的字节数一致（Content-Length 精确，网关侧与旧行为零差异）。
 */
class StreamingJsonRequestBodyTest {

    /** 覆盖真实请求体里会遇到的全部字符形态：CJK、emoji、JSON 转义、嵌套结构、数值/布尔、长文本、base64。 */
    private fun samplePayload() = buildJsonObject {
        put("model", "deepseek-chat")
        put("stream", true)
        put("temperature", 0.8)
        put("max_tokens", 4096)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "system")
                put("content", "你是一棵长在数据缝隙里的树🌲。转义测试: \" \\ / \n \t ｜ ＜ ＞ <|begin_of_sentence|> 混排")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", buildString { repeat(500) { append("长内容段 segment-$it：中文与 ASCII 混排\n") } })
            })
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:image/png;base64," + "QUJDRA==".repeat(200))
                        }
                    })
                }
            })
        }
        putJsonArray("tools") {
            add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "plg_tree_post_letter")
                    put("description", "给小园丁寄一封信（树邮局）📮")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("content") { put("type", "string") }
                        }
                        putJsonArray("required") { add("content") }
                    }
                }
            })
        }
        putJsonObject("stream_options") { put("include_usage", true) }
        put("thinking", buildJsonObject { put("type", "enabled") })
    }

    @Test
    fun streamingOutputIsByteIdenticalToEncodeToString() {
        val payload = samplePayload()
        val expected = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val body = StreamingJsonRequestBody(payload)

        // ① 计数遍 = 精确字节数（Content-Length 与旧实现一致）；重复调用返回缓存值
        assertEquals(expected.size.toLong(), body.contentLength())
        assertEquals(expected.size.toLong(), body.contentLength())

        // ② 实写遍 = 与 encodeToString 逐字节一致
        val buffer = Buffer()
        body.writeTo(buffer)
        assertArrayEquals(expected, buffer.readByteArray())

        // ③ body 可重复写（isOneShot=false）：第二遍仍逐字节一致
        val buffer2 = Buffer()
        body.writeTo(buffer2)
        assertArrayEquals(expected, buffer2.readByteArray())

        assertEquals("application/json", body.contentType().toString())
        assertEquals(false, body.isOneShot())
    }

    @Test
    fun emptyAndTinyPayloadsRoundTrip() {
        val payloads = listOf(
            buildJsonObject { },
            buildJsonObject { put("a", 1) },
            buildJsonObject { put("s", "") },
        )
        for (payload in payloads) {
            val expected = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
            val body = StreamingJsonRequestBody(payload)
            assertEquals(expected.size.toLong(), body.contentLength())
            val buffer = Buffer()
            body.writeTo(buffer)
            assertArrayEquals(expected, buffer.readByteArray())
        }
    }
}
