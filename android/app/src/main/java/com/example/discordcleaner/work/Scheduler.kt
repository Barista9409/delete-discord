package com.example.discordcleaner.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.discordcleaner.data.Repeat
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.Schedule
import com.example.discordcleaner.data.Store
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * スケジュール実行。
 * 15分ごとの「見回り」ワーカーが、予定時刻を過ぎたルーチンを見つけて RunWorker を起動する。
 * (端末の再起動やアプリ更新をまたいでもWorkManagerが見回りを継続する)
 */
object Scheduler {
    private const val TICKER = "schedule-ticker"

    fun ensureTicker(context: Context) {
        val req = PeriodicWorkRequestBuilder<TickWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(TICKER, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** ルーチン保存時に呼ぶ。次回予定を決め直す。 */
    fun prepare(r: Routine, now: Long = System.currentTimeMillis()): Routine =
        if (!r.isScheduled) r.copy(nextRunAt = null) else r.copy(nextRunAt = nextRun(r.schedule, now))

    /** 次の実行時刻(揺らぎ込み)。 */
    fun nextRun(s: Schedule, from: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, s.hour); set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 揺らぎで前倒しになっても from より後になるよう、基準は from + 揺らぎ幅
        val earliest = from + s.jitterMin * 60_000L
        for (i in 0 until 400) {
            if (c.timeInMillis > earliest && matches(s, c)) break
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        val jitter = if (s.jitterMin > 0) Random.nextLong(-s.jitterMin * 60_000L, s.jitterMin * 60_000L) else 0L
        return c.timeInMillis + jitter
    }

    private fun matches(s: Schedule, c: Calendar): Boolean = when (s.repeat) {
        Repeat.MANUAL -> false
        Repeat.DAILY -> true
        Repeat.WEEKLY -> {
            // Calendar: 日=1 … 土=7 → 月=1 … 日=7 に変換
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            s.weekdays.ifEmpty { setOf(7) }.contains(dow)
        }
        Repeat.MONTHLY -> {
            val last = c.getActualMaximum(Calendar.DAY_OF_MONTH)
            c.get(Calendar.DAY_OF_MONTH) == minOf(s.dayOfMonth, last)
        }
    }

    /** 今すぐ実行(手動)。ルーチン未保存でも routine を丸ごと渡せる。 */
    fun runNow(context: Context, r: Routine, saved: Boolean): UUID {
        // 保存していないルーチンはファイル経由で渡す(WorkManagerの入力データは10KBまで)
        val input = if (saved) workDataOf(RunWorker.K_ROUTINE_ID to r.id) else {
            val f = RunWorker.adhocFile(context, UUID.randomUUID().toString())
            f.parentFile?.mkdirs(); f.writeText(r.toJson().toString())
            workDataOf(RunWorker.K_ADHOC_FILE to f.name)
        }
        val req = OneTimeWorkRequestBuilder<RunWorker>()
            .setInputData(input)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(RunWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueue(req)
        return req.id
    }

    fun enqueueScheduled(context: Context, r: Routine, plannedAt: Long) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (r.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(r.chargingOnly)
            .setRequiresBatteryNotLow(r.batteryNotLow)
            .build()
        val req = OneTimeWorkRequestBuilder<RunWorker>()
            .setInputData(workDataOf(RunWorker.K_ROUTINE_ID to r.id, RunWorker.K_SCHEDULED to true, RunWorker.K_PLANNED to plannedAt))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .addTag(RunWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sched-${r.id}", ExistingWorkPolicy.KEEP, req)
    }

    fun cancelScheduled(context: Context, routineId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("sched-$routineId")
    }

    class TickWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val store = Store.get(applicationContext)
            val now = System.currentTimeMillis()
            for (r in store.routines.value) {
                if (!r.isScheduled) continue
                val due = r.nextRunAt
                if (due == null) { store.upsertRoutine(prepare(r, now)); continue }
                if (due <= now) {
                    com.example.discordcleaner.data.DebugLog.d("sched", "予定時刻到来: ${r.name}")
                    enqueueScheduled(applicationContext, r, due)
                    store.upsertRoutine(r.copy(nextRunAt = nextRun(r.schedule, now)))
                }
            }
            return Result.success()
        }
    }
}
