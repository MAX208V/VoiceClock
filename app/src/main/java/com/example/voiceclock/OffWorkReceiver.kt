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
        // 先排好下一次，再拉起播报服务。
        // 顺序很重要：服务的存活与否都不影响后续排程，
        // 也不会出现「跳过→重排→再触发」的循环。
        try {
            OffWorkScheduler.schedule(context)
        } catch (e: Throwable) {
            Log.w(TAG, "重排下班提醒失败", e)
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

    }
}
