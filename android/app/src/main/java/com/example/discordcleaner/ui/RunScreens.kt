package com.example.discordcleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.discordcleaner.discord.Engine
import com.example.discordcleaner.work.RunWorker

/** 手動削除: 1 対象 → 2 条件 → 3 確認。 */
@Composable
fun WizardScreen(vm: AppViewModel, s: Screen.Wizard) {
    val r = s.draft
    fun update(n: com.example.discordcleaner.data.Routine) = vm.replace(s.copy(draft = n))
    val titles = listOf("対象を選ぶ", "条件", "確認")
    Column(Modifier.fillMaxSize()) {
        Bar(titles[s.step], onBack = {
            if (s.step > 0) { vm.cancelCount(); vm.replace(s.copy(step = s.step - 1)) } else vm.back()
        }) { Text("${s.step + 1}/3  ", color = C.muted) }
        Column(Modifier.padding(horizontal = 16.dp)) { StepDots(s.step) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            when (s.step) {
                0 -> TargetSection(vm, r, ::update)
                1 -> ConditionSection(r, ::update)
                else -> ConfirmBody(vm, r)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s.step) {
                0 -> {
                    val ok = r.scope != com.example.discordcleaner.data.Scope.SELECTED || r.targets.isNotEmpty()
                    BigButton(if (ok) "次へ(${Labels.target(r)})" else "対象を選んでください", { vm.replace(s.copy(step = 1)) }, enabled = ok)
                }
                1 -> BigButton("スキャンする", { vm.startCount(r); vm.replace(s.copy(step = 2)) })
                else -> ConfirmButtons(vm, r)
            }
        }
    }
}

@Composable
private fun ConfirmBody(vm: AppViewModel, r: com.example.discordcleaner.data.Routine) {
    val c = vm.count
    Panel {
        when {
            c.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp))
                Text("  スキャン中… ${if (c.of > 0) "${c.step}/${c.of}" else ""}", color = C.muted)
            }
            c.error != null -> {
                Text(c.error, color = C.danger)
                TextButton(onClick = { vm.startCount(r) }) { Text("もう一度") }
            }
            c.result != null -> {
                val res = c.result
                Text("${res.total}", fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Caption("件(目安)", Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Text(Labels.target(r) + " · " + Labels.condition(r), color = C.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                res.oldest?.let { Caption("最も古い発言: ${Engine.dateStr(it)} · 古い順に削除 · ${Labels.estimate(res.total, r)}", Modifier.fillMaxWidth().padding(top = 4.dp)) }
            }
        }
    }
    c.result?.preview?.takeIf { it.isNotEmpty() }?.let { pv ->
        Spacer(Modifier.height(10.dp))
        SectionLabel("最初に消える発言")
        Panel {
            pv.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Caption("${p.where} · ${Engine.dateStr(p.timestamp)}")
                Text(p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Caption("件数はDiscordの検索結果による目安です。システムメッセージなど削除できないものは実行時にスキップします。")
}

@Composable
private fun ConfirmButtons(vm: AppViewModel, r: com.example.discordcleaner.data.Routine) {
    val res = vm.count.result
    var ask by remember { mutableStateOf(false) }
    BigButton(
        if (res == null) "スキャン待ち" else if (res.total == 0) "対象はありません" else "${res.total}件を削除する",
        { ask = true }, color = C.danger, enabled = res != null && res.total > 0,
    )
    SubButton("ルーチンとして保存", {
        vm.push(Screen.EditRoutine(r.copy(maxPerRun = if (r.maxPerRun == 0) 300 else r.maxPerRun), isNew = true))
    })
    if (ask) AlertDialog(
        onDismissRequest = { ask = false },
        title = { Text("削除しますか？") },
        text = { Text("${res?.total ?: 0}件の発言を古い順に削除します。削除した発言は元に戻せません。") },
        confirmButton = { TextButton(onClick = { ask = false; vm.stack.removeAll { it is Screen.Wizard }; vm.runNow(r, saved = false) }) { Text("削除する", color = C.danger) } },
        dismissButton = { TextButton(onClick = { ask = false }) { Text("やめる") } },
    )
}

/** 実行中画面(WorkManagerの進捗を表示)。閉じても通知で続く。 */
@Composable
fun RunningScreen(vm: AppViewModel, s: Screen.Running) {
    val ctx = LocalContext.current
    val wm = remember { WorkManager.getInstance(ctx) }
    val info by wm.getWorkInfoByIdFlow(s.workId).collectAsState(initial = null)
    val st = info?.state
    val d = if (st != null && st.isFinished) info!!.outputData else info?.progress
    val phase = d?.getString(RunWorker.P_PHASE)
    val found = d?.getInt(RunWorker.P_FOUND, 0) ?: 0
    val del = d?.getInt(RunWorker.P_DELETED, 0) ?: 0
    val skip = d?.getInt(RunWorker.P_SKIPPED, 0) ?: 0
    val fail = d?.getInt(RunWorker.P_FAILED, 0) ?: 0
    val total = d?.getInt(RunWorker.P_TOTAL, 0) ?: 0
    val current = d?.getString(RunWorker.P_CURRENT) ?: ""
    val done = del + skip + fail

    val title = when {
        st == WorkInfo.State.SUCCEEDED -> "完了"
        st == WorkInfo.State.CANCELLED -> "停止しました"
        st == WorkInfo.State.FAILED -> "エラー"
        phase == "SCAN" -> "対象を確認中"
        phase == "DELETE" -> "削除中"
        else -> "開始待ち"
    }
    Column(Modifier.fillMaxSize()) {
        Bar(title, onBack = { vm.back() }) { d?.getString(RunWorker.P_ACCOUNT)?.let { Text("$it  ", color = C.muted) } }
        Column(Modifier.weight(1f).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(180.dp).padding(8.dp), contentAlignment = Alignment.Center) {
                if (phase == "DELETE" || st?.isFinished == true) {
                    CircularProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else if (st?.isFinished == true) 1f else 0f },
                        modifier = Modifier.fillMaxSize(), color = C.mint, trackColor = C.raised, strokeWidth = 12.dp,
                    )
                } else CircularProgressIndicator(Modifier.fillMaxSize(), color = C.accent, trackColor = C.raised, strokeWidth = 12.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (phase == "SCAN") "$found" else "$del", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    Caption(if (phase == "SCAN") "件見つかりました" else if (total > 0) "/ $total 件削除" else "件削除")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("● 削除 $del", color = C.mint, fontSize = 13.sp)
                Text("● スキップ $skip", color = C.muted, fontSize = 13.sp)
                Text("● エラー $fail", color = if (fail > 0) C.danger else C.muted, fontSize = 13.sp)
            }
            if (current.isNotEmpty() && st?.isFinished != true) Panel { Caption("いま"); Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (phase == "DELETE" && total > 0 && st?.isFinished != true) {
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth(), color = C.mint, trackColor = C.raised)
            }
            if (st == WorkInfo.State.FAILED) Text(info?.outputData?.getString(RunWorker.P_ERROR) ?: "", color = C.danger, textAlign = TextAlign.Center)
            if (st == WorkInfo.State.ENQUEUED || phase == "WAIT") Caption("ほかの削除が終わるのを待っています", Modifier.fillMaxWidth())
            if (st?.isFinished != true) Caption("この画面を閉じても続きます(通知から停止できます)")
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st?.isFinished == true) BigButton("ホームへ", { vm.tab(Screen.Home) })
            else BigButton("停止", { wm.cancelWorkById(s.workId) }, color = C.danger)
        }
    }
}
