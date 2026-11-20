package com.example.discordcleaner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** アカウント・ルーチン・履歴をアプリ専用領域のJSONに保存する。件数が少ないのでDBは使わない。 */
class Store private constructor(context: Context) {
    private val dir = context.filesDir
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val tokens = TokenVault(context)

    private val _accounts = MutableStateFlow(load("accounts.json", Account::fromJson))
    private val _routines = MutableStateFlow(load("routines.json", Routine::fromJson))
    private val _runs = MutableStateFlow(load("runs.json", RunRecord::fromJson).let(::prune))
    private val _currentAccountId = MutableStateFlow(prefs.getString("current", null))

    val accounts: StateFlow<List<Account>> = _accounts
    val routines: StateFlow<List<Routine>> = _routines
    val runs: StateFlow<List<RunRecord>> = _runs
    val currentAccountId: StateFlow<String?> = _currentAccountId

    fun account(id: String) = _accounts.value.firstOrNull { it.id == id }
    fun routine(id: String) = _routines.value.firstOrNull { it.id == id }

    @Synchronized fun upsertAccount(a: Account, token: String?) {
        if (token != null) tokens.put(a.id, token)
        val list = _accounts.value.filterNot { it.id == a.id } + a
        _accounts.value = list.sortedBy { it.name.lowercase() }
        save("accounts.json", _accounts.value.map { it.toJson() })
        if (_currentAccountId.value == null || account(_currentAccountId.value!!) == null) setCurrent(a.id)
    }

    @Synchronized fun markNeedsLogin(id: String, needs: Boolean) {
        val a = account(id) ?: return
        if (a.needsLogin == needs) return
        upsertAccount(a.copy(needsLogin = needs), null)
    }

    @Synchronized fun removeAccount(id: String) {
        tokens.remove(id)
        _accounts.value = _accounts.value.filterNot { it.id == id }
        save("accounts.json", _accounts.value.map { it.toJson() })
        _routines.value = _routines.value.filterNot { it.accountId == id }
        save("routines.json", _routines.value.map { it.toJson() })
        if (_currentAccountId.value == id) setCurrent(_accounts.value.firstOrNull()?.id)
    }

    fun setCurrent(id: String?) {
        _currentAccountId.value = id
        prefs.edit().putString("current", id).apply()
    }

    @Synchronized fun upsertRoutine(r: Routine) {
        val list = _routines.value.toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list.add(r)
        _routines.value = list
        save("routines.json", list.map { it.toJson() })
    }

    @Synchronized fun removeRoutine(id: String) {
        _routines.value = _routines.value.filterNot { it.id == id }
        save("routines.json", _routines.value.map { it.toJson() })
    }

    @Synchronized fun addRun(r: RunRecord) {
        _runs.value = prune(listOf(r) + _runs.value)
        save("runs.json", _runs.value.map { it.toJson() })
    }

    @Synchronized fun clearRuns() {
        _runs.value = emptyList()
        save("runs.json", emptyList())
    }

    /** 90日より古い履歴と500件を超える分は捨てる。 */
    private fun prune(list: List<RunRecord>): List<RunRecord> {
        val limit = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        return list.filter { it.startedAt >= limit }.sortedByDescending { it.startedAt }.take(500)
    }

    private fun <T> load(name: String, parse: (JSONObject) -> T): List<T> = try {
        val f = File(dir, name)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
        }
    } catch (e: Exception) { emptyList() }

    private fun save(name: String, items: List<JSONObject>) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(JSONArray(items).toString())
        tmp.renameTo(File(dir, name))
    }

    companion object {
        @Volatile private var inst: Store? = null
        fun get(context: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(context.applicationContext).also { inst = it }
        }
    }
}
