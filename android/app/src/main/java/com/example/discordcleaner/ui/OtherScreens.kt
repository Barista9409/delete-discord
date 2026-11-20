@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.discordcleaner.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.discordcleaner.BuildConfig
import com.example.discordcleaner.data.Account
import com.example.discordcleaner.data.DebugLog
import com.example.discordcleaner.discord.DiscordApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONTokener
import kotlin.coroutines.resume

// ------------------------------------------------------------ アカウント切替シート

@Composable
fun AccountSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val accounts by vm.store.accounts.collectAsState()
    val routines by vm.store.routines.collectAsState()
    val cur by vm.store.currentAccountId.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = C.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("アカウント", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
            accounts.forEach { a ->
                val mine = routines.filter { it.accountId == a.id }
                val next = mine.filter { it.isScheduled && it.nextRunAt != null }.minByOrNull { it.nextRunAt!! }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (a.id == cur) C.raised else C.surface)
                        .clickable { vm.store.setCurrent(a.id); onDismiss() }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(a, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name)
                        Caption(if (a.needsLogin) "要再ログイン" else "ルーチン ${mine.size}" + (next?.let { " · 次回 ${Labels.whenText(it.nextRunAt!!)}" } ?: ""))
                    }
                    if (a.id == cur) Text("✓", color = C.mint, fontWeight = FontWeight.Bold)
                    if (a.needsLogin) Text(" !", color = C.danger, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            SubButton("＋ アカウントを追加", { onDismiss(); vm.push(Screen.Login(relogin = false)) })
        }
    }
}

// ------------------------------------------------------------ ログイン(トークン取得)

private const val TOKEN_JS = """
(function(){
  var ok=function(t){return typeof t==='string'&&t.length>20&&t.length<200};
  try{
    var names=Object.keys(window).filter(function(k){return k.indexOf('webpackChunk')===0});
    for(var n=0;n<names.length;n++){
      var tok=null;
      try{
        window[names[n]].push([[Symbol()],{},function(req){
          var mods=Object.values(req.c||{});
          for(var i=0;i<mods.length;i++){
            try{
              var e=mods[i]&&mods[i].exports; if(!e) continue;
              var cs=[e.default,e.Z,e.ZP].filter(Boolean);
              for(var j=0;j<cs.length;j++){
                var c=cs[j];
                if(typeof c.getToken==='function'&&(typeof c.getId==='function'||typeof c.getEmail==='function')){
                  var t=c.getToken(); if(ok(t)&&!tok) tok=t;
                }
              }
            }catch(x){}
          }
        }]);
      }catch(x){}
      if(tok) return tok;
    }
  }catch(x){}
  try{var raw=localStorage.getItem('token'); if(raw){var p=JSON.parse(raw); if(ok(p)) return p;}}catch(x){}
  return null;
})()
"""

