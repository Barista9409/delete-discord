package com.example.discordcleaner

import android.app.Application
import com.example.discordcleaner.data.DebugLog
import com.example.discordcleaner.discord.DiscordApi
import com.example.discordcleaner.work.Notifications
import com.example.discordcleaner.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        // 端末のWebViewのChromeバージョンに合わせたPC版UA(古いバージョンを名乗るとDiscordが正しく表示しないことがある)
        runCatching {
            val def = android.webkit.WebSettings.getDefaultUserAgent(this)
            Regex("""Chrome/(\d+)""").find(def)?.groupValues?.get(1)?.let { DiscordApi.UA = DiscordApi.desktopUa(it) }
            DebugLog.d("app", "既定UA: $def")
        }.onFailure { DebugLog.d("app", "UA取得失敗: ${it.message}") }
        Notifications.createChannels(this)
        Scheduler.ensureTicker(this)
    }
}
