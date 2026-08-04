/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Phase 12+ — WorkManager worker that fires a workflow at a random time-window point.
 *
 * Uses Koin component injection (same pattern as [WorkflowTimeCronWorker]). The actual
 * fire dispatch goes through [TriggerRegistry] so condition + cooldown evaluation happens
 * consistently with every other trigger family; the family re-enqueues the next random
 * point after the fire.
 */
class WorkflowRandomTimeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val registry: TriggerRegistry by inject()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(RandomTimeTriggerFamily.KEY_WORKFLOW_ID) ?: return Result.failure()
        registry.fireFromRandomTimeWorker(workflowId)
        return Result.success()
    }
}
