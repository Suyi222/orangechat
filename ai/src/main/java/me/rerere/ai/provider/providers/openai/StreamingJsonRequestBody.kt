/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.util.json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.OutputStream

/**
 * 2.4.7 D2（OOM 第二刀·砍「山峰」）：请求体流式序列化。
 *
 * 旧路径 `json.encodeToString(requestBody).toRequestBody(...)` 每次发送要爬三座山：
 * ① encodeToString 把整个请求体物化成 String——UTF-16 char[]，7-8MB 请求体 ≈ 13-15MB
 *    连续大块（9-27 OOM 新栈 StringFactory.newStringFromChars ← Json.encodeToString ←
 *    streamText 的分配主体）；
 * ② toRequestBody(String) 再把 String 整体 UTF-8 编码成 byte[]（又一份 7-8MB）；
 * ③ 发送期间 byte[] 全程驻留到写完。
 *
 * 新路径：以上全部消失——encodeToStream 直接写进 okio sink（8KB 级 segment），
 * 堆上任何时刻只有小缓冲，请求体再大也不再产生大块分配。
 *
 * **形态取舍（两段计数，而非 chunked one-shot）**：
 * - contentLength() 先走一遍「只计数 OutputStream」得到精确字节数（第一遍序列化，零分配）；
 * - writeTo() 再真正流式写出（第二遍序列化）；
 * - 线上字节与旧行为**逐字节一致**（含 Content-Length 头）→ 网关兼容零风险；
 * - body 可重复写（isOneShot 保持默认 false）→ 不放弃 okhttp 的传输层重试。
 * 代价是每次请求序列化两遍（纯 CPU、无大分配；且 D1 已把旧实现日志里的第三遍删掉，
 * 总 CPU 反而比旧实现低）。chunked+isOneShot 单遍形态列为备选：若未来实测有性能需要
 * 且两平台网关验证通过，可再切换。本批验收延后、又是考前最后一个施工窗口，取稳。
 *
 * 组装语义不变的自证：StreamingJsonRequestBodyTest（JVM 单测）验证流式输出与
 * encodeToString 逐字节一致、计数遍与实际写遍字节数一致——同一 Json 配置同一
 * JsonObject，序列化确定性保证「请求组装语义零改动」（范围铁律要求）。
 */
@OptIn(ExperimentalSerializationApi::class)
internal class StreamingJsonRequestBody(
    private val payload: JsonObject,
) : RequestBody() {

    private val mediaType: MediaType = "application/json".toMediaType()

    /** -1 = 尚未计数；计数遍结果缓存（okhttp 一次请求通常只调一次 contentLength）。 */
    private var countedLength: Long = -1L

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long {
        if (countedLength < 0) {
            var count = 0L
            json.encodeToStream(payload, object : OutputStream() {
                override fun write(b: Int) {
                    count++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    count += len
                }
            })
            countedLength = count
        }
        return countedLength
    }

    override fun writeTo(sink: BufferedSink) {
        val out = sink.outputStream()
        json.encodeToStream(payload, out)
        // 只 flush 不 close：close 会连带关闭 okhttp 的 sink，sink 生命周期归 okhttp 管。
        out.flush()
    }
}
