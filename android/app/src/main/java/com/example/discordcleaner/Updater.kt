package com.example.discordcleaner

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * GitHub Releases の最新版を確認し、新しければAPKをダウンロードしてインストール画面を開く。
 * - 公開リポジトリの releases/latest を見るだけ(トークン等は一切送らない)
 * - ダウンロードしたAPKは パッケージ名・署名証明書・SHA-256(取得できれば) を照合してから渡す
 * - 最終的なインストールはAndroid標準の確認画面でユーザーが「更新」を押す
 */
class Updater(private val activity: Activity) {

    private var pendingApk: File? = null
    private var waitingForInstallPermission = false

    fun checkForUpdate(manual: Boolean) {
        val current = BuildConfig.VERSION_NAME
        if (parseVersion(current) == null) {
            if (manual) toast("開発版のため更新確認をスキップしました")
            return
        }
        if (manual) toast("更新を確認中…")
        Thread {
            try {
                val rel = fetchLatestRelease()
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    if (rel != null && isNewer(rel.version, current)) showUpdateDialog(rel)
                    else if (manual) toast("最新版です (v$current)")
                }
            } catch (e: Exception) {
                if (manual) activity.runOnUiThread { toast("更新確認に失敗しました: ${e.message}") }
            }
        }.start()
    }

    fun onResume() {
        val apk = pendingApk ?: return
        if (waitingForInstallPermission && canInstall()) {
            waitingForInstallPermission = false
            launchInstaller(apk)
        }
    }

    private data class Release(val version: String, val apkUrl: String, val sha256: String?, val notes: String)

    private fun fetchLatestRelease(): Release? {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DiscordCleaner-Updater")
        }
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    return Release(tag, a.getString("browser_download_url"), digest, json.optString("body"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpdateDialog(rel: Release) {
        AlertDialog.Builder(activity)
            .setTitle("新しいバージョン v${rel.version}")
            .setMessage("現在: v${BuildConfig.VERSION_NAME}\n\n${rel.notes}".trim())
            .setPositiveButton("更新する") { _, _ -> download(rel) }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun download(rel: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = "ダウンロード中…" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad / 2)
            addView(label); addView(bar)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("v${rel.version} を取得中")
            .setView(box).setCancelable(false).show()

        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "update.apk")
                val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "DiscordCleaner-Updater")
                }
                val md = MessageDigest.getInstance("SHA-256")
                try {
                    if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                output.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                if (pct != last) { last = pct; activity.runOnUiThread { bar.progress = pct } }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                if (rel.sha256 != null && !rel.sha256.equals(actual, ignoreCase = true)) {
                    throw RuntimeException("ファイルのハッシュが一致しません")
                }
                verifyApk(out)
                activity.runOnUiThread { dialog.dismiss(); install(out) }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    AlertDialog.Builder(activity).setTitle("更新に失敗しました")
                        .setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    /** パッケージ名と署名証明書が今のアプリと同じか確認する(違うものは渡さない)。 */
    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File) {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw RuntimeException("APKを読み込めませんでした")
        if (archive.packageName != activity.packageName) throw RuntimeException("別のアプリのAPKです")
        val mine = certDigests(pm.getPackageInfo(activity.packageName, flags))
        val theirs = certDigests(archive)
        // 取得できない端末ではAndroidのインストーラー側の署名チェックに任せる
        if (theirs.isNotEmpty() && mine.isNotEmpty() && mine.intersect(theirs).isEmpty()) {
            throw RuntimeException("署名キーが一致しません")
        }
    }

    @Suppress("DEPRECATION")
    private fun certDigests(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return sigs?.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()

    private fun install(apk: File) {
        pendingApk = apk
        if (canInstall()) { launchInstaller(apk); return }
        AlertDialog.Builder(activity)
            .setTitle("インストールの許可")
            .setMessage("次の画面で「この提供元のアプリを許可」をオンにして、戻ってください。初回のみ必要です。")
            .setPositiveButton("設定を開く") { _, _ ->
                waitingForInstallPermission = true
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LATEST_API = "https://api.github.com/repos/Barista9409/delete-discord/releases/latest"

        fun parseVersion(v: String): List<Int>? =
            v.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }

        fun isNewer(remote: String, current: String): Boolean {
            val r = parseVersion(remote) ?: return false
            val c = parseVersion(current) ?: return false
            for (i in 0 until maxOf(r.size, c.size)) {
                val a = r.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }
    }
}
