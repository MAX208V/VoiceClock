package com.example.voiceclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZonedDateTime

class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        if (!relevant) return

        // 时间跳变后以当前时刻为新锚点，避免排程错乱
        val now = ZonedDateTime.now()
        if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_BOOT_COMPLETED) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("anchor", now.toInstant().toEpochMilli()).apply()
        }

        // 报时重排（未开启报时时无需处理）
        try {
            if (Settings.enabled(context) && ClockAlarms.permitted(context)) {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val saved = prefs.getLong("anchor", now.toInstant().toEpochMilli())
                val anchor = Instant.ofEpochMilli(saved).atZone(now.zone)
                ClockAlarms.schedule(context, Settings.load(context).next(now, anchor))
            }
        } catch (_: Throwable) {
            // 报时重排失败不影响其它逻辑
        }
        // 下班提醒有自己的接收器处理开机；这里只处理时间跳变，避免重复登记
        if (action != Intent.ACTION_BOOT_COMPLETED) OffWorkScheduler.schedule(context)
    }
}
