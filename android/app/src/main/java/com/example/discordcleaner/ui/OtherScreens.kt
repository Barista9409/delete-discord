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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
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
  var ok=function(t){return typeof t==='string'&&/^([\w-]{20,}\.[\w-]{5,}\.[\w-]{20,}|mfa\.[\w-]{20,})$/.test(t)};
  try{ if(typeof window.__dcTok==='string'&&ok(window.__dcTok)) return window.__dcTok; }catch(e){}
  // 連続スキャンでDiscordのi18n警告が大量に出るのを避け、4秒に1回だけ探す
  var now=Date.now();
  try{ if(window.__dcScanAt&&now-window.__dcScanAt<4000) return null; }catch(e){}
  try{ window.__dcScanAt=now; }catch(e){}
  var found=null;
  try{
    var names=Object.keys(window).filter(function(k){return k.indexOf('webpackChunk')===0});
    for(var n=0;n<names.length&&!found;n++){
      try{
        window[names[n]].push([[Symbol()],{},function(req){
          var mods=Object.values(req.c||{});
          for(var i=0;i<mods.length;i++){
            if(found) return;
            try{
              var e=mods[i]&&mods[i].exports; if(!e) continue;
              var cs=[e.default,e.Z,e.ZP];
              for(var j=0;j<cs.length;j++){
                var c=cs[j]; if(!c) continue;
                var gt; try{ gt=c.getToken; }catch(x){ continue; }
                if(typeof gt!=='function') continue;
                var t; try{ t=c.getToken(); }catch(x){}
                if(ok(t)){ found=t; return; }
              }
            }catch(x){}
          }
        }]);
      }catch(x){}
    }
  }catch(x){}
  try{ if(!found){var raw=localStorage.getItem('token'); if(raw){var p=JSON.parse(raw); if(ok(p)) found=p;}} }catch(x){}
  if(found){ try{ window.__dcTok=found; }catch(e){} }
  return found;
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
    var pc by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val hasAccounts = remember { vm.store.accounts.value.isNotEmpty() }
    val logLines by DebugLog.lines.collectAsState()
    var manualOpen by remember { mutableStateOf(false) }

    // WebViewは画面を開いた時点で作り、そのまま読み込みを始める
    val wv = remember {
        DebugLog.d("login", "ログイン画面を開く 再ログイン=${s.relogin} 既存アカウント=$hasAccounts")
        WebView.setWebContentsDebuggingEnabled(true) // PCのchrome://inspectで中身を見られるように
        WebView(ctx).apply {
            // 中身はあるのに画面が真っ暗/真っ白になるのを防ぐ: サイズと背景を明示し、ハードウェア描画にする
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.WHITE)
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = DiscordApi.browserUA  // 端末の実際のChrome(モバイル)を名乗る
            // Discordはクッキーが無いとログインページを描画できない(空白になる)ことがある
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            DebugLog.d("login", "UA(Chrome): ${DiscordApi.browserUA}")
            webChromeClient = object : WebChromeClient() {
                var lastLogged = -1
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (!done && newProgress < 100) status = "読み込み中… $newProgress%"
                    else if (!done) status = "ログイン画面の表示に10〜20秒かかることがあります"
                    if (newProgress == 100 || newProgress / 50 != lastLogged / 50) {
                        lastLogged = newProgress; DebugLog.d("web", "進捗 $newProgress%")
                    }
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    val lv = m.messageLevel()
                    val msg = m.message()
                    // トークン探索でi18nプロキシに触れると大量に出る警告は記録しない(無害なノイズ)
                    if (msg.contains("requested locale") || msg.contains("does not have a value")) return true
                    if (lv == android.webkit.ConsoleMessage.MessageLevel.ERROR || lv == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                        DebugLog.d("console", "$lv ${msg.take(300)} (${DebugLog.url(m.sourceId())}:${m.lineNumber()})")
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

    // トークンを受け取って検証・登録する(自動検出と手動入力の共通処理)
    fun register(token: String) {
        if (done) return
        done = true
        status = "確認中…"
        scope.launch {
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
                } else status = "ログイン情報を確認できませんでした。もう一度お試しください"
                done = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("Discordにログイン", onBack = { vm.back() }) {
            TextButton(onClick = { manualOpen = true }) { Text("手動入力") }
            TextButton(onClick = {
                pc = !pc
                wv.settings.userAgentString = if (pc) DiscordApi.UA else DiscordApi.browserUA
                DebugLog.d("login", "表示切替 → ${if (pc) "PC" else "Chrome"} UA: ${wv.settings.userAgentString}")
                wv.loadUrl(LOGIN_URL)
            }) { Text(if (pc) "Chrome表示" else "PC表示") }
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
            if (polls <= 15 || polls % 10 == 1) {
                val diag = evalJs(wv, DIAG_JS)
                DebugLog.d("login", "状態#$polls url=${DebugLog.url(wv.url)} ページ=$diag")
            }
            val host = runCatching { Uri.parse(wv.url ?: "").host }.getOrNull()
            if (host != "discord.com") continue
            val token = runCatching { JSONTokener(evalJs(wv, TOKEN_JS)).nextValue() as? String }.getOrNull()
            if (token == null) continue
            DebugLog.d("login", "トークン検出(長さ ${token.length})")
            register(token)
        }
    }
    if (manualOpen) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { manualOpen = false },
            title = { Text("トークンを手動で入力") },
            text = {
                Column {
                    Text("自動で取得できないときの手段です。PCのブラウザや前のアプリで取り出したトークンを貼り付けてください。トークンは暗号化して端末内に保存され、Discord以外には送りません。",
                        fontSize = 13.sp, color = C.muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(input, { input = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("トークン") })
                }
            },
            confirmButton = {
                TextButton(onClick = { manualOpen = false; register(input.trim()) }, enabled = input.trim().length > 20) { Text("登録") }
            },
            dismissButton = { TextButton(onClick = { manualOpen = false }) { Text("やめる") } },
        )
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
  var mount=function(){try{var m=document.getElementById('app-mount');return m?m.childElementCount:-2;}catch(e){return 'e';}}();
  var note=function(){try{var t=document.body?document.body.innerText:'';return /unsupported|not supported|cookie|javascript|サポート|対応/i.test(t)?t.slice(0,120):'';}catch(e){return '';}}();
  return JSON.stringify({ready:document.readyState,title:document.title,
    bodyLen:document.body?document.body.innerHTML.length:-1,mount:mount,
    text:(document.body?document.body.innerText:'').replace(/\s+/g,' ').slice(0,60),
    chunks:Object.keys(window).filter(function(k){return k.indexOf('webpackChunk')===0}).length,
    ls:(function(){try{return !!localStorage.getItem('token')}catch(e){return 'err'}})(),
    ck:(document.cookie||'').length,note:note,w:innerWidth,h:innerHeight});
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
        Bar("設定")
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

            SectionLabel("履歴")
            Panel {
                NavRow("履歴を見る", "削除の記録", { vm.push(Screen.History) })
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NavRow("履歴を消去", "", { clearAsk = true })
            }

            SectionLabel("アプリ")
            Panel {
                NavRow("更新を確認", "v${BuildConfig.VERSION_NAME}", checkUpdate)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                NavRow("診断ログ", "不具合の調査用", { vm.push(Screen.Logs) })
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

// ------------------------------------------------------------ ブラウザ(ログイン後のDiscord画面)

private class BrowserCtl { var token: String? = null; var injected = false; var zoom = 100 }

/** 保存済みトークンでログイン状態にするJS。iframe経由のlocalStorageに入れて反映する(Discordは直接のlocalStorageを封じているため)。 */
private fun browserLoginJs(token: String): String =
    "(function(){try{var i=document.createElement('iframe');document.body.appendChild(i);" +
    "i.contentWindow.localStorage.token='\"'+'" + token + "'+'\"';i.remove();}catch(e){}" +
    "location.replace('https://discord.com/channels/@me');})();"

private fun zoomJs(pct: Int) = "try{document.documentElement.style.zoom='" + (pct / 100.0) + "'}catch(e){}"

/** 表示倍率をCSSで即時に変える(Discordのviewport設定に左右されず、指定%どおりになる)。 */
private fun applyZoom(wv: WebView, pct: Int) {
    (wv.tag as? BrowserCtl)?.zoom = pct
    wv.evaluateJavascript(zoomJs(pct), null)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createBrowserWebView(appCtx: android.content.Context): WebView {
    val ctl = BrowserCtl()
    return WebView(appCtx).apply {
        tag = ctl
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = DiscordApi.browserUA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val host = runCatching { Uri.parse(url ?: "").host }.getOrNull() ?: return
                if (host != "discord.com" && !host.endsWith(".discord.com")) return
                if (!ctl.injected && ctl.token != null) {
                    ctl.injected = true
                    view.evaluateJavascript(browserLoginJs(ctl.token!!), null)
                    return
                }
                if (ctl.zoom != 100) view.evaluateJavascript(zoomJs(ctl.zoom), null) // 再読み込み後も倍率を保つ
            }
        }
    }
}

private fun loadBrowser(wv: WebView, token: String?) {
    (wv.tag as? BrowserCtl)?.let { it.token = token; it.injected = false }
    wv.loadUrl("https://discord.com/app")
}

private fun setImmersive(activity: android.app.Activity?, on: Boolean) {
    activity ?: return
    val c = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
    if (on) {
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(bars)
    } else c.show(bars)
}

@Composable
fun BrowserScreen(vm: AppViewModel, onAccounts: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    vm.store.currentAccountId.collectAsState().value
    vm.store.accounts.collectAsState().value
    val acct = vm.currentAccount()
    if (acct == null) {
        Column(Modifier.fillMaxSize()) {
            Bar("ブラウザ")
            Panel(Modifier.padding(16.dp)) { Text("アカウントを追加するとDiscordを開けます", color = C.muted) }
        }
        return
    }
    var zoom by remember { mutableStateOf((vm.browserWeb?.tag as? BrowserCtl)?.zoom ?: 100) }
    var landscape by remember { mutableStateOf(false) }
    val wv = remember { vm.browserWeb ?: createBrowserWebView(ctx.applicationContext).also { vm.browserWeb = it } }

    LaunchedEffect(acct.id) {
        if (vm.browserAccount != acct.id) {
            vm.browserAccount = acct.id
            loadBrowser(wv, vm.store.tokens.get(acct.id))
        }
    }
    // 横向き = 全画面(システムバーもアプリのバーも隠す)。タブを離れたら元に戻す。
    LaunchedEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        vm.immersive = landscape
        setImmersive(activity, landscape)
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            vm.immersive = false
            setImmersive(activity, false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!landscape) {
                Bar("ブラウザ") {
                    AccountChip(acct, onAccounts)
                    TextButton(onClick = { vm.browserWeb?.reload() }) { Text("更新") }
                }
                Row(
                    Modifier.fillMaxWidth().background(C.surface).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("表示倍率", color = C.muted, fontSize = 12.sp)
                    TextButton(onClick = { val n = (zoom - 10).coerceAtLeast(50); if (n != zoom) { zoom = n; applyZoom(wv, n) } }) { Text("−", fontSize = 20.sp) }
                    Text("$zoom%", fontSize = 13.sp, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { val n = (zoom + 10).coerceAtMost(250); if (n != zoom) { zoom = n; applyZoom(wv, n) } }) { Text("＋", fontSize = 20.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { landscape = true }) { Text("横向き") }
                }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { (wv.parent as? android.view.ViewGroup)?.removeView(wv); wv },
            )
        }
        if (landscape) {
            // 全画面中に縦向きへ戻す小さなボタン
            TextButton(
                onClick = { landscape = false },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .background(C.surface.copy(alpha = 0.85f), androidx.compose.foundation.shape.CircleShape),
            ) { Text("縦", color = C.fg) }
        }
    }
    BackHandler(enabled = true) { if (landscape) landscape = false else if (wv.canGoBack()) wv.goBack() }
}
