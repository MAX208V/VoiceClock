package com.example.voiceclock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

class OffWorkReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "OffWorkReceiver"
        /** 兜底重排的动作 data，与普通提醒区分开。 */
        private const val RETRY_DATA = "voiceclock://offwork/reschedule"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 开机会广播到本接收器：只重排，不播报
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                OffWorkScheduler.schedule(context)
            } catch (e: Throwable) {
                Log.e(TAG, "开机重排下班提醒失败", e)
            }
            return
        }

        // 兜底重排：服务没起来时补一次排程
        if (intent.dataString == RETRY_DATA) {
            try {
                OffWorkScheduler.schedule(context)
            } catch (e: Throwable) {
                Log.w(TAG, "兜底重排失败", e)
            }
            return
        }

        val settings = OffWorkSettingsStorage.load(context)
        if (!settings.enabled) return

        val kindName = intent.getStringExtra("kind")
        val kind = runCatching { ReminderKind.valueOf(kindName ?: "") }.getOrNull()
        if (kind == null) {
            Log.w(TAG, "收到未知提醒类型：$kindName")
            return
        }
        val minutes = intent.getIntExtra("minutes", 0)

        // 先重排，再播报。顺序很重要：播报服务是否存活都不影响后续排程。
        try {
            OffWorkScheduler.schedule(context)
        } catch (e: Throwable) {
            Log.w(TAG, "重排下班提醒失败", e)
        }

        val serviceIntent = Intent(context, OffWorkService::class.java).apply {
            action = "SPEAK"
            putExtra("kind", kind.name)
            putExtra("minutes", minutes)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                scheduleRetry(context)
            }
        }
    }

    /** 服务起不来时的兜底：30 秒后再补一次排程。 */
    private fun scheduleRetry(context: Context) {
        try {
            val manager = context.getSystemService(AlarmManager::class.java) ?: return
            val pending = PendingIntent.getBroadcast(
                context,
                9001,
                Intent(context, OffWorkReceiver::class.java).setData(Uri.parse(RETRY_DATA)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.set(AlarmManager.RTC, System.currentTimeMillis() + 30_000L, pending)
        } catch (e: Throwable) {
            Log.w(TAG, "登记兜底重排失败", e)
        }
    }
}
