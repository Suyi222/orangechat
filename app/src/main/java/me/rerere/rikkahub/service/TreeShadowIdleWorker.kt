/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * 件② 问题B到点即结（2.4.5）——树影下闲置总结的 15 分钟周期兜底。
 *
 * 退后台即查（ChatService 的 ProcessLifecycleOwner ON_STOP）已覆盖「当天正常使用后退出」的
 * 场景；本 Worker 兜住剩下的窗口：进程被杀后重启前、长期挂后台被 LLM 清场后——只要 WorkManager
 * 还活着就每 15 分钟醒一次做同一个闲置检查。命中与否全部复用 [ChatService.runIdleSummaryCheck]，
 * 闸门（开关/记录者/防重入/幂等）都在那一层，Worker 自身无逻辑。
 */
class TreeShadowIdleWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val chatService: ChatService by inject()

    override suspend fun doWork(): Result {
        runCatching { chatService.runIdleSummaryCheck() }
            .onFailure { android.util.Log.w(TAG, "idle summary check failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "TreeShadowIdleWorker"
        private const val WORK_NAME = "tree_shadow_idle_check"

        /** WorkManager 周期下限正好 15 分钟，KEEP 策略保证重复注册不叠加 */
        fun register(context: Context) {
            val request = PeriodicWorkRequestBuilder<TreeShadowIdleWorker>(15, TimeUnit.MINUTES)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { android.util.Log.w(TAG, "register failed", it) }
        }
    }
}
