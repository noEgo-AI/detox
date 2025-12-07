package com.focuslock.detox

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import org.json.JSONObject

class MainActivity : TauriActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // BlockerManager 초기화
        BlockerManager.init(this)

        // 잠금 상태 확인 및 복구
        if (BlockerManager.checkAndUpdateLockState()) {
            // VPN 서비스 재시작
            startVpnServiceIfNeeded()
        }
    }

    private fun startVpnServiceIfNeeded() {
        if (BlockerManager.isLocked) {
            val intent = Intent(this, BlockerVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            BlockerManager.VPN_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    // VPN 권한 승인됨
                    notifyPermissionResult("vpn", true)
                } else {
                    notifyPermissionResult("vpn", false)
                }
            }
        }
    }

    private fun notifyPermissionResult(type: String, granted: Boolean) {
        webView?.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('focuslock-permission', { detail: { type: '$type', granted: $granted } }))",
            null
        )
    }

    override fun onResume() {
        super.onResume()

        // WebView에 JavaScript 인터페이스 추가
        setupJavaScriptInterface()

        // 잠금 상태 업데이트
        BlockerManager.checkAndUpdateLockState()
    }

    private fun setupJavaScriptInterface() {
        // Tauri의 WebView 찾기
        try {
            val webViewField = this::class.java.superclass?.getDeclaredField("webView")
            webViewField?.isAccessible = true
            webView = webViewField?.get(this) as? WebView

            webView?.addJavascriptInterface(FocusLockBridge(), "FocusLockAndroid")
        } catch (e: Exception) {
            // WebView를 직접 찾지 못하면 View 계층에서 찾기
            findWebViewInHierarchy(window.decorView)?.let { wv ->
                webView = wv
                wv.addJavascriptInterface(FocusLockBridge(), "FocusLockAndroid")
            }
        }
    }

    private fun findWebViewInHierarchy(view: android.view.View): WebView? {
        if (view is WebView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebViewInHierarchy(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /**
     * JavaScript에서 호출 가능한 인터페이스
     */
    inner class FocusLockBridge {

        @JavascriptInterface
        fun isAndroid(): Boolean = true

        @JavascriptInterface
        fun startLock(durationMinutes: Int): String {
            return try {
                // VPN 권한 확인
                val vpnIntent = VpnService.prepare(this@MainActivity)
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, BlockerManager.VPN_REQUEST_CODE)
                    JSONObject().apply {
                        put("success", false)
                        put("error", "VPN_PERMISSION_REQUIRED")
                    }.toString()
                } else if (!BlockerManager.isAccessibilityEnabled(this@MainActivity)) {
                    // Accessibility 권한 필요
                    JSONObject().apply {
                        put("success", false)
                        put("error", "ACCESSIBILITY_PERMISSION_REQUIRED")
                    }.toString()
                } else {
                    // 잠금 시작
                    val success = BlockerManager.startBlocking(this@MainActivity, durationMinutes)
                    JSONObject().apply {
                        put("success", success)
                        if (!success) put("error", "START_FAILED")
                    }.toString()
                }
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message)
                }.toString()
            }
        }

        @JavascriptInterface
        fun getLockState(): String {
            BlockerManager.checkAndUpdateLockState()

            return JSONObject().apply {
                put("is_locked", BlockerManager.isLocked)
                put("remaining_seconds", BlockerManager.getRemainingSeconds())
                put("unlock_time", if (BlockerManager.isLocked) BlockerManager.unlockTime else null)
            }.toString()
        }

        @JavascriptInterface
        fun checkPermissions(): String {
            val vpnReady = VpnService.prepare(this@MainActivity) == null
            val accessibilityReady = BlockerManager.isAccessibilityEnabled(this@MainActivity)

            return JSONObject().apply {
                put("vpn", vpnReady)
                put("accessibility", accessibilityReady)
                put("all_ready", vpnReady && accessibilityReady)
            }.toString()
        }

        @JavascriptInterface
        fun requestVpnPermission() {
            val intent = VpnService.prepare(this@MainActivity)
            if (intent != null) {
                startActivityForResult(intent, BlockerManager.VPN_REQUEST_CODE)
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            BlockerManager.openAccessibilitySettings(this@MainActivity)
        }

        @JavascriptInterface
        fun getBlockedApps(): String {
            return JSONObject().apply {
                put("apps", org.json.JSONArray(AppBlockerService.BLOCKED_PACKAGES))
            }.toString()
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
