/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.4.6.2 RD1：主动消息 / 工作流唤醒全链路埋点的统一出口——logcat + 文件双写。
 *
 * 背景（树邮局晨信案）：workflow_runs 的 SUCCESS 只覆盖 8 段链路第 1 段
 * （trigger_proactive_message 是 fire-and-forget，startForegroundService 调用成功即报「触发成功」），
 * 后续 ②FGS启动 ③取时 ④claim ⑤请求 ⑥首包 ⑦工具调用 ⑧落库/完成 全是黑盒。
 * 文件化后每段一行：晨信工作流跑一夜，早起在「设置 → 系统工具 → 导出后台任务日志」
 * 导出 proactive_trace.log 即可定位死在哪段，不用插线抓 logcat。
 *
 * 文件与树影下总结日志同规格：filesDir、256KB 轮转（超限保留后半段）。
 * logcat 过滤照旧：adb logcat -s ProactiveTrace。
 */
object ProactiveTrace {
    const val TAG = "ProactiveTrace"
    private const val FILE_NAME = "proactive_trace.log"
    private const val MAX_BYTES = 256 * 1024L

    /** 埋点日志文件（设置页「导出后台任务日志」用）。 */
    fun logFile(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 双写一行埋点：logcat + 文件。context 为 null 时只写 logcat。写文件失败静默（埋点不得反噬主链路）。 */
    @Synchronized
    fun log(context: Context?, message: String) {
        Log.i(TAG, message)
        if (context == null) return
        runCatching {
            val file = logFile(context)
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            file.appendText("[$ts] $message\n")
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt()))
            }
        }.onFailure { Log.w(TAG, "write trace file failed", it) }
    }
}
