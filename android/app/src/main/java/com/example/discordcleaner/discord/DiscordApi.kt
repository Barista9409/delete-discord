package com.example.discordcleaner.discord

import com.example.discordcleaner.data.DebugLog
import com.example.discordcleaner.data.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AuthException : Exception("ログインが切れています。アカウントを再ログインしてください")

class ApiException(val status: Int, val code: Int?, msg: String) : Exception(msg)

/**
 * Discord REST API(v9)の最小クライアント。
 * トークンは Authorization ヘッダーにだけ載せ、ログや例外メッセージには含めない。
 */
class DiscordApi(private val token: String) {

    data class Resp(val status: Int, val body: String) {
        fun json() = JSONObject(body.ifEmpty { "{}" })
    }

    suspend fun me(): JSONObject = getJson("/users/@me")

    suspend fun guilds(): List<Target> {
        val arr = JSONArray(call("GET", "/users/@me/guilds").also { check(it) }.body)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Target(Target.GUILD, it.getString("id"), it.optString("name")) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun dms(): List<Target> {
        val arr = JSONArray(call("GET", "/users/@me/channels").also { check(it) }.body)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { c ->
            val rec = c.optJSONArray("recipients") ?: JSONArray()
            val names = (0 until rec.length()).map { i ->
                rec.getJSONObject(i).let { u -> u.optString("global_name").takeIf { n -> n.isNotEmpty() && n != "null" } ?: u.optString("username") }
            }
            val name = if (c.optInt("type") == 3) c.optString("name").takeIf { it.isNotEmpty() && it != "null" }
                ?: names.joinToString(", ").ifEmpty { "グループDM" }
            else names.firstOrNull() ?: "DM"
            Target(Target.DM, c.getString("id"), name)
        }
    }

    /** メッセージ検索。202(インデックス作成中)と429は待って再試行する。 */
    suspend fun search(t: Target, params: Map<String, String>): JSONObject {
        val all = if (t.isGuild) params + ("include_nsfw" to "true") else params
        val q = all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val path = if (t.isGuild) "/guilds/${t.id}/messages/search?$q" else "/channels/${t.id}/messages/search?$q"
        repeat(8) {
            val r = call("GET", path)
            if (r.status == 202) { delay(retryAfterMs(r, 3000)); return@repeat }
            check(r)
            return r.json()
        }
        throw ApiException(202, null, "検索インデックスの準備が終わりませんでした。時間をおいて再実行してください")
    }

    enum class DeleteResult { DELETED, SKIPPED, FAILED }

    suspend fun delete(channelId: String, messageId: String): DeleteResult {
        val r = call("DELETE", "/channels/$channelId/messages/$messageId")
        return when {
            r.status == 204 || r.status == 200 -> DeleteResult.DELETED
            r.status == 404 -> DeleteResult.SKIPPED                  // 既に消えている
            r.status == 401 -> throw AuthException()
            errorCode(r) == 50021 -> DeleteResult.SKIPPED            // システムメッセージ
            else -> DeleteResult.FAILED
        }
    }

    private suspend fun getJson(path: String): JSONObject = call("GET", path).also { check(it) }.json()

    private fun check(r: Resp) {
        if (r.status in 200..299) return
        if (r.status == 401) throw AuthException()
        throw ApiException(r.status, errorCode(r), "Discord APIエラー (HTTP ${r.status})")
    }

    private fun errorCode(r: Resp): Int? = runCatching { r.json().takeIf { it.has("code") }?.getInt("code") }.getOrNull()

    private fun retryAfterMs(r: Resp, default: Long): Long =
        runCatching { (r.json().getDouble("retry_after") * 1000).toLong() }.getOrDefault(default) + 500

    /** 429 は retry_after だけ待って自動で再試行する。 */
    private suspend fun call(method: String, path: String): Resp {
        var attempt = 0
        while (true) {
            val r = rawCall(method, path)
            if (r.status == 429 && attempt++ < 10) { delay(retryAfterMs(r, 2000)); continue }
            return r
        }
    }

    private suspend fun rawCall(method: String, path: String): Resp = withContext(Dispatchers.IO) {
        var lastErr: IOException? = null
        repeat(3) { i ->
            try {
                val conn = (URL(API + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 15000; readTimeout = 30000
                    setRequestProperty("Authorization", token)
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val status = conn.responseCode
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    DebugLog.d("api", "$method ${DebugLog.url(path)} → $status" + if (status >= 400) " ${body.take(200)}" else "")
                    return@withContext Resp(status, body)
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                lastErr = e
                DebugLog.d("api", "$method ${DebugLog.url(path)} 通信エラー ${e.javaClass.simpleName}: ${e.message}")
                delay(2000L * (i + 1))
            }
        }
        throw IOException("通信に失敗しました: ${lastErr?.javaClass?.simpleName}")
    }

    companion object {
        private const val API = "https://discord.com/api/v9"
        /** PC版ChromeのUA。起動時に端末のWebViewと同じChromeバージョンへ合わせる(App.onCreate)。 */
        @Volatile var UA = desktopUa("130")

        fun desktopUa(chromeMajor: String) =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeMajor.0.0.0 Safari/537.36"
    }
}
