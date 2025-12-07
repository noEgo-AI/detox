package com.focuslock.detox

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Date

object BlockerManager {
    private const val TAG = "BlockerManager"
    private const val PREFS_NAME = "focuslock_prefs"
    private const val KEY_UNLOCK_TIME = "unlock_time"
    private const val KEY_IS_LOCKED = "is_locked"

    const val VPN_REQUEST_CODE = 100
    const val ACCESSIBILITY_REQUEST_CODE = 101

    @Volatile
    var isLocked: Boolean = false
        private set

    var unlockTime: Long = 0
        private set

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
        checkAndUpdateLockState()
    }

    private fun loadState() {
        isLocked = prefs.getBoolean(KEY_IS_LOCKED, false)
        unlockTime = prefs.getLong(KEY_UNLOCK_TIME, 0)
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, isLocked)
            .putLong(KEY_UNLOCK_TIME, unlockTime)
            .apply()
    }

    fun checkAndUpdateLockState(): Boolean {
        if (isLocked && System.currentTimeMillis() >= unlockTime) {
            // 잠금 시간이 만료됨
            unlock()
            return false
        }
        return isLocked
    }

    fun getRemainingSeconds(): Long {
        if (!isLocked) return 0
        val remaining = (unlockTime - System.currentTimeMillis()) / 1000
        return if (remaining > 0) remaining else 0
    }

    fun startLock(context: Context, durationMinutes: Int): Boolean {
        if (isLocked) {
            Log.w(TAG, "Already locked")
            return false
        }

        unlockTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        isLocked = true
        saveState()

        // VPN 서비스 시작 (DNS 필터링)
        startVpnService(context)

        Log.i(TAG, "Lock started. Unlock at: ${Date(unlockTime)}")
        return true
    }

    private fun startVpnService(context: Context) {
        val intent = Intent(context, BlockerVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopVpnService(context: Context) {
        val intent = Intent(context, BlockerVpnService::class.java).apply {
            action = "STOP"
        }
        context.startService(intent)
    }

    fun unlock() {
        isLocked = false
        unlockTime = 0
        saveState()
        Log.i(TAG, "Unlocked")
    }

    // VPN 권한 확인
    fun prepareVpn(activity: Activity): Boolean {
        val intent = VpnService.prepare(activity)
        return if (intent != null) {
            activity.startActivityForResult(intent, VPN_REQUEST_CODE)
            false
        } else {
            true
        }
    }

    // Accessibility 서비스 활성화 확인
    fun isAccessibilityEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/${AppBlockerService::class.java.canonicalName}"

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
    }

    // Accessibility 설정 화면으로 이동
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // 모든 권한이 준비되었는지 확인
    fun arePermissionsReady(context: Context): Boolean {
        val vpnReady = VpnService.prepare(context) == null
        val accessibilityReady = isAccessibilityEnabled(context)
        return vpnReady && accessibilityReady
    }

    // 차단 시작 (모든 권한이 있을 때)
    fun startBlocking(context: Context, durationMinutes: Int): Boolean {
        if (!arePermissionsReady(context)) {
            Log.w(TAG, "Permissions not ready")
            return false
        }

        return startLock(context, durationMinutes)
    }

    // 차단 중지 (시간 만료 시 자동 호출)
    fun stopBlocking(context: Context) {
        stopVpnService(context)
        unlock()
    }
}
