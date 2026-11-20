package com.example.discordcleaner

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.discordcleaner.ui.AppRoot
import com.example.discordcleaner.ui.AppViewModel
import com.example.discordcleaner.ui.CleanerTheme

class MainActivity : AppCompatActivity() {
    private val vm: AppViewModel by viewModels()
    private lateinit var updater: Updater
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updater = Updater(this)
        setContent {
            CleanerTheme {
                AppRoot(
                    vm,
                    requestNotif = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    checkUpdate = { updater.checkForUpdate(manual = true) },
                )
            }
        }
        if (savedInstanceState == null) updater.checkForUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        updater.onResume() // 「提供元不明のアプリ」許可から戻った時にインストールを続行
    }
}