/**
 * Discordのログイン画面を開き、ログイン済みになったらトークンを取得して登録する。
 * 取得後はWebViewのCookie等を消す(サーバー側ではログアウトしないのでトークンは有効なまま)。
 * 以前のバージョンでログイン済みなら、そのセッションをそのまま取り込む。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(vm: AppViewModel, s: Screen.Login) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("読み込み中…") }
    var done by remember { mutableStateOf(false) }
    var mobile by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(true) }
    val hasAccounts = remember { vm.store.accounts.value.isNotEmpty() }
    val logLines by DebugLog.lines.collectAsState()

    // WebViewは画面を開いた時点で作り、そのまま読み込みを始める
    val wv = remember {
        DebugLog.d("login", "ログイン画面を開く 再ログイン=${s.relogin} 既存アカウント=$hasAccounts")
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DiscordApi.UA
            DebugLog.d("login", "UA(PC): ${DiscordApi.UA}")
            webChromeClient = object : WebChromeClient() {
                var lastLogged = -1
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (!done && newProgress < 100) status = "読み込み中… $newProgress%"
                    else if (!done) status = "ログインすると自動で登録されます"
                    if (newProgress == 100 || newProgress / 50 != lastLogged / 50) {
                        lastLogged = newProgress; DebugLog.d("web", "進捗 $newProgress%")
                    }
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    val lv = m.messageLevel()
                    if (lv == android.webkit.ConsoleMessage.MessageLevel.ERROR || lv == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                        DebugLog.d("console", "$lv ${m.message().take(300)} (${DebugLog.url(m.sourceId())}:${m.lineNumber()})")
                    }
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    DebugLog.d("web", "読み込み開始 ${DebugLog.url(url)}")
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    DebugLog.d("web", "読み込み完了 ${DebugLog.url(url)}")
                }
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) {
                        status = "読み込みに失敗しました(${error.description})。右上の再読み込みを押してください"
                        DebugLog.d("web", "エラー(メイン) ${error.errorCode} ${error.description} ${DebugLog.url(request.url.toString())}")
                    } else DebugLog.d("web", "エラー(部品) ${error.errorCode} ${error.description} ${DebugLog.url(request.url.toString())}")
                }
                override fun onReceivedHttpError(view: WebView, request: android.webkit.WebResourceRequest, response: android.webkit.WebResourceResponse) {
                    DebugLog.d("web", "HTTP ${response.statusCode}${if (request.isForMainFrame) "(メイン)" else ""} ${DebugLog.url(request.url.toString())}")
                }
                override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    DebugLog.d("web", "SSLエラー ${error.primaryError} ${DebugLog.url(error.url)}")
                    handler.cancel()
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    DebugLog.d("web", "描画プロセス停止 crash=${detail.didCrash()}")
                    status = "表示が停止しました。戻ってもう一度開いてください"
                    return true
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Discordにログイン", onBack = { vm.back() }) {
            TextButton(onClick = {
                mobile = !mobile
                wv.settings.userAgentString = if (mobile) null else DiscordApi.UA
                DebugLog.d("login", "表示切替 → ${if (mobile) "スマホ" else "PC"} UA: ${wv.settings.userAgentString}")
                wv.loadUrl(LOGIN_URL)
            }) { Text(if (mobile) "PC表示" else "スマホ表示") }
            TextButton(onClick = { DebugLog.d("login", "再読み込み"); wv.loadUrl(LOGIN_URL) }) { Text("再読込") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "ログを隠す" else "ログ") }
        }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { wv })
        if (showLog) Column(Modifier.fillMaxWidth().background(C.surface).padding(8.dp)) {
            logLines.takeLast(8).forEach {
                Text(it.substringAfter(' ').substringAfter(' '), fontSize = 10.sp, color = C.muted, maxLines = 2,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    // ログイン状態になるまで定期的にトークンを確認
    LaunchedEffect(Unit) {
        // 2つ目以降・再ログインは、前のセッションを消し終えてからログイン画面を出す
        if (hasAccounts || s.relogin) { clearWebData(); DebugLog.d("login", "以前のセッションを消去") }
        wv.loadUrl(LOGIN_URL)
        var polls = 0
        while (true) {
            delay(1500)
            if (done) continue
            polls++
            if (polls % 10 == 1) {
                val diag = evalJs(wv, DIAG_JS)
                DebugLog.d("login", "状態#$polls url=${DebugLog.url(wv.url)} ページ=$diag")
            }
            val host = runCatching { Uri.parse(wv.url ?: "").host }.getOrNull()
            if (host != "discord.com") continue
            val token = runCatching { JSONTokener(evalJs(wv, TOKEN_JS)).nextValue() as? String }.getOrNull()
            if (token == null) continue
            DebugLog.d("login", "トークン検出(長さ ${token.length})")
            if (!done) {
                done = true
                status = "確認中…"
                scope.launch {
                    // 追加のつもりで既存アカウントのセッションが残っていた場合は登録しない
                    val known = vm.store.accounts.value.map { it.id }.toSet()
                    val res = vm.registerToken(token, allowExisting = s.relogin || !hasAccounts, known = known)
                    val a = res.getOrNull()
                    if (a != null) {
                        DebugLog.d("login", "登録成功 ${a.name}")
                        status = "${a.name} を登録しました"
                        clearWebData()
                        vm.back()
                    } else {
                        val e = res.exceptionOrNull()
                        DebugLog.d("login", "登録失敗 ${e?.javaClass?.simpleName}: ${e?.message}")
                        if (e is AlreadyRegistered) {
                            status = "${e.name} は登録済みです。別のアカウントでログインしてください"
                            clearWebData(); wv.loadUrl(LOGIN_URL)
                        } else status = "ログイン情報を確認できませんでした。もう一度ログインしてください"
                        done = false
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { wv.stopLoading(); wv.destroy() } }
}

private suspend fun evalJs(wv: WebView, js: String): String? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { res -> if (cont.isActive) cont.resume(res) }
    }

/** 真っ白などの原因調査用: ページの状態(トークンは含めない)。 */
private const val DIAG_JS = """
(function(){try{
  return JSON.stringify({ready:document.readyState,title:document.title,
    bodyLen:document.body?document.body.innerHTML.length:-1,
    text:(document.body?document.body.innerText:'').slice(0,80),
    chunks:Object.keys(window).filter(function(k){return k.indexOf('webpackChunk')===0}).length,
    ls:(function(){try{return !!localStorage.getItem('token')}catch(e){return 'err'}})(),
    w:innerWidth,h:innerHeight});
}catch(e){return 'diag error: '+e}})()
"""

