package com.example.discordcleaner.discord

import com.example.discordcleaner.data.Kind
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.Scope
import com.example.discordcleaner.data.Target
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlin.random.Random

/**
 * 削除エンジン。
 * 方式は従来どおり「先にスキャン → 古い順に削除」(削除しながら検索するとインデックスがずれて止まるため)。
 * 削除対象は通常メッセージ(type 0 / 19)のみ。
 */
class Engine(private val api: DiscordApi, private val meId: String) {

    data class Found(val channelId: String, val id: String, val timestamp: Long, val where: String)
    data class Preview(val where: String, val timestamp: Long, val text: String)
    data class CountResult(val total: Int, val targets: Int, val preview: List<Preview>, val oldest: Long?)

    data class Progress(
        val phase: Phase, val found: Int, val deleted: Int, val skipped: Int, val failed: Int,
        val total: Int, val current: String,
    )
    enum class Phase { SCAN, DELETE }

    /** 「すべて」などを実際のサーバー/DM一覧に展開する(実行のたびに取り直す)。 */
    suspend fun resolveTargets(r: Routine): List<Target> = when (r.scope) {
        Scope.SELECTED -> r.targets
        Scope.ALL_GUILDS -> api.guilds()
        Scope.ALL_DMS -> api.dms()
        Scope.ALL -> api.guilds() + api.dms()
    }

    fun params(r: Routine, now: Long = System.currentTimeMillis()): MutableMap<String, String> {
        val p = linkedMapOf("author_id" to meId, "sort_by" to "timestamp", "sort_order" to "asc", "include_nsfw" to "true")
        if (r.keyword.isNotBlank()) p["content"] = r.keyword.trim()
        when (r.kind) { Kind.FILE -> p["has"] = "file"; Kind.LINK -> p["has"] = "link"; Kind.ALL -> {} }
        if (r.keepPinned) p["pinned"] = "false"
        val maxMs = r.olderThanDays?.let { now - it * DAY } ?: r.toDate?.let { it + DAY - 1 }
        val minMs = if (r.olderThanDays == null) r.fromDate else null
        maxMs?.let { p["max_id"] = snowflake(it) }
        minMs?.let { p["min_id"] = snowflake(it) }
        return p
    }

    /** 確認画面用: 件数の目安と先頭数件のプレビュー(本文は表示のみで保存しない)。 */
    suspend fun count(r: Routine, onStep: suspend (Int, Int) -> Unit): CountResult {
        val targets = resolveTargets(r)
        var total = 0; var oldest: Long? = null
        val preview = mutableListOf<Preview>()
        targets.forEachIndexed { i, t ->
            currentCoroutineContext().ensureActive()
            onStep(i + 1, targets.size)
            val page = try { api.search(t, params(r)) } catch (e: ApiException) { if (e.status == 403) null else throw e }
            if (page != null) {
                total += page.optInt("total_results")
                val msgs = hits(page)
                msgs.firstOrNull()?.let { m -> val ts = ts(m); if (oldest == null || ts < oldest!!) oldest = ts }
                if (preview.size < 3) msgs.filter { deletable(it, r) }.take(3 - preview.size).forEach {
                    preview += Preview(t.name, ts(it), it.optString("content").ifEmpty { "(添付ファイルなど)" }.take(80))
                }
            }
            if (i < targets.size - 1) delay(700)
        }
        return CountResult(total, targets.size, preview, oldest)
    }

