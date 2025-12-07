package com.focuslock.detox

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppBlockerService : AccessibilityService() {
    companion object {
        const val TAG = "AppBlockerService"

        // 차단할 앱 패키지 목록
        val BLOCKED_PACKAGES = listOf(
            // YouTube
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.youtube.music",
            "com.google.android.youtube.kids",

            // Instagram
            "com.instagram.android",
            "com.instagram.lite",

            // Chzzk (네이버 치지직) - 실제 패키지명
            "com.navercorp.game.android.community",
            "com.nhn.android.chzzk",
            "com.naver.chzzk",

            // League of Legends (와일드 리프트)
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildriftkr",

            // 기타 라이엇 게임
            "com.riotgames.mobile.leagueconnect",
            "com.riotgames.mobile.valorant"
        )

        @Volatile
        var isEnabled = false
            private set

        @Volatile
        var instance: AppBlockerService? = null
            private set
    }

    private var lastBlockedApp: String? = null
    private var lastBlockTime: Long = 0

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        serviceInfo = info
        isEnabled = true
        instance = this

        Log.i(TAG, "App blocker service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 잠금 상태가 아니면 무시
        if (!BlockerManager.isLocked) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return

                if (shouldBlockPackage(packageName)) {
                    blockApp(packageName)
                }
            }
        }
    }

    private fun shouldBlockPackage(packageName: String): Boolean {
        return BLOCKED_PACKAGES.any { blocked ->
            packageName.equals(blocked, ignoreCase = true) ||
            packageName.startsWith(blocked, ignoreCase = true)
        }
    }

    private fun blockApp(packageName: String) {
        val now = System.currentTimeMillis()

        // 같은 앱을 300ms 내에 다시 차단하지 않음 (무한루프 방지, 빠른 반응)
        if (packageName == lastBlockedApp && now - lastBlockTime < 300) {
            return
        }

        lastBlockedApp = packageName
        lastBlockTime = now

        Log.i(TAG, "Blocking app: $packageName")

        // 홈 화면으로 즉시 이동
        goToHome()

        // 알림 표시 (선택적)
        showBlockedNotification(packageName)
    }

    private fun goToHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    private fun showBlockedNotification(packageName: String) {
        // 앱 이름 가져오기
        val appName = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        Log.d(TAG, "Blocked app: $appName")

        // TODO: 토스트 또는 오버레이로 알림 표시
    }

    override fun onInterrupt() {
        Log.w(TAG, "App blocker service interrupted")
    }

    override fun onDestroy() {
        isEnabled = false
        instance = null
        super.onDestroy()
        Log.i(TAG, "App blocker service destroyed")
    }
}
