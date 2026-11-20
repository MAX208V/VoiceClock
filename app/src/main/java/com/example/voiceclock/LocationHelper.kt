package com.example.voiceclock

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 前台粗定位（ACCESS_COARSE_LOCATION）。
 *
 * 策略：先取系统最近一次已知位置（GPS / 网络），如果为空则请求单次定位更新；
 * 超时后回调失败。只在前台调用，不申请后台定位权限。
 */
object LocationHelper {

    data class Point(val latitude: Double, val longitude: Double)

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * 请求一次定位。callback 始终在主线程回调，timeoutMillis 超时后以 null 回调。
     */
    fun request(context: Context, timeoutMillis: Long = 8000L, callback: (Point?) -> Unit) {
        if (!hasPermission(context)) {
            callback(null)
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            callback(null)
            return
        }
        val main = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(point: Point?) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            callback(point)
        }

        // 1) 先看历史缓存位置
        val cached = lastKnown(manager)
        if (cached != null) {
            finish(Point(cached.latitude, cached.longitude))
            return
        }

        // 2) 请求一次更新
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            finish(null)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try { manager.removeUpdates(this) } catch (_: Exception) { }
                finish(Point(location.latitude, location.longitude))
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("已废弃但需实现", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        try {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        } catch (_: SecurityException) {
            finish(null)
            return
        } catch (_: Exception) {
            finish(null)
            return
        }

        main.postDelayed({ finish(null) }, timeoutMillis)
    }

    private fun lastKnown(manager: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (provider in providers) {
            val location = try {
                if (!manager.isProviderEnabled(provider)) null else manager.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
            if (location != null && (best == null || location.time > best.time)) best = location
        }
        return best
    }
}
