/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.developer

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.ai.AILoggingManager

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()

    // 2.4.6.4 第一刀③：请求体按需落盘开关（网关取证用，默认关；开=每次生成异步写文件，密钥脱敏）
    val dumpEnabled = aiLoggingManager.isDumpEnabled()
    val dumpPath: String = aiLoggingManager.dumpDirPath()

    fun setDumpEnabled(enabled: Boolean) {
        aiLoggingManager.setDumpEnabled(enabled)
    }
}
