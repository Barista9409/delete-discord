package com.example.discordcleaner

import android.app.Application
import com.example.discordcleaner.work.Notifications
import com.example.discordcleaner.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Scheduler.ensureTicker(this)
    }
}
