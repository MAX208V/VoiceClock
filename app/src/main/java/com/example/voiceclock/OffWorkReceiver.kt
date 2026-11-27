package com.example.voiceclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class OffWorkReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "OffWorkReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        // 开机会广播到本接收器：只重排闹钟，不播报
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                OffWorkScheduler.schedule(context)
            } catch (e: Throwable) {
                Log.e(TAG, "开机重排下班提醒失败", e)
            }
            return
        }
        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) return
        val type = intent.getStringExtra("type") ?: "on_time"
        if (type == "reschedule") {
            // 兜底重排：服务已结束时不会再排下一次，这里补上
            try {
                OffWorkScheduler.schedule(context)
            } catch (e: Throwable) {
                Log.w(TAG, "兜底重排失败", e)
            }
            return
        }
        val serviceIntent = Intent(context, OffWorkService::class.java).apply {
            action = "SPEAK"
            putExtra("type", type)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Throwable) {
            // 部分 ROM 在后台不允许起前台服务，退一步用普通服务
            Log.w(TAG, "startForegroundService 失败，尝试 startService", e)
            try {
                context.startService(serviceIntent)
            } catch (again: Throwable) {
                Log.e(TAG, "启动 OffWorkService 失败", again)
            }
        }

        // 不在这里重排：schedule() 会先 cancel 再登记，可能把另一个
        // 正要触发的闹钟一起取消。下一次排程由 OffWorkService.onDestroy() 处理。
        // 兜底：若服务根本起不来（被系统拦截），30 秒后补一次重排。
        try {
            val retry = Intent(context, OffWorkReceiver::class.java)
                .setData(android.net.Uri.parse("voiceclock://offwork/reschedule"))
                .putExtra("type", "reschedule")
            val manager = context.getSystemService(android.app.AlarmManager::class.java)
            manager?.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 30_000L,
                android.app.PendingIntent.getBroadcast(
                    context, 5, retry,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } catch (e: Throwable) {
            Log.w(TAG, "登记兜底重排失败", e)
        }
    }
}
