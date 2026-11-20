package com.example.discordcleaner

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/** discord.com を開き、assets/inject.js(削除ツール本体)を注入するだけのWebViewアプリ。 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var updater: Updater
    private val injectJs: String by lazy {
        assets.open("inject.js").bufferedReader().use { it.readText() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 従来版(v1.3以前)と同じ設定・同じデスクトップUA
            userAgentString = DESKTOP_UA
        }
        webView.webChromeClient = WebChromeClient() // alert / confirm / prompt 用
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val host = url?.let { android.net.Uri.parse(it).host } ?: return
                if (host == "discord.com" || host.endsWith(".discord.com")) {
                    // 二重注入防止(SPA内遷移で onPageFinished が複数回来ても1回だけ)
                    view.evaluateJavascript(
                        "if(!window.__dcInjected){window.__dcInjected=true;\n$injectJs\n}", null
                    )
                }
            }
        }

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(START_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        updater = Updater(this)
        updater.checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        updater.onResume() // 「提供元不明のアプリ」許可から戻った時にインストールを続行
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush() // ログイン状態を確実に保存
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> { webView.reload(); true }
        R.id.action_update -> { updater.checkForUpdate(manual = true); true }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        private const val START_URL = "https://discord.com/app"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
