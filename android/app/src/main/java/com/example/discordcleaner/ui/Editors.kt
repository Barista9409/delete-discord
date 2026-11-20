@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.discordcleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.discordcleaner.data.Kind
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.Scope
import com.example.discordcleaner.data.Target
import com.example.discordcleaner.discord.Engine

/** 対象の選択(サーバー / DM / すべて)。 */
@Composable
fun TargetSection(vm: AppViewModel, r: Routine, onChange: (Routine) -> Unit) {
    LaunchedEffect(r.accountId) { if (r.accountId.isNotEmpty()) vm.loadTargets(r.accountId) }
    val list = vm.targetLists[r.accountId]
    var tab by rememberSaveable { mutableStateOf(if (r.scope == Scope.SELECTED) (if (r.targets.firstOrNull()?.isGuild == false) 1 else 0) else 2) }
    var query by rememberSaveable { mutableStateOf("") }

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("サーバー", "DM", "すべて").forEachIndexed { i, label ->
            SegmentedButton(
                selected = tab == i,
                onClick = {
                    tab = i
                    if (i == 2 && r.scope == Scope.SELECTED) onChange(r.copy(scope = Scope.ALL_GUILDS))
                    if (i != 2 && r.scope != Scope.SELECTED) onChange(r.copy(scope = Scope.SELECTED))
                },
                shape = SegmentedButtonDefaults.itemShape(i, 3),
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(8.dp))

    if (tab == 2) {
        listOf(Scope.ALL_GUILDS to "すべてのサーバー", Scope.ALL_DMS to "すべてのDM", Scope.ALL to "すべてのサーバーとDM").forEach { (s, label) ->
            Row(Modifier.fillMaxWidth().clickable { onChange(r.copy(scope = s)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = r.scope == s, onClick = { onChange(r.copy(scope = s)) })
                Text(label)
            }
        }
        Caption("実行するたびに、参加中のサーバー・DMの一覧を取り直します。")
        return
    }

    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        placeholder = { Text(if (tab == 0) "サーバーを検索" else "DMを検索") }, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    when {
        list == null || list.loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.height(18.dp)); Text("  読み込み中…", color = C.muted)
        }
        list.error != null -> Column {
            Text(list.error, color = C.danger, fontSize = 13.sp)
            TextButton(onClick = { vm.loadTargets(r.accountId, force = true) }) { Text("再読み込み") }
        }
        else -> {
            val source = if (tab == 0) list.guilds else list.dms
            val q = query.trim()
            val shown = if (q.isEmpty()) source else source.filter { it.name.contains(q, ignoreCase = true) || it.id == q }
            val type = if (tab == 0) Target.GUILD else Target.DM
            if (q.length >= 17 && q.all { it.isDigit() } && shown.none { it.id == q }) {
                TextButton(onClick = {
                    onChange(r.copy(scope = Scope.SELECTED, targets = r.targets + Target(type, q, "ID $q"))); query = ""
                }) { Text("ID $q を追加") }
            }
            val selectedIds = r.targets.map { it.id }.toSet()
            // 一覧にないIDで追加したもの
            r.targets.filter { t -> t.type == type && source.none { it.id == t.id } }.forEach { t ->
                TargetRow(t, true) { onChange(r.copy(targets = r.targets.filterNot { it.id == t.id })) }
            }
            shown.forEach { t ->
                TargetRow(t, t.id in selectedIds) { on ->
                    onChange(r.copy(scope = Scope.SELECTED, targets = if (on) r.targets + t else r.targets.filterNot { it.id == t.id }))
                }
            }
            if (shown.isEmpty()) Caption("見つかりませんでした")
        }
    }
}

@Composable
private fun TargetRow(t: Target, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 条件(期間・キーワード・種類・ピン留め)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConditionSection(r: Routine, onChange: (Routine) -> Unit) {
    val mode = when { r.olderThanDays != null -> 1; r.fromDate != null || r.toDate != null -> 2; else -> 0 }
    SectionLabel("期間")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(mode == 0, { onChange(r.copy(olderThanDays = null, fromDate = null, toDate = null)) }, { Text("全期間") })
        FilterChip(mode == 1, { onChange(r.copy(olderThanDays = r.olderThanDays ?: 30, fromDate = null, toDate = null)) }, { Text("◯日より古い") })
        FilterChip(mode == 2, { onChange(r.copy(olderThanDays = null, toDate = r.toDate ?: System.currentTimeMillis())) }, { Text("日付を指定") })
    }
    if (mode == 1) {
        val days = r.olderThanDays ?: 30
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("今日から", color = C.muted)
            Stepper(days, { onChange(r.copy(olderThanDays = it)) }, 1, 3650, if (days >= 60) 30 else if (days >= 14) 7 else 1, "日")
            Text("より古い発言", color = C.muted)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 7, 30, 90, 365).forEach { d -> FilterChip(days == d, { onChange(r.copy(olderThanDays = d)) }, { Text(if (d == 365) "1年" else "${d}日") }) }
        }
    }
    if (mode == 2) {
        var pick by remember { mutableStateOf<Int?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pick = 0 }) { Text("開始: " + (r.fromDate?.let { Engine.dateStr(it) } ?: "最初から")) }
            TextButton(onClick = { pick = 1 }) { Text("終了: " + (r.toDate?.let { Engine.dateStr(it) } ?: "今日")) }
        }
        pick?.let { which ->
            val st = rememberDatePickerState(initialSelectedDateMillis = if (which == 0) r.fromDate else r.toDate)
            DatePickerDialog(
                onDismissRequest = { pick = null },
                confirmButton = {
                    TextButton(onClick = {
                        onChange(if (which == 0) r.copy(fromDate = st.selectedDateMillis) else r.copy(toDate = st.selectedDateMillis))
                        pick = null
                    }) { Text("決定") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onChange(if (which == 0) r.copy(fromDate = null) else r.copy(toDate = null)); pick = null
                    }) { Text("指定しない") }
                },
            ) { DatePicker(state = st) }
        }
    }

    SectionLabel("キーワード(任意)")
    OutlinedTextField(r.keyword, { onChange(r.copy(keyword = it)) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("この語を含む発言だけ") })
    OutlinedTextField(r.exclude, { onChange(r.copy(exclude = it)) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("この語を含む発言は残す(カンマ区切り)") })

    SectionLabel("種類")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Kind.ALL to "すべて", Kind.FILE to "添付ファイルあり", Kind.LINK to "リンクあり").forEach { (k, l) ->
            FilterChip(r.kind == k, { onChange(r.copy(kind = k)) }, { Text(l) })
        }
    }
    SwitchRow("ピン留めした発言は残す", r.keepPinned) { onChange(r.copy(keepPinned = it)) }
}

/** 数値だけのテキスト入力(件数など)。 */
@Composable
fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { s -> onChange(s.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0) },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
