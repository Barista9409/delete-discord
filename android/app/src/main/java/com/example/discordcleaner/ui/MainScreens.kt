@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.discordcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.discordcleaner.data.Pace
import com.example.discordcleaner.data.Repeat
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.RunStatus
import com.example.discordcleaner.work.RunWorker

// ------------------------------------------------------------ ホーム

@Composable
fun HomeScreen(vm: AppViewModel, onAccounts: () -> Unit) {
    val accounts by vm.store.accounts.collectAsState()
    val routines by vm.store.routines.collectAsState()
    val runs by vm.store.runs.collectAsState()
    vm.store.currentAccountId.collectAsState().value
    val acct = vm.currentAccount()
    val ctx = LocalContext.current
    val works by remember { WorkManager.getInstance(ctx).getWorkInfosByTagFlow(RunWorker.TAG) }.collectAsState(initial = emptyList())
    val running = works.firstOrNull { it.state == WorkInfo.State.RUNNING }

    Column(Modifier.fillMaxSize()) {
        Bar("ホーム") {
            if (accounts.isNotEmpty()) AccountChip(acct, onAccounts)
            IconButton(onClick = { vm.push(Screen.Settings) }) { Icon(Icons.Default.Settings, "設定") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (acct == null) {
                Panel {
                    Text("はじめに", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Discordアカウントを追加してください。以前のバージョンでログインしていた場合は、ログインし直さなくても自動で取り込まれます。", color = C.muted, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    BigButton("アカウントを追加", { vm.push(Screen.Login(relogin = false)) })
                }
                return@Column
            }
            if (acct.needsLogin) Panel(onClick = { vm.push(Screen.Login(relogin = true)) }) {
                Text("${acct.name} のログインが切れています", color = C.danger, fontWeight = FontWeight.Bold)
                Caption("タップして再ログインしてください。それまでこのアカウントのルーチンは実行されません。")
            }
            if (running != null) Panel(onClick = { vm.push(Screen.Running(running.id)) }) {
                val p = running.progress
                Text("実行中: ${p.getString(RunWorker.P_NAME) ?: "削除"}", fontWeight = FontWeight.Bold)
                Caption("${p.getInt(RunWorker.P_DELETED, 0)}件削除 · タップで詳細")
            }

            val mine = routines.filter { it.accountId == acct.id }
            val next = mine.filter { it.isScheduled && it.nextRunAt != null }.minByOrNull { it.nextRunAt!! }
            Panel {
                Caption("次の自動実行")
                if (next == null) {
                    Text("予定はありません", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Caption("ルーチンでスケジュールを設定すると、自動で古い発言を消せます。")
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Labels.whenText(next.nextRunAt!!) + " 頃", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(next.name, color = C.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(checked = next.enabled, onCheckedChange = { vm.setEnabled(next, it) })
                }
            }

            BigButton("今すぐ削除する", {
                vm.push(Screen.Wizard(Routine(accountId = acct.id, olderThanDays = null, maxPerRun = 0)))
            })

            if (mine.isNotEmpty()) Panel {
                Caption("よく使うルーチン")
                mine.take(4).forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Caption(Labels.schedule(r) + " · " + Labels.condition(r).substringBefore(" · "))
                        }
                        TextButton(onClick = { vm.runNow(r, saved = true) }) { Icon(Icons.Default.PlayArrow, null); Text("実行") }
                    }
                }
            }

            runs.firstOrNull { it.accountId == acct.id }?.let { last ->
                Panel(onClick = { vm.tab(Screen.History) }) {
                    Caption("前回の実行 · ${Labels.whenText(last.startedAt)}")
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${last.deleted}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.mint)
                        Text("  件削除 · ${Labels.status(last.status)} · ${Labels.duration(last.endedAt - last.startedAt)}", color = C.muted, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------ ルーチン一覧

@Composable
fun RoutinesScreen(vm: AppViewModel, onAccounts: () -> Unit) {
    val routines by vm.store.routines.collectAsState()
    vm.store.currentAccountId.collectAsState().value
    vm.store.accounts.collectAsState().value
    val acct = vm.currentAccount()
    val mine = routines.filter { it.accountId == acct?.id }
    Column(Modifier.fillMaxSize()) {
        Bar("ルーチン") { if (acct != null) AccountChip(acct, onAccounts) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mine.isEmpty()) item {
                Panel {
                    Text("ルーチンはまだありません", fontWeight = FontWeight.Bold)
                    Caption("よく使う削除の条件を保存して、ワンタップや決まった時間に実行できます。例: 毎日3時に30日より古い発言を消す")
                }
            }
            items(mine, key = { it.id }) { r ->
                Panel(onClick = { vm.push(Screen.EditRoutine(r, isNew = false)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Caption(Labels.target(r) + " · " + Labels.condition(r))
                            Caption(Labels.schedule(r) + (if (r.isScheduled && r.nextRunAt != null) " · 次回 ${Labels.whenText(r.nextRunAt)}" else ""))
                        }
                        if (r.schedule.repeat != Repeat.MANUAL) Switch(checked = r.enabled, onCheckedChange = { vm.setEnabled(r, it) })
                    }
                    TextButton(onClick = { vm.runNow(r, saved = true) }) { Icon(Icons.Default.PlayArrow, null); Text("今すぐ実行") }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        if (acct != null) Column(Modifier.padding(16.dp)) {
            BigButton("新しいルーチン", { vm.push(Screen.EditRoutine(Routine(accountId = acct.id), isNew = true)) })
        }
    }
}

// ------------------------------------------------------------ ルーチン編集

@Composable
fun RoutineEditScreen(vm: AppViewModel, s: Screen.EditRoutine, requestNotif: () -> Unit) {
    val r = s.routine
    fun update(n: Routine) = vm.replace(s.copy(routine = n))
    val accounts by vm.store.accounts.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Bar(if (s.isNew) "新しいルーチン" else "ルーチン", onBack = { vm.back() }) {
            TextButton(onClick = {
                vm.saveRoutine(r)
                if (r.enabled && r.schedule.repeat != Repeat.MANUAL) requestNotif()
                vm.tab(Screen.Routines)
            }, enabled = r.scope != com.example.discordcleaner.data.Scope.SELECTED || r.targets.isNotEmpty()) { Text("保存", fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(r.name, { update(r.copy(name = it)) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("名前") }, placeholder = { Text(Labels.autoName(r)) })

            SectionLabel("アカウント")
            var open by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountChip(accounts.firstOrNull { it.id == r.accountId }) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    accounts.forEach { a ->
                        DropdownMenuItem(text = { Text(a.name) }, leadingIcon = { Avatar(a, 22.dp) },
                            onClick = { open = false; if (a.id != r.accountId) update(r.copy(accountId = a.id, targets = emptyList())) })
                    }
                }
            }

            SectionLabel("対象")
            Panel { TargetSection(vm, r, ::update) }
            SectionLabel("条件")
            Panel { ConditionSection(r, ::update) }

            SectionLabel("スケジュール")
            Panel { ScheduleSection(r, ::update) }

            SectionLabel("実行する条件")
            Panel {
                SwitchRow("Wi-Fi接続時のみ", r.wifiOnly, "モバイル通信では実行しません") { update(r.copy(wifiOnly = it)) }
                SwitchRow("充電中のみ", r.chargingOnly) { update(r.copy(chargingOnly = it)) }
                SwitchRow("電池残量が少ないときは実行しない", r.batteryNotLow) { update(r.copy(batteryNotLow = it)) }
            }

            SectionLabel("1回の上限と速さ")
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100, 300, 1000, 0).forEach { n -> FilterChip(r.maxPerRun == n, { update(r.copy(maxPerRun = n)) }, { Text(if (n == 0) "無制限" else "${n}件") }) }
                }
                Caption("上限を超えた分は次回に削除します。多いほどアカウント停止のリスクが上がります。")
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.pace == Pace.NORMAL, { update(r.copy(pace = Pace.NORMAL)) }, { Text("標準(1.2〜2.5秒)") })
                    FilterChip(r.pace == Pace.SLOW, { update(r.copy(pace = Pace.SLOW)) }, { Text("ゆっくり(3〜6秒)") })
                }
            }

            if (!s.isNew) {
                Spacer(Modifier.height(8.dp))
                SubButton("今すぐ実行", { vm.saveRoutine(r); vm.runNow(vm.store.routine(r.id) ?: r, saved = true) })
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("このルーチンを削除", color = C.danger) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("ルーチンを削除しますか？") },
        text = { Text("「${r.name}」とそのスケジュールを削除します。これまでに消した発言には影響しません。") },
        confirmButton = { TextButton(onClick = { vm.deleteRoutine(r); confirmDelete = false; vm.tab(Screen.Routines) }) { Text("削除", color = C.danger) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
    )
}

@Composable
private fun ScheduleSection(r: Routine, update: (Routine) -> Unit) {
    val s = r.schedule
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val opts = listOf(Repeat.MANUAL to "手動", Repeat.DAILY to "毎日", Repeat.WEEKLY to "毎週", Repeat.MONTHLY to "毎月")
        opts.forEachIndexed { i, (rep, label) ->
            SegmentedButton(
                selected = s.repeat == rep,
                onClick = { update(r.copy(schedule = s.copy(repeat = rep), enabled = rep != Repeat.MANUAL)) },
                shape = SegmentedButtonDefaults.itemShape(i, opts.size),
            ) { Text(label) }
        }
    }
    if (s.repeat == Repeat.MANUAL) { Caption("ホームやルーチン一覧から手動で実行します。"); return }
    Spacer(Modifier.height(6.dp))
    if (s.repeat == Repeat.WEEKLY) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("月", "火", "水", "木", "金", "土", "日").forEachIndexed { i, l ->
            val d = i + 1
            FilterChip(d in s.weekdays, {
                val n = if (d in s.weekdays) s.weekdays - d else s.weekdays + d
                if (n.isNotEmpty()) update(r.copy(schedule = s.copy(weekdays = n)))
            }, { Text(l) })
        }
    }
    if (s.repeat == Repeat.MONTHLY) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("毎月", color = C.muted); Stepper(s.dayOfMonth, { update(r.copy(schedule = s.copy(dayOfMonth = it))) }, 1, 31, suffix = "日")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("開始", color = C.muted)
        Stepper(s.hour, { update(r.copy(schedule = s.copy(hour = it))) }, 0, 23, suffix = "時")
        Spacer(Modifier.width(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 30).forEach { m -> FilterChip(s.minute == m, { update(r.copy(schedule = s.copy(minute = m))) }, { Text("%02d分".format(m)) }) }
        }
    }
    Caption("毎回同じ時刻にならないよう、前後30分の範囲でずらして実行します。")
    SwitchRow("スケジュールを有効にする", r.enabled) { update(r.copy(enabled = it)) }
}

// ------------------------------------------------------------ 履歴

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val runs by vm.store.runs.collectAsState()
    val accounts by vm.store.accounts.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Bar("履歴")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (runs.isEmpty()) item { Panel { Text("まだ実行していません", color = C.muted) } }
            items(runs, key = { it.id }) { h ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.routineName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val color = when (h.status) { RunStatus.DONE -> C.mint; RunStatus.STOPPED, RunStatus.SKIPPED -> C.warn; else -> C.danger }
                        Text(Labels.status(h.status), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Caption("${Labels.whenText(h.startedAt)} · ${accounts.firstOrNull { it.id == h.accountId }?.name ?: "削除済みのアカウント"}" + if (h.scheduled) " · 自動" else "")
                    Text("削除 ${h.deleted} · スキップ ${h.skipped} · エラー ${h.failed} · ${Labels.duration(h.endedAt - h.startedAt)}", fontSize = 13.sp)
                    if (h.message.isNotEmpty()) Caption(h.message)
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