private const val LOGIN_URL = "https://discord.com/login"

/** WebViewのCookieと保存データを消す(Cookieの削除完了まで待つ)。サーバー側のログアウトはしない。 */
private suspend fun clearWebData() {
    WebStorage.getInstance().deleteAllData()
    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
        CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) }
    }
    CookieManager.getInstance().flush()
    delay(300)
}

class AlreadyRegistered(val name: String) : Exception("already registered")

// ------------------------------------------------------------ 設定

@Composable
fun SettingsScreen(vm: AppViewModel, requestNotif: () -> Unit, checkUpdate: () -> Unit) {
    val ctx = LocalContext.current
    val accounts by vm.store.accounts.collectAsState()
    var removing by remember { mutableStateOf<Account?>(null) }
    var clearAsk by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }  // 設定画面から戻ったときの再評価用
    val notifOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(tick) {
        (ctx.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(ctx.packageName)
    }

    Column(Modifier.fillMaxSize()) {
        Bar("設定", onBack = { vm.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("アカウント")
            Panel {
                accounts.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(a, 30.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name)
                            Caption(if (a.needsLogin) "要再ログイン" else "ログイン中")
                        }
                        TextButton(onClick = { vm.store.setCurrent(a.id); vm.push(Screen.Login(relogin = true)) }) { Text("再ログイン") }
                        TextButton(onClick = { removing = a }) { Text("削除", color = C.danger) }
                    }
                }
                TextButton(onClick = { vm.push(Screen.Login(relogin = false)) }) { Text("＋ アカウントを追加") }
                Caption("ログイン情報(トークン)はこの端末内に暗号化して保存し、Discord以外には送りません。")
            }

            SectionLabel("自動実行")
            Panel {
                NavRow("通知", if (notifOk) "許可済み" else "未許可") { requestNotif(); tick++ }
                Caption("実行中の進捗・停止ボタンと、完了・ログイン切れのお知らせに使います。")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NavRow("電池の最適化", if (batteryOk) "対象外(推奨)" else "最適化中") {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    tick++
                }
                Caption("最適化の対象外にすると、決まった時刻に実行されやすくなり、長い削除も途中で止まりにくくなります。")
            }

            SectionLabel("アプリ")
            Panel {
                NavRow("更新を確認", "v${BuildConfig.VERSION_NAME}", checkUpdate)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NavRow("診断ログ", "不具合の調査用", { vm.push(Screen.Logs) })
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NavRow("履歴を消去", "", { clearAsk = true })
            }
            Caption("ユーザートークンによる自動操作はDiscordの利用規約に反する可能性があり、アカウント停止のリスクがあります。上限件数と間隔は控えめにしてください。")
            Spacer(Modifier.height(16.dp))
        }
    }
    removing?.let { a ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("${a.name} を削除しますか？") },
            text = { Text("この端末からログイン情報と、このアカウントのルーチンを削除します。Discordの発言やアカウント自体には影響しません。") },
            confirmButton = { TextButton(onClick = { vm.removeAccount(a); removing = null }) { Text("削除", color = C.danger) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("やめる") } },
        )
    }
    if (clearAsk) AlertDialog(
        onDismissRequest = { clearAsk = false },
        title = { Text("履歴を消去しますか？") },
        confirmButton = { TextButton(onClick = { vm.store.clearRuns(); clearAsk = false }) { Text("消去", color = C.danger) } },
        dismissButton = { TextButton(onClick = { clearAsk = false }) { Text("やめる") } },
    )
}

// ------------------------------------------------------------ 診断ログ

@Composable
fun LogsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val lines by DebugLog.lines.collectAsState()
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Bar("診断ログ", onBack = { vm.back() }) {
            TextButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("診断ログ", DebugLog.text()))
                copied = true
            }) { Text(if (copied) "コピー済" else "コピー") }
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, DebugLog.text())
                ctx.startActivity(Intent.createChooser(send, "診断ログを共有"))
            }) { Text("共有") }
            TextButton(onClick = { DebugLog.clear() }) { Text("消去") }
        }
        Caption("トークンは記録しません(トークンらしき文字列は自動で伏せ字にします)。端末の外には自動で送信されません。", Modifier.padding(horizontal = 16.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            items(lines.size) { i ->
                val l = lines[lines.size - 1 - i]   // 新しい順
                Text(l, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = if ("エラー" in l || "ERROR" in l || "失敗" in l) C.danger else C.fg,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
