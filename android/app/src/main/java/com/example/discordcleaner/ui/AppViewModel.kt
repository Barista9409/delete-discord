package com.example.discordcleaner.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.discordcleaner.data.Account
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.Store
import com.example.discordcleaner.data.Target
import com.example.discordcleaner.discord.AuthException
import com.example.discordcleaner.discord.DiscordApi
import com.example.discordcleaner.discord.Engine
import com.example.discordcleaner.work.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Routines : Screen
    data object History : Screen
    data object Settings : Screen
    data class Wizard(val draft: Routine, val step: Int = 0) : Screen
    data class Running(val workId: UUID) : Screen
    data class EditRoutine(val routine: Routine, val isNew: Boolean) : Screen
    data class Login(val relogin: Boolean) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store.get(app)

    // ---- 画面遷移(スタック) ----
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun push(s: Screen) { stack.add(s) }
    fun replace(s: Screen) { stack.removeAt(stack.lastIndex); stack.add(s) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }

    fun currentAccount(): Account? {
        val id = store.currentAccountId.value
        return store.accounts.value.firstOrNull { it.id == id } ?: store.accounts.value.firstOrNull()
    }

    // ---- サーバー/DM一覧(アカウントごとにキャッシュ) ----
    data class TargetList(val loading: Boolean = true, val guilds: List<Target> = emptyList(), val dms: List<Target> = emptyList(), val error: String? = null)
    val targetLists = mutableStateMapOf<String, TargetList>()

    fun loadTargets(accountId: String, force: Boolean = false) {
        val cur = targetLists[accountId]
        if (!force && cur != null && (cur.loading || cur.error == null)) return
        targetLists[accountId] = TargetList(loading = true)
        viewModelScope.launch {
            targetLists[accountId] = try {
                val api = api(accountId) ?: throw AuthException()
                TargetList(false, api.guilds(), api.dms())
            } catch (e: AuthException) {
                store.markNeedsLogin(accountId, true)
                TargetList(false, error = e.message)
            } catch (e: Exception) {
                TargetList(false, error = e.message ?: "読み込みに失敗しました")
            }
        }
    }

    // ---- 確認画面の件数スキャン ----
    data class CountState(val running: Boolean = false, val step: Int = 0, val of: Int = 0, val result: Engine.CountResult? = null, val error: String? = null)
    var count by mutableStateOf(CountState())
        private set
    private var countJob: Job? = null

    fun startCount(r: Routine) {
        countJob?.cancel()
        count = CountState(running = true)
        countJob = viewModelScope.launch {
            count = try {
                val api = api(r.accountId) ?: throw AuthException()
                val me = api.me().getString("id")
                val res = Engine(api, me).count(r) { i, n -> count = count.copy(step = i, of = n) }
                CountState(result = res)
            } catch (e: AuthException) {
                store.markNeedsLogin(r.accountId, true)
                CountState(error = e.message)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CountState(error = e.message ?: "スキャンに失敗しました")
            }
        }
    }

    fun cancelCount() { countJob?.cancel(); count = CountState() }

    // ---- 実行・保存 ----
    fun runNow(r: Routine, saved: Boolean) {
        val id = Scheduler.runNow(getApplication(), r, saved)
        push(Screen.Running(id))
    }

    fun saveRoutine(r: Routine) {
        val named = if (r.name.isBlank()) r.copy(name = Labels.autoName(r)) else r
        val prepared = Scheduler.prepare(named)
        store.upsertRoutine(prepared)
        if (!prepared.isScheduled) Scheduler.cancelScheduled(getApplication(), prepared.id)
        Scheduler.ensureTicker(getApplication())
    }

    fun setEnabled(r: Routine, on: Boolean) = saveRoutine(r.copy(enabled = on))

    fun deleteRoutine(r: Routine) {
        Scheduler.cancelScheduled(getApplication(), r.id)
        store.removeRoutine(r.id)
    }

    fun removeAccount(a: Account) {
        store.routines.value.filter { it.accountId == a.id }.forEach { Scheduler.cancelScheduled(getApplication(), it.id) }
        store.removeAccount(a.id)
        targetLists.remove(a.id)
    }

    private fun api(accountId: String): DiscordApi? = store.tokens.get(accountId)?.let { DiscordApi(it) }

    /** ログイン画面で取得したトークンを検証して登録する。 */
    suspend fun registerToken(token: String, allowExisting: Boolean, known: Set<String>): Result<Account> = runCatching {
        val me = DiscordApi(token).me()
        val id = me.getString("id")
        val display = me.optString("global_name").takeIf { it.isNotEmpty() && it != "null" } ?: me.optString("username")
        if (!allowExisting && id in known) throw AlreadyRegistered(display)
        val existing = store.account(id)
        val acct = Account(id, display, existing?.colorIdx ?: (store.accounts.value.size % C.avatars.size), needsLogin = false)
        store.upsertAccount(acct, token)
        store.setCurrent(id)
        targetLists.remove(id)
        acct
    }
}
