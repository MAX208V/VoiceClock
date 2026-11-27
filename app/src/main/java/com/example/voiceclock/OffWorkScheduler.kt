package com.example.voiceclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.ZonedDateTime

object OffWorkScheduler {
    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun permitted(context: Context) = Build.VERSION.SDK_INT < 31 || manager(context).canScheduleExactAlarms()

    /**
     * PendingIntent 的身份由 requestCode + Intent 比对（extras 不参与比对），
     * 因此把 type 写进 data，确保「提前提醒」和「到点提醒」是两个不同实体，
     * 否则后注册的会覆盖先注册的，导致只剩一次提醒。
     */
    private fun operation(context: Context, type: String) = PendingIntent.getBroadcast(
        context,
        if (type == "advance") 3 else 4,
        Intent(context, OffWorkReceiver::class.java)
            .setData(Uri.parse("voiceclock://offwork/" + type))
            .putExtra("type", type),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** 计算下一个需要提醒的时刻（下班时刻本身，或提前提醒时刻）。 */
    private fun nextOccurrence(
        now: ZonedDateTime,
        target: ZonedDateTime,
        weekdays: Set<Int>
    ): ZonedDateTime? {
        var candidate = target
        // 若目标时刻已过去，顺延到下一个生效星期
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        var day = candidate
        for (i in 0..6) {
            if (weekdays.contains(day.dayOfWeek.value) && !day.isBefore(now)) {
                return day
            }
            day = day.plusDays(1)
        }
        return null
    }

    fun schedule(context: Context): Boolean {
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) { cancel(context); return true }
        val now = ZonedDateTime.now()
        val target = settings.timeOn(now)

        val alarms = mutableListOf<Pair<Long, String>>()
        val advance = settings.advanceTimeOn(now)
        if (advance != null) {
            val t = nextOccurrence(now, advance, settings.weekdays)
            if (t != null) alarms.add(Pair(t.toInstant().toEpochMilli(), "advance"))
        }
        val t = nextOccurrence(now, target, settings.weekdays)
        if (t != null) alarms.add(Pair(t.toInstant().toEpochMilli(), "on_time"))

        // 每次重排先清空旧的，避免关闭提前提醒后残留上一次的闹钟
        cancel(context)
        if (!permitted(context)) return false
        if (alarms.isEmpty()) return false
        // 提前提醒与到点提醒落在同一毫秒时只保留一次（例如 23:00 提前 60 分钟跨天重叠）
        val distinct = alarms.distinctBy { it.first }
        for (pair in distinct) {
            try {
                manager(context).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, pair.first, operation(context, pair.second)
                )
            } catch (_: SecurityException) {
                cancel(context)
                return false
            }
        }
        return true
    }

    fun cancel(context: Context) {
        try {
            manager(context).cancel(operation(context, "advance"))
            manager(context).cancel(operation(context, "on_time"))
        } catch (_: Throwable) {
            // 取消失败不影响后续重排
        }
    }

    /** 下一个提醒时刻（供界面展示），没有则返回 null。 */
    fun next(context: Context): ZonedDateTime? {
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) return null
        val now = ZonedDateTime.now()
        val candidates = mutableListOf<ZonedDateTime>()
        settings.advanceTimeOn(now)?.let { nextOccurrence(now, it, settings.weekdays)?.let(candidates::add) }
        nextOccurrence(now, settings.timeOn(now), settings.weekdays)?.let(candidates::add)
        return candidates.minOrNull()
    }
}
