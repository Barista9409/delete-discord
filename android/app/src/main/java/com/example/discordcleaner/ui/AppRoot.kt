package com.example.discordcleaner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.example.discordcleaner.R

@Composable
fun AppRoot(vm: AppViewModel, requestNotif: () -> Unit, checkUpdate: () -> Unit) {
    var sheet by remember { mutableStateOf(false) }
    val s = vm.screen
    val isTab = s is Screen.Home || s is Screen.Routines || s is Screen.Browser || s is Screen.Settings
    BackHandler(enabled = vm.stack.size > 1) {
        if (s is Screen.Wizard && s.step > 0) { vm.cancelCount(); vm.replace(s.copy(step = s.step - 1)) } else vm.back()
    }
    Scaffold(
        containerColor = C.bg,
        bottomBar = {
            if (isTab && !vm.immersive) NavigationBar(containerColor = C.surface) {
                NavigationBarItem(s is Screen.Home, { vm.tab(Screen.Home) }, { Icon(Icons.Default.Home, null) }, label = { Text("ホーム") })
                NavigationBarItem(s is Screen.Routines, { vm.tab(Screen.Routines) }, { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("ルーチン") })
                NavigationBarItem(s is Screen.Browser, { vm.tab(Screen.Browser) }, { Icon(painterResource(R.drawable.ic_tab_browser), null, modifier = Modifier.size(24.dp)) }, label = { Text("ブラウザ") })
                NavigationBarItem(s is Screen.Settings, { vm.tab(Screen.Settings) }, { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.fillMaxSize()) {
                when (s) {
                    Screen.Home -> HomeScreen(vm) { sheet = true }
                    Screen.Routines -> RoutinesScreen(vm) { sheet = true }
                    Screen.Browser -> BrowserScreen(vm) { sheet = true }
                    Screen.History -> HistoryScreen(vm)
                    Screen.Settings -> SettingsScreen(vm, requestNotif, checkUpdate)
                    is Screen.Wizard -> WizardScreen(vm, s)
                    is Screen.Running -> RunningScreen(vm, s)
                    is Screen.EditRoutine -> RoutineEditScreen(vm, s, requestNotif)
                    is Screen.Login -> LoginScreen(vm, s)
                    Screen.Logs -> LogsScreen(vm)
                }
            }
        }
    }
    if (sheet) AccountSheet(vm) { sheet = false }
}
