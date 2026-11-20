package com.example.discordcleaner.ui

import com.example.discordcleaner.data.Kind
import com.example.discordcleaner.data.Repeat
import com.example.discordcleaner.data.Routine
import com.example.discordcleaner.data.RunStatus
import com.example.discordcleaner.data.Scope
import com.example.discordcleaner.discord.Engine
import java.util.Calendar

object Labels {
    private val WD = listOf("月", "火", "水", "木", "金", "土", "日")

    fun target(r: Routine): String = when (r.scope) {
        Scope.ALL -> "すべてのサーバーとDM"
        Scope.ALL_GUILDS -> "すべてのサーバー"
        Scope.ALL_DMS -> "すべてのDM"
        Scope.SELECTED -> when (r.targets.size) {
            0 -> "未選択"
            1 -> r.targets[0].name
            else -> "${r.targets[0].name} ほか${r.targets.size - 1}件"
        }
    }

    fun condition(r: Routine): String {
        val parts = mutableListOf<String>()
        parts += when {
            r.olderThanDays != null -> "${r.olderThanDays}日より古い"
            r.fromDate != null || r.toDate != null ->
                "${r.fromDate?.let { Engine.dateStr(it) } ?: "最初"}〜${r.toDate?.let { Engine.dateStr(it) } ?: "今日"}"
            else -> "全期間"
        }
        if (r.keyword.isNotBlank()) parts += "「${r.keyword}」を含む"
        if (r.exclude.isNotBlank()) parts += "除外あり"
        when (r.kind) { Kind.FILE -> parts += "添付あり"; Kind.LINK -> parts += "リンクあり"; Kind.ALL -> {} }
        return parts.joinToString(" · ")
    }

    fun schedule(r: Routine): String {
        val s = r.schedule
        val t = "%d:%02d".format(s.hour, s.minute)
        return when (s.repeat) {
            Repeat.MANUAL -> "手動のみ"
            Repeat.DAILY -> "毎日 $t"
            Repeat.WEEKLY -> "毎週${s.weekdays.sorted().joinToString("・") { WD[it - 1] }} $t"
            Repeat.MONTHLY -> "毎月${s.dayOfMonth}日 $t"
        } + if (s.repeat != Repeat.MANUAL && !r.enabled) "(停止中)" else ""
    }

    fun autoName(r: Routine) = "${target(r)} · ${condition(r).substringBefore(" · ")}"

    fun whenText(ms: Long, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val t = "%d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val dayDiff = ((startOfDay(c) - startOfDay(n)) / Engine.DAY).toInt()
        return when (dayDiff) {
            0 -> "今日 $t"; 1 -> "明日 $t"; -1 -> "昨日 $t"
            else -> "${c.get(Calendar.MONTH) + 1}/${c.get(Calendar.DAY_OF_MONTH)}(${WD[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]}) $t"
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "${s / 3600}時間${s % 3600 / 60}分" else if (s >= 60) "${s / 60}分${s % 60}秒" else "${s}秒"
    }

    fun status(s: RunStatus) = when (s) {
        RunStatus.DONE -> "完了"; RunStatus.STOPPED -> "停止"; RunStatus.SKIPPED -> "見送り"
        RunStatus.ERROR -> "エラー"; RunStatus.NEEDS_LOGIN -> "要ログイン"
    }

    /** 削除にかかる時間の目安。 */
    fun estimate(count: Int, r: Routine): String {
        val per = (r.pace.minMs + r.pace.maxMs) / 2 + 300
        return "約" + duration(count * per)
    }
}
