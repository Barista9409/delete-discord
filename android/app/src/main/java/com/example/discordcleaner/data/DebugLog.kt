package com.example.discordcleaner.data

import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.example.discordcleaner.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 問題解析用の診断ログ(端末内のみ。自動送信はしない)。
 * - トークンらしき文字列は記録前に必ず伏せ字にする
 * - URLのクエリは落とす
 * - 直近500行をメモリに、ファイルは約200KBで切り詰め
 */
object DebugLog {
    private const val MAX_LINES = 500
    private const val MAX_FILE = 200_000L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var file: File? = null

    // Discordトークン(base64.base64.base64)や mfa. 形式、長い英数字列を伏せる
    private val tokenLike = Regex("""(mfa\.[\w-]{20,})|([\w-]{20,}\.[\w-]{4,}\.[\w-]{20,})""")
    private val authHeader = Regex("""(?i)(authorization["':\s=]+)[^\s"',}]+""")

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "debug.log")
        file = f
        runCatching { if (f.exists()) _lines.value = f.readLines().takeLast(MAX_LINES) }
        val wv = runCatching {
            if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } else null
        }.getOrNull()
        d("app", "起動 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL} / WebView ${wv ?: "不明"}")
    }

    fun redact(s: String): String = authHeader.replace(tokenLike.replace(s, "[伏せ字]"), "$1[伏せ字]")

    /** URLはホストとパスだけにする。 */
    fun url(u: String?): String = u?.substringBefore('?')?.substringBefore('#')?.take(200) ?: "(null)"

    @Synchronized
    fun d(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] ${redact(msg).replace('\n', ' ').take(500)}"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        runCatching {
            val f = file ?: return
            if (f.length() > MAX_FILE) f.writeText(_lines.value.takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
            f.appendText(line + "\n")
        }
    }

    fun text(): String = _lines.value.joinToString("\n")

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }
}
