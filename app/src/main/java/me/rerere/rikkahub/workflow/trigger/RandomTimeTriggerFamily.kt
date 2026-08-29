/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Phase 12+ — random time-window family.
 *
 * Fires a workflow at random moments inside `start..end` ("HH:mm", may wrap midnight),
 * keeping at least `min_interval_minutes` between fires and at most `max_triggers_per_day`
 * per day. Scheduling is a one-shot chain: each worker run fires (subject to the daily cap)
 * then re-enqueues itself at the next random point, so intervals stay genuinely random
 * instead of a fixed period.
 *
 * Daily cap is tracked in a small SharedPreferences (date + count per workflow id);
 * the date rolls over automatically on the first fire of a new day.
 */
internal class RandomTimeTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "random_time_between"

    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null

    private val counterPrefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.RandomTimeBetween

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        fireCallback = callback
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }
        // Cancel removed
        for (id in previous.keys - current.keys) {
            cancelWork(id)
        }
        // Schedule added or changed
        for ((id, wf) in current) {
            val prev = previous[id]
            if (prev == null
                || prev.trigger != wf.trigger
                || prev.updatedAtMs != wf.updatedAtMs
                || !prev.enabled
            ) {
                scheduleWork(wf)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) cancelWork(wf.id)
        lastSnapshot = emptyList()
        fireCallback = null
    }

    fun cancelWork(workflowId: String) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(workName(workflowId)) }
            .onFailure { Log.w(TAG, "random_time: cancel work failed for $workflowId", it) }
    }

    private fun scheduleWork(wf: WorkflowDefinition, shiftMs: Long = 0L) {
        val spec = wf.trigger as? TriggerSpec.RandomTimeBetween ?: return
        val nowMs = System.currentTimeMillis()
        // shiftMs 用于"跳过当前窗口"（每日上限已满时）：把虚拟 now 平移到当前窗口
        // 结束之后，再计算下一个随机点；delay 始终从真实当前时间起算。
        val nextFireMs = computeNextFireMs(spec, ZoneId.systemDefault(), nowMs + shiftMs)
        val delay = (nextFireMs - nowMs).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<WorkflowRandomTimeWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_WORKFLOW_ID to wf.id))
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(wf.id), ExistingWorkPolicy.REPLACE, req,
            )
        }.onFailure { Log.w(TAG, "random_time: enqueue failed for ${wf.id}", it) }
    }

    /**
     * Internal — fires the workflow (subject to the daily cap) then re-enqueues the next
     * random point. Called from [WorkflowRandomTimeWorker] via the registry.
     */
    suspend fun onWorkerFired(workflowId: String) {
        val cb = fireCallback ?: return
        // Post-boot / post-process-death race: same fallback as time_cron — read the repo
        // directly if the registry hasn't populated `lastSnapshot` yet.
        val wf = lastSnapshot.firstOrNull { it.id == workflowId }
            ?: run {
                val loaded = TimeCronWorkerHelper.repositoryLookup(workflowId)
                if (loaded == null) return
                if (!loaded.entity.enabled) return
                loaded.definition
            }
        val spec = wf.trigger as? TriggerSpec.RandomTimeBetween ?: return

        // 每日上限：达上限则跳过本次触发，并把下一个随机点推到当前窗口之后
        //（避免在窗口剩余时间内再空跑一次 worker 才发现已达上限）。
        if (!claimFire(workflowId, spec.maxTriggersPerDay)) {
            Log.d(TAG, "random_time: $workflowId daily cap (${spec.maxTriggersPerDay}) reached, rescheduling after current window")
            scheduleWorkAfterCurrentWindow(wf)
            return
        }

        scope.launch(Dispatchers.IO) {
            runCatching { cb.onFire(wf.id, wf.trigger) }
                .onFailure { Log.w(TAG, "random_time: fire callback failed for $workflowId", it) }
        }
        // 重新调度下一个随机点
        scheduleWork(wf)
    }

    /** 达每日上限时：跳过当前窗口剩余时间，直接调度到下一窗口的随机点。 */
    private fun scheduleWorkAfterCurrentWindow(wf: WorkflowDefinition) {
        val spec = wf.trigger as? TriggerSpec.RandomTimeBetween ?: return
        val nowMs = System.currentTimeMillis()
        val windowEndMs = currentWindowEndMs(spec, ZoneId.systemDefault(), nowMs)
        val shiftMs = (windowEndMs - nowMs).coerceAtLeast(0L) + 1000L
        scheduleWork(wf, shiftMs)
    }

    /** true = 允许本次触发并已计数；false = 已达每日上限。按日期自动重置。 */
    private fun claimFire(workflowId: String, maxPerDay: Int): Boolean {
        val today = LocalDate.now().toString()
        val dateKey = "${KEY_PREFIX}_${workflowId}_date"
        val countKey = "${KEY_PREFIX}_${workflowId}_count"
        synchronized(counterLock) {
            val storedDate = counterPrefs.getString(dateKey, "")
            val count = if (storedDate == today) {
                counterPrefs.getInt(countKey, 0)
            } else {
                // 2.4.2：commit() 同步落盘——apply() 在崩溃窗口内丢计数，导致每日上限失效（晨信双信案 §十）
                counterPrefs.edit().putString(dateKey, today).putInt(countKey, 0).commit()
                0
            }
            if (count >= maxPerDay) return false
            counterPrefs.edit().putInt(countKey, count + 1).commit()
            return true
        }
    }

    companion object {
        private const val TAG = "WorkflowTrigger"
        private const val PREFS_NAME = "workflow_random_time_prefs"
        private const val KEY_PREFIX = "random_time"
        const val KEY_WORKFLOW_ID = "workflow_id"
        private val counterLock = Any()
        fun workName(workflowId: String) = "wf_randomtime_$workflowId"

        /**
         * Compute the next random fire time inside [spec.start]..[spec.end] (may wrap midnight),
         * at least [spec.minIntervalMinutes] after [nowMs]. If today's window can't fit another
         * fire, falls through to tomorrow's window (random point inside it).
         */
        fun computeNextFireMs(spec: TriggerSpec.RandomTimeBetween, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val startT = parseHHmm(spec.start)
            val endT = parseHHmm(spec.end)
            val wraps = !startT.isBefore(endT) // start >= end → 跨午夜

            // 窗口 = day.start .. day.end（跨午夜时结束顺延到次日）
            fun windowStartOf(day: LocalDate) = day.atTime(startT).atZone(zone)
            fun windowEndOf(day: LocalDate): ZonedDateTime {
                val e = day.atTime(endT).atZone(zone)
                return if (wraps) e.plusDays(1) else e
            }

            val yesterday = now.toLocalDate().minusDays(1)
            val todayS = windowStartOf(now.toLocalDate())
            val todayE = windowEndOf(now.toLocalDate())

            // 选择 now 所在的窗口：跨午夜时凌晨属于"昨晚"的窗口（如 22:00..08:00 中
            // 的 02:00 属于昨晚 22:00 开始的那段），否则可能把窗口后半段白白跳过。
            val (winStart, winEnd) = when {
                now >= windowStartOf(yesterday) && now < windowEndOf(yesterday) ->
                    windowStartOf(yesterday) to windowEndOf(yesterday)
                now >= todayS && now < todayE -> todayS to todayE
                now < todayS -> todayS to todayE
                else -> windowStartOf(now.toLocalDate().plusDays(1)) to
                    windowEndOf(now.toLocalDate().plusDays(1))
            }

            val minNext = now.plusMinutes(spec.minIntervalMinutes.toLong())
            val candidateStart = maxOf(winStart, minNext)
            if (candidateStart.isBefore(winEnd)) {
                // 窗口内 [candidateStart, winEnd) 随机
                val spanMs = ChronoUnit.MILLIS.between(candidateStart, winEnd)
                val offset = Random.nextLong(0, spanMs.coerceAtLeast(1L))
                return candidateStart.plus(offset, ChronoUnit.MILLIS).toInstant().toEpochMilli()
            }
            // 当天窗口放不下 → 明天窗口内随机
            val nextDay = now.toLocalDate().plusDays(1)
            val s = windowStartOf(nextDay)
            val e = windowEndOf(nextDay)
            val spanMs = ChronoUnit.MILLIS.between(s, e)
            val offset = Random.nextLong(0, spanMs.coerceAtLeast(1L))
            return s.plus(offset, ChronoUnit.MILLIS).toInstant().toEpochMilli()
        }

        private fun parseHHmm(s: String): LocalTime {
            val (h, m) = s.split(":").let { it[0].toInt() to it[1].toInt() }
            return LocalTime.of(h, m)
        }

        /**
         * 返回 now 所在窗口的结束时刻（ms）。若 now 不在任何窗口内（例如非跨午夜窗口的
         * 深夜），返回下一窗口的开始时刻——把"虚拟 now"平移到下个窗口起点，供
         * [scheduleWorkAfterCurrentWindow] 跳过当前窗口使用。
         */
        fun currentWindowEndMs(spec: TriggerSpec.RandomTimeBetween, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            val startT = parseHHmm(spec.start)
            val endT = parseHHmm(spec.end)
            val wraps = !startT.isBefore(endT)
            fun windowStartOf(day: LocalDate) = day.atTime(startT).atZone(zone)
            fun windowEndOf(day: LocalDate): ZonedDateTime {
                val e = day.atTime(endT).atZone(zone)
                return if (wraps) e.plusDays(1) else e
            }
            val yesterday = now.toLocalDate().minusDays(1)
            val todayS = windowStartOf(now.toLocalDate())
            val todayE = windowEndOf(now.toLocalDate())
            return when {
                now >= windowStartOf(yesterday) && now < windowEndOf(yesterday) ->
                    windowEndOf(yesterday).toInstant().toEpochMilli()
                now >= todayS && now < todayE ->
                    todayE.toInstant().toEpochMilli()
                else ->
                    todayS.toInstant().toEpochMilli()
            }
        }
    }
}
