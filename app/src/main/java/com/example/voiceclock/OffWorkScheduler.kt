package com.example.voiceclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.time.ZonedDateTime

object OffWorkScheduler {
    /** 一次最多登记几个闹钟。太多会被系统限制，够覆盖接下来几次提醒即可。 */
    private const val MAX_ALARMS = 4
    private const val PREFS = "off_work_settings"
    private const val KEY_SCHEDULED = "scheduled_keys"

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun permitted(context: Context) =
        Build.VERSION.SDK_INT < 31 || manager(context).canScheduleExactAlarms()

    /**
     * 生成 PendingIntent。
     *
     * PendingIntent 的身份由 requestCode + Intent 比对（extras 不参与比对），
     * 所以把 kind 和触发时刻都写进 data，确保每个提醒都是独立实体，
     * 否则后登记的会覆盖先登记的，多个时段就只剩一个提醒。
     */
    private fun operation(context: Context, reminder: Reminder): PendingIntent {
        val requestCode = reminder.key().hashCode() and 0x7fffffff
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, OffWorkReceiver::class.java)
                .setData(Uri.parse("voiceclock://offwork/${reminder.kind}/${reminder.at.toInstant().toEpochMilli()}"))
                .putExtra("kind", reminder.kind.name)
                .putExtra("minutes", reminder.minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context): Boolean {
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) {
            cancel(context)
            return true
        }
        val now = ZonedDateTime.now()
        val upcoming = settings.upcomingReminders(now).take(MAX_ALARMS)

        // 先清空旧的，避免关闭某个提醒后残留上一次的登记
        cancel(context)
        if (!permitted(context)) return false
        if (upcoming.isEmpty()) return false

        val scheduled = ArrayList<String>(upcoming.size)
        for (reminder in upcoming) {
            try {
                manager(context).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.at.toInstant().toEpochMilli(),
                    operation(context, reminder)
                )
                scheduled.add("${reminder.kind}|${reminder.at.toInstant().toEpochMilli()}|${reminder.minutes}")
            } catch (_: SecurityException) {
                cancel(context)
                return false
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCHEDULED, scheduled.joinToString(";")).apply()
        return true
    }

    /**
     * 撤销上一次登记的所有闹钟。
     *
     * PendingIntent 必须用与登记时完全相同的 Intent 才能撤销，
     * 所以把登记过的 key 持久化下来，撤销时逐个还原。
     */
    fun cancel(context: Context) {
        try {
            val alarmManager = manager(context) ?: return
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SCHEDULED, "") ?: ""
            if (saved.isBlank()) return
            for (token in saved.split(';')) {
                val parts = token.split('|')
                if (parts.size != 3) continue
                val kindName = parts[0]
                val millis = parts[1].toLongOrNull() ?: continue
                val minutes = parts[2].toIntOrNull() ?: 0
                val kind = runCatching { ReminderKind.valueOf(kindName) }.getOrNull() ?: continue
                val reminder = Reminder(
                    at = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()),
                    kind = kind,
                    minutes = minutes
                )
                alarmManager.cancel(operation(context, reminder))
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_SCHEDULED).apply()
        } catch (_: Throwable) {
            // 取消失败不影响后续重排
        }
    }

    /** 下一个提醒时刻（供界面展示），没有则返回 null。 */
    fun next(context: Context): ZonedDateTime? {
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) return null
        return settings.nextReminder(ZonedDateTime.now())?.at
    }
}