    /** 削除対象を集める。limit 件集まったら打ち切る(0 = 無制限)。 */
    suspend fun scan(r: Routine, limit: Int, onProgress: suspend (Progress) -> Unit): List<Found> {
        val targets = resolveTargets(r)
        val out = LinkedHashMap<String, Found>()
        for ((ti, t) in targets.withIndex()) {
            var offset = 0
            var minId: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                val p = params(r)
                if (minId != null) p["min_id"] = minId
                p["offset"] = offset.toString()
                val page = try { api.search(t, p) } catch (e: ApiException) { if (e.status == 403) break else throw e }
                val raw = hits(page)
                if (raw.isEmpty()) break
                var newOnes = 0
                for (m in raw) {
                    if (!deletable(m, r)) continue
                    val id = m.getString("id")
                    if (out.containsKey(id)) continue
                    out[id] = Found(m.getString("channel_id"), id, ts(m), t.name); newOnes++
                    if (limit > 0 && out.size >= limit) break
                }
                onProgress(Progress(Phase.SCAN, out.size, 0, 0, 0, 0, "${t.name} を確認中 (${ti + 1}/${targets.size})"))
                if (limit > 0 && out.size >= limit) break
                val total = page.optInt("total_results")
                offset += raw.size
                if (offset >= total) break
                // 検索のoffsetには上限があるため、深くなったら最後のIDを起点に取り直す
                if (offset >= 5000) { minId = raw.last().getString("id"); offset = 0 }
                if (newOnes == 0 && offset == 0) break
                delay(Random.nextLong(600, 1200))
            }
            if (limit > 0 && out.size >= limit) break
        }
        return out.values.sortedBy { it.id.toBigInteger() }
    }

    /** 古い順に削除する。 */
    suspend fun delete(list: List<Found>, r: Routine, found: Int, onProgress: suspend (Progress) -> Unit): Triple<Int, Int, Int> {
        var del = 0; var skip = 0; var fail = 0
        list.forEachIndexed { i, f ->
            currentCoroutineContext().ensureActive()
            onProgress(Progress(Phase.DELETE, found, del, skip, fail, list.size, "${f.where} · ${dateStr(f.timestamp)}"))
            when (api.delete(f.channelId, f.id)) {
                DiscordApi.DeleteResult.DELETED -> del++
                DiscordApi.DeleteResult.SKIPPED -> skip++
                DiscordApi.DeleteResult.FAILED -> fail++
            }
            onProgress(Progress(Phase.DELETE, found, del, skip, fail, list.size, "${f.where} · ${dateStr(f.timestamp)}"))
            if (i < list.size - 1) delay(Random.nextLong(r.pace.minMs, r.pace.maxMs))
        }
        return Triple(del, skip, fail)
    }

    private fun hits(page: JSONObject): List<JSONObject> {
        val groups = page.optJSONArray("messages") ?: return emptyList()
        return (0 until groups.length()).mapNotNull { gi ->
            val g = groups.optJSONArray(gi) ?: return@mapNotNull null
            val all = (0 until g.length()).map { g.getJSONObject(it) }
            all.firstOrNull { it.optBoolean("hit") } ?: all.firstOrNull()
        }
    }

    private fun deletable(m: JSONObject, r: Routine): Boolean {
        val type = m.optInt("type", -1)
        if (type != 0 && type != 19) return false
        if (m.optJSONObject("author")?.optString("id") != meId) return false
        if (r.keepPinned && m.optBoolean("pinned")) return false
        val words = r.exclude.split(',', '、').map { it.trim() }.filter { it.isNotEmpty() }
        val content = m.optString("content")
        return words.none { content.contains(it, ignoreCase = true) }
    }

    /** 投稿時刻はメッセージID(snowflake)から正確に求められる。 */
    private fun ts(m: JSONObject): Long = snowflakeMs(m.optString("id"))

    companion object {
        const val DAY = 24L * 3600 * 1000
        private const val EPOCH = 1420070400000L
        fun snowflake(ms: Long): String = ((ms - EPOCH).coerceAtLeast(0) shl 22).toString()
        fun snowflakeMs(id: String): Long = runCatching { (id.toBigInteger().shiftRight(22).toLong()) + EPOCH }.getOrDefault(0)
        fun dateStr(ms: Long): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
            return "${c.get(java.util.Calendar.YEAR)}/${c.get(java.util.Calendar.MONTH) + 1}/${c.get(java.util.Calendar.DAY_OF_MONTH)}"
        }
    }
}
