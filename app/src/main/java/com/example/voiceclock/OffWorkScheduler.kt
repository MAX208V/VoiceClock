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

    fun schedule(context: Context): Boolean {
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) { cancel(context); return true }
        val now = ZonedDateTime.now()

        val alarms = mutableListOf<Pair<Long, String>>()
        settings.nextAdvance(now)?.let { alarms.add(Pair(it.toInstant().toEpochMilli(), "advance")) }
        settings.nextOnTime(now)?.let { alarms.add(Pair(it.toInstant().toEpochMilli(), "on_time")) }

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
        return listOfNotNull(settings.nextAdvance(now), settings.nextOnTime(now)).minOrNull()
    }
}
