package com.example.discordcleaner.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Discordアカウント。トークン本体は TokenVault に暗号化して別保存する。 */
data class Account(
    val id: String,          // DiscordのユーザーID
    val name: String,        // 表示名
    val colorIdx: Int,
    val needsLogin: Boolean = false,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("colorIdx", colorIdx).put("needsLogin", needsLogin)
    companion object {
        fun fromJson(o: JSONObject) = Account(o.getString("id"), o.getString("name"), o.optInt("colorIdx"), o.optBoolean("needsLogin"))
    }
}

/** 削除対象(サーバー or DM)。 */
data class Target(val type: String, val id: String, val name: String) {
    val isGuild get() = type == GUILD
    fun toJson() = JSONObject().put("type", type).put("id", id).put("name", name)
    companion object {
        const val GUILD = "guild"
        const val DM = "dm"
        fun fromJson(o: JSONObject) = Target(o.getString("type"), o.getString("id"), o.optString("name"))
    }
}

enum class Scope { SELECTED, ALL_GUILDS, ALL_DMS, ALL }
enum class Kind { ALL, FILE, LINK }
enum class Pace(val minMs: Long, val maxMs: Long) { NORMAL(1200, 2500), SLOW(3000, 6000) }
enum class Repeat { MANUAL, DAILY, WEEKLY, MONTHLY }

data class Schedule(
    val repeat: Repeat = Repeat.MANUAL,
    val weekdays: Set<Int> = setOf(7),   // 1=月 … 7=日
    val dayOfMonth: Int = 1,
    val hour: Int = 3,
    val minute: Int = 0,
    val jitterMin: Int = 30,
) {
    fun toJson() = JSONObject().put("repeat", repeat.name).put("weekdays", JSONArray(weekdays.toList()))
        .put("dayOfMonth", dayOfMonth).put("hour", hour).put("minute", minute).put("jitterMin", jitterMin)
    companion object {
        fun fromJson(o: JSONObject?): Schedule {
            if (o == null) return Schedule()
            val wd = o.optJSONArray("weekdays")
            return Schedule(
                runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.MANUAL),
                if (wd == null) setOf(7) else (0 until wd.length()).map { wd.getInt(it) }.toSet(),
                o.optInt("dayOfMonth", 1), o.optInt("hour", 3), o.optInt("minute", 0), o.optInt("jitterMin", 30),
            )
        }
    }
}

/** 削除ルーチン。手動削除も「保存しないルーチン」として同じ形で扱う。 */
data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val accountId: String = "",
    val scope: Scope = Scope.SELECTED,
    val targets: List<Target> = emptyList(),
    val olderThanDays: Int? = 30,      // null なら期間指定(from/to)か無制限
    val fromDate: Long? = null,        // エポックms
    val toDate: Long? = null,
    val keyword: String = "",          // 含む(Discord検索の content)
    val exclude: String = "",          // 含まない(カンマ区切り、端末側で除外)
    val kind: Kind = Kind.ALL,
    val keepPinned: Boolean = true,
    val maxPerRun: Int = 300,          // 0 = 無制限
    val pace: Pace = Pace.NORMAL,
    val schedule: Schedule = Schedule(),
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val batteryNotLow: Boolean = true,
    val enabled: Boolean = false,
    val nextRunAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("accountId", accountId)
        .put("scope", scope.name).put("targets", JSONArray(targets.map { it.toJson() }))
        .put("olderThanDays", olderThanDays ?: JSONObject.NULL).put("fromDate", fromDate ?: JSONObject.NULL)
        .put("toDate", toDate ?: JSONObject.NULL).put("keyword", keyword).put("exclude", exclude)
        .put("kind", kind.name).put("keepPinned", keepPinned).put("maxPerRun", maxPerRun).put("pace", pace.name)
        .put("schedule", schedule.toJson()).put("wifiOnly", wifiOnly).put("chargingOnly", chargingOnly)
        .put("batteryNotLow", batteryNotLow).put("enabled", enabled).put("nextRunAt", nextRunAt ?: JSONObject.NULL)

    val isScheduled get() = enabled && schedule.repeat != Repeat.MANUAL

    companion object {
        private fun JSONObject.optLongOrNull(k: String) = if (isNull(k) || !has(k)) null else getLong(k)
        private fun JSONObject.optIntOrNull(k: String) = if (isNull(k) || !has(k)) null else getInt(k)
        private inline fun <reified E : Enum<E>> JSONObject.enum(k: String, d: E): E =
            runCatching { enumValueOf<E>(getString(k)) }.getOrDefault(d)

        fun fromJson(o: JSONObject): Routine {
            val t = o.optJSONArray("targets") ?: JSONArray()
            return Routine(
                id = o.getString("id"), name = o.optString("name"), accountId = o.optString("accountId"),
                scope = o.enum("scope", Scope.SELECTED),
                targets = (0 until t.length()).map { Target.fromJson(t.getJSONObject(it)) },
                olderThanDays = o.optIntOrNull("olderThanDays"), fromDate = o.optLongOrNull("fromDate"),
                toDate = o.optLongOrNull("toDate"), keyword = o.optString("keyword"), exclude = o.optString("exclude"),
                kind = o.enum("kind", Kind.ALL), keepPinned = o.optBoolean("keepPinned", true),
                maxPerRun = o.optInt("maxPerRun", 300), pace = o.enum("pace", Pace.NORMAL),
                schedule = Schedule.fromJson(o.optJSONObject("schedule")),
                wifiOnly = o.optBoolean("wifiOnly", true), chargingOnly = o.optBoolean("chargingOnly"),
                batteryNotLow = o.optBoolean("batteryNotLow", true), enabled = o.optBoolean("enabled"),
                nextRunAt = o.optLongOrNull("nextRunAt"),
            )
        }
    }
}

enum class RunStatus { DONE, STOPPED, SKIPPED, ERROR, NEEDS_LOGIN }

/** 実行履歴。メッセージ本文は保存しない。 */
data class RunRecord(
    val id: String = UUID.randomUUID().toString(),
    val routineId: String?,
    val routineName: String,
    val accountId: String,
    val startedAt: Long,
    val endedAt: Long,
    val found: Int,
    val deleted: Int,
    val skipped: Int,
    val failed: Int,
    val status: RunStatus,
    val message: String = "",
    val scheduled: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("routineId", routineId ?: JSONObject.NULL)
        .put("routineName", routineName).put("accountId", accountId).put("startedAt", startedAt).put("endedAt", endedAt)
        .put("found", found).put("deleted", deleted).put("skipped", skipped).put("failed", failed)
        .put("status", status.name).put("message", message).put("scheduled", scheduled)
    companion object {
        fun fromJson(o: JSONObject) = RunRecord(
            o.getString("id"), if (o.isNull("routineId")) null else o.optString("routineId"), o.optString("routineName"),
            o.optString("accountId"), o.optLong("startedAt"), o.optLong("endedAt"), o.optInt("found"), o.optInt("deleted"),
            o.optInt("skipped"), o.optInt("failed"),
            runCatching { RunStatus.valueOf(o.getString("status")) }.getOrDefault(RunStatus.ERROR),
            o.optString("message"), o.optBoolean("scheduled"),
        )
    }
}
