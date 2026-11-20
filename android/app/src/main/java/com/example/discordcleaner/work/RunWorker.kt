package com.example.discordcleaner.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.RunRecord
import com.example.discordcleaner.data.RunStatus
import com.example.discordcleaner.data.Store
import com.example.discordcleaner.discord.AuthException
import com.example.discordcleaner.discord.DiscordApi
import com.example.discordcleaner.discord.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 1回分の削除を実行するワーカー(手動・スケジュール共通)。
 * 実行中はフォアグラウンドサービス(dataSync)にして通知に進捗と停止を出す。
 * フォアグラウンド化できない場合(バックグラウンドからの起動制限)は通常ワーカーとして約8分半で区切る。
 */
class RunWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val notifId = id.hashCode()
    private var title = "削除を準備中"

    override suspend fun getForegroundInfo(): ForegroundInfo = fgInfo("準備中", 0, 0)

    override suspend fun doWork(): Result {
        val store = Store.get(applicationContext)
        val scheduled = inputData.getBoolean(K_SCHEDULED, false)
        val routine = inputData.getString(K_ROUTINE_JSON)?.let { Routine.fromJson(JSONObject(it)) }
            ?: inputData.getString(K_ROUTINE_ID)?.let { store.routine(it) }
            ?: return Result.success()
        val saved = inputData.getString(K_ROUTINE_ID) != null
        val name = routine.name.ifBlank { "手動削除" }
        title = name

        if (scheduled) {
            if (!routine.isScheduled) return Result.success()            // 途中で無効化された
            val planned = inputData.getLong(K_PLANNED, 0)
            if (planned > 0 && System.currentTimeMillis() - planned > 6 * 3600_000L) {
                record(store, routine, saved, scheduled, System.currentTimeMillis(), 0, 0, 0, 0, RunStatus.SKIPPED, "実行条件(Wi-Fi・充電など)が6時間揃わなかったため見送りました")
                return Result.success()
            }
        }

        val foreground = try { setForeground(fgInfo("開始待ち", 0, 0)); true } catch (e: Exception) { false }
        // フォアグラウンドでなければ通常ワーカーの10分制限に収まるよう区切る
        val deadline = if (foreground) Long.MAX_VALUE else System.currentTimeMillis() + 8 * 60_000L + 30_000L

        setProgress(workDataOf(P_PHASE to "WAIT"))
        return runLock.withLock { execute(store, routine, saved, scheduled, name, foreground, deadline) }
    }

    private suspend fun execute(
        store: Store, routine: Routine, saved: Boolean, scheduled: Boolean, name: String,
        foreground: Boolean, deadline: Long,
    ): Result {
        val started = System.currentTimeMillis()
        val account = store.account(routine.accountId)
        val token = account?.let { store.tokens.get(it.id) }
        if (account == null || token == null) {
            record(store, routine, saved, scheduled, started, 0, 0, 0, 0, RunStatus.NEEDS_LOGIN, "アカウントのログイン情報がありません")
            notifyNeedsLogin(account?.name ?: "不明なアカウント", name)
            return Result.success()
        }

        var found = 0; var del = 0; var skip = 0; var fail = 0
        var lastUi = 0L
        suspend fun report(p: Engine.Progress, force: Boolean = false) {
            found = p.found; del = p.deleted; skip = p.skipped; fail = p.failed
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 700) return
            lastUi = now
            setProgress(workDataOf(
                P_PHASE to p.phase.name, P_FOUND to p.found, P_DELETED to p.deleted, P_SKIPPED to p.skipped,
                P_FAILED to p.failed, P_TOTAL to p.total, P_CURRENT to p.current, P_NAME to name, P_ACCOUNT to account.name,
            ))
            if (foreground) {
                val text = if (p.phase == Engine.Phase.SCAN) "対象を確認中 · ${p.found}件" else "${p.deleted + p.skipped + p.failed} / ${p.total} 件"
                runCatching { setForeground(fgInfo(text, p.deleted + p.skipped + p.failed, p.total)) }
            }
        }

        return try {
            val api = DiscordApi(token)
            val me = api.me().getString("id")
            if (me != account.id) throw AuthException()
            store.markNeedsLogin(account.id, false)
            val engine = Engine(api, me)
            val limit = routine.maxPerRun
            val list = engine.scan(routine, limit) { p -> report(p) }
            found = list.size
            val cut = timeBoxed(list, routine, deadline)
            report(Engine.Progress(Engine.Phase.DELETE, found, 0, 0, 0, cut.size, ""), true)
            val (d, s, f) = engine.delete(cut, routine, found) { p -> report(p) }
            del = d; skip = s; fail = f
            val more = cut.size < list.size || (limit > 0 && list.size >= limit)
            val msg = if (more) "残りは次回に削除します" else ""
            record(store, routine, saved, scheduled, started, found, del, skip, fail, RunStatus.DONE, msg)
            Notifications.result(applicationContext, notifId + 1, "$name: ${del}件削除", "${account.name} · スキップ${skip} · エラー${fail}" + if (msg.isNotEmpty()) "\n$msg" else "")
            Result.success(workDataOf(P_PHASE to "DONE", P_FOUND to found, P_DELETED to del, P_SKIPPED to skip, P_FAILED to fail))
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                record(store, routine, saved, scheduled, started, found, del, skip, fail, RunStatus.STOPPED, "停止しました")
            }
            throw e
        } catch (e: AuthException) {
            store.markNeedsLogin(account.id, true)
            record(store, routine, saved, scheduled, started, found, del, skip, fail, RunStatus.NEEDS_LOGIN, e.message ?: "")
            notifyNeedsLogin(account.name, name)
            Result.failure(workDataOf(P_PHASE to "ERROR", P_ERROR to (e.message ?: "")))
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            record(store, routine, saved, scheduled, started, found, del, skip, fail, RunStatus.ERROR, msg)
            if (scheduled) Notifications.result(applicationContext, notifId + 1, "$name を実行できませんでした", msg)
            Result.failure(workDataOf(P_PHASE to "ERROR", P_ERROR to msg))
        }
    }

    /** 通常ワーカー(10分制限)のときは時間内に収まる件数に絞る。 */
    private fun timeBoxed(list: List<Engine.Found>, r: Routine, deadline: Long): List<Engine.Found> {
        if (deadline == Long.MAX_VALUE) return list
        val remain = deadline - System.currentTimeMillis()
        val per = (r.pace.minMs + r.pace.maxMs) / 2 + 400
        return list.take((remain / per).toInt().coerceAtLeast(0))
    }

    private fun record(
        store: Store, r: Routine, saved: Boolean, scheduled: Boolean, started: Long,
        found: Int, del: Int, skip: Int, fail: Int, status: RunStatus, msg: String,
    ) = store.addRun(RunRecord(
        routineId = if (saved) r.id else null, routineName = r.name.ifBlank { "手動削除" }, accountId = r.accountId,
        startedAt = started, endedAt = System.currentTimeMillis(), found = found, deleted = del, skipped = skip,
        failed = fail, status = status, message = msg, scheduled = scheduled,
    ))

    private fun notifyNeedsLogin(accountName: String, routineName: String) =
        Notifications.result(applicationContext, notifId + 1, "ログインが必要です($accountName)",
            "「$routineName」を実行できませんでした。アプリを開いて再ログインしてください。")

    private fun fgInfo(text: String, done: Int, total: Int): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val n = Notifications.progress(applicationContext, title, text, done, total, cancel)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notifId, n)
    }

    companion object {
        const val TAG = "run"
        const val K_ROUTINE_ID = "routineId"
        const val K_ROUTINE_JSON = "routineJson"
        const val K_SCHEDULED = "scheduled"
        const val K_PLANNED = "plannedAt"
        const val P_PHASE = "phase"; const val P_FOUND = "found"; const val P_DELETED = "deleted"
        const val P_SKIPPED = "skipped"; const val P_FAILED = "failed"; const val P_TOTAL = "total"
        const val P_CURRENT = "current"; const val P_NAME = "name"; const val P_ACCOUNT = "account"; const val P_ERROR = "error"
        private val runLock = Mutex()
    }
}
