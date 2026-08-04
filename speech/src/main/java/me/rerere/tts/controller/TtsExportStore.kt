/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.tts.controller

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.tts.model.AudioFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TtsExportStore"

/** TTS 导出条目元数据 */
@Serializable
data class TtsExportEntry(
    val id: String,
    val text: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val format: String
)

/**
 * TTS 音频导出缓存（全局单例，跨模块共享）
 *
 * - 朗读时自动把音频写入内部目录 tts_exports/
 * - PCM 自动加 WAV 头，其余格式原样保存
 * - 24 小时后自动清理，最多保留 [MAX_ENTRIES] 条
 * - AI 工具通过 [list] 查询、[exportToPublic] 复制到公共下载目录
 */
object TtsExportStore {

    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 50
    private const val INDEX_FILE = "index.json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cacheDir: File? = null

    private val lock = Any()

    /** 初始化缓存目录（幂等） */
    fun init(context: Context) {
        if (cacheDir == null) {
            synchronized(lock) {
                if (cacheDir == null) {
                    cacheDir = File(context.filesDir, "tts_exports").apply { mkdirs() }
                }
            }
        }
    }

    private fun dir(): File = cacheDir
        ?: throw IllegalStateException("TtsExportStore not initialized, call init(context) first")

    // ========== 写入 ==========

    /**
     * 合并保存一次完整朗读的多段 PCM。
     * 将各分段 PCM 按顺序拼接后统一加 WAV 头写入一个文件（一次朗读 = 一个完整音频）。
     * 仅适用于 PCM；非 PCM 分段请走 [save] 逐段保存。失败返回 null。
     */
    fun saveMergedPcm(text: String, pcmParts: List<ByteArray>, sampleRate: Int): TtsExportEntry? {
        val d = dir()
        return try {
            synchronized(lock) { cleanupLocked() }
            val merged = ByteArrayOutputStream()
            pcmParts.forEach { merged.write(it) }
            val wav = pcmToWav(merged.toByteArray(), sampleRate)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val fileName = "tts_$stamp.wav"
            val file = File(d, fileName)
            file.writeBytes(wav)
            val entry = TtsExportEntry(
                id = fileName,
                text = text.take(200),
                fileName = fileName,
                sizeBytes = wav.size.toLong(),
                createdAt = System.currentTimeMillis(),
                format = "wav"
            )
            synchronized(lock) { appendIndexLocked(entry) }
            Log.i(TAG, "saved merged ${file.absolutePath} (${wav.size} bytes, ${pcmParts.size} parts)")
            entry
        } catch (e: Exception) {
            Log.e(TAG, "saveMergedPcm failed", e)
            null
        }
    }

    /** 保存一段 TTS 音频。PCM 转 WAV，其余格式原样。失败返回 null。 */
    fun save(text: String, audioData: ByteArray, format: AudioFormat, sampleRate: Int?): TtsExportEntry? {
        val d = dir()
        return try {
            synchronized(lock) { cleanupLocked() }
            val isPcm = format == AudioFormat.PCM
            val bytes = if (isPcm) pcmToWav(audioData, sampleRate ?: 24000) else audioData
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val fileName = "tts_$stamp." + (if (isPcm) "wav" else format.name.lowercase(Locale.ROOT))
            val file = File(d, fileName)
            file.writeBytes(bytes)
            val entry = TtsExportEntry(
                id = fileName,
                text = text.take(200),
                fileName = fileName,
                sizeBytes = bytes.size.toLong(),
                createdAt = System.currentTimeMillis(),
                format = if (isPcm) "wav" else format.name
            )
            synchronized(lock) { appendIndexLocked(entry) }
            Log.i(TAG, "saved ${file.absolutePath} (${bytes.size} bytes)")
            entry
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            null
        }
    }

    // ========== 查询 ==========

    /** 列出所有缓存条目（时间倒序，最前为最新），顺带清理过期/超限 */
    fun list(): List<TtsExportEntry> = synchronized(lock) {
        cleanupLocked()
        loadIndexLocked().sortedByDescending { it.createdAt }
    }

    /** 按 id 查找条目 */
    fun find(id: String): TtsExportEntry? = list().firstOrNull { it.id == id }

    /** 获取最新一条 */
    fun latest(): TtsExportEntry? = list().firstOrNull()

    /** 按 id 打开缓存音频文件（不存在返回 null） */
    fun openFile(id: String): File? {
        val entry = find(id) ?: return null
        val f = File(dir(), entry.fileName)
        return if (f.exists()) f else null
    }

    // ========== 导出到公共目录 ==========

    /**
     * 把缓存音频复制到 app 的外部下载目录（无需存储权限），返回目标绝对路径。
     * @param context 用于定位外部目录
     * @param id 条目 id；为空则导出最新一条
     */
    fun exportToPublic(context: Context, id: String? = null): Pair<TtsExportEntry, String>? {
        val found = if (id != null) find(id) else latest()
        val entry = found ?: return null
        val src = File(dir(), entry.fileName)
        if (!src.exists()) return null
        val destDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "tts_exports")
        destDir.mkdirs()
        val dest = File(destDir, entry.fileName)
        src.copyTo(dest, overwrite = true)
        Log.i(TAG, "exported ${entry.fileName} -> ${dest.absolutePath}")
        return entry to dest.absolutePath
    }

    // ========== 清理 ==========

    private fun cleanupLocked() {
        val d = cacheDir ?: return
        val now = System.currentTimeMillis()
        val entries = loadIndexLocked()
        val (valid, expired) = entries.partition { now - it.createdAt < TTL_MS }
        expired.forEach { runCatching { File(d, it.fileName).delete() } }

        // 数量超限：删除最旧的
        val sorted = valid.sortedByDescending { it.createdAt }
        val keep = if (sorted.size > MAX_ENTRIES) sorted.take(MAX_ENTRIES) else sorted
        (sorted - keep).forEach { runCatching { File(d, it.fileName).delete() } }

        // 顺带清理 index 中已被外部删除的文件
        val final = keep.filter { it.id.isNotBlank() && File(d, it.fileName).exists() }
        saveIndexLocked(final)
    }

    // ========== index.json 读写 ==========

    private fun indexFile(): File = File(dir(), INDEX_FILE)

    private fun loadIndexLocked(): MutableList<TtsExportEntry> {
        val f = indexFile()
        if (!f.exists()) return mutableListOf()
        return try {
            json.decodeFromString<List<TtsExportEntry>>(f.readText()).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "index.json corrupt, reset: ${e.message}")
            mutableListOf()
        }
    }

    private fun appendIndexLocked(entry: TtsExportEntry) {
        val list = loadIndexLocked()
        list.add(entry)
        saveIndexLocked(list)
    }

    private fun saveIndexLocked(entries: List<TtsExportEntry>) {
        runCatching { indexFile().writeText(json.encodeToString(entries)) }
    }

    // ========== WAV ==========

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val out = ByteArrayOutputStream()
        with(out) {
            write("RIFF".toByteArray())
            write(intToBytes(36 + pcm.size))
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            write(intToBytes(16))
            write(shortToBytes(1))
            write(shortToBytes(channels.toShort()))
            write(intToBytes(sampleRate))
            write(intToBytes(byteRate))
            write(shortToBytes((channels * bitsPerSample / 8).toShort()))
            write(shortToBytes(bitsPerSample.toShort()))
            write("data".toByteArray())
            write(intToBytes(pcm.size))
            write(pcm)
        }
        return out.toByteArray()
    }

    private fun intToBytes(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short) = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
}
