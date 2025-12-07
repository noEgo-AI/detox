package com.focuslock.detox

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.webkit.WebView
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class StartBlockingArgs {
    var durationMinutes: Int = 0
}

@TauriPlugin
class BlockerPlugin(private val activity: Activity) : Plugin(activity) {

    companion object {
        private const val TAG = "BlockerPlugin"
        const val VPN_REQUEST_CODE = 100
    }

    private var pendingInvoke: Invoke? = null

    override fun load(webView: WebView) {
        super.load(webView)
        // Initialize BlockerManager
        BlockerManager.init(activity)

        // Check and resume lock state
        if (BlockerManager.checkAndUpdateLockState()) {
            startVpnServiceIfNeeded()
        }
    }

    @Command
    fun checkBlockerPermissions(invoke: Invoke) {
        val vpnReady = VpnService.prepare(activity) == null
        val accessibilityReady = BlockerManager.isAccessibilityEnabled(activity)

        val ret = JSObject()
        ret.put("vpn", vpnReady)
        ret.put("accessibility", accessibilityReady)
        ret.put("allReady", vpnReady && accessibilityReady)
        invoke.resolve(ret)
    }

    @Command
    fun requestVpnPermission(invoke: Invoke) {
        val vpnIntent = VpnService.prepare(activity)
        if (vpnIntent != null) {
            pendingInvoke = invoke
            activity.startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            // Already have permission
            val ret = JSObject()
            ret.put("granted", true)
            invoke.resolve(ret)
        }
    }

    @Command
    fun openAccessibilitySettings(invoke: Invoke) {
        BlockerManager.openAccessibilitySettings(activity)
        invoke.resolve()
    }

    @Command
    fun startBlocking(invoke: Invoke) {
        val args = invoke.parseArgs(StartBlockingArgs::class.java)
        val durationMinutes = args.durationMinutes

        android.util.Log.i(TAG, "startBlocking called with duration: $durationMinutes minutes")

        // Check VPN permission
        val vpnIntent = VpnService.prepare(activity)
        if (vpnIntent != null) {
            android.util.Log.w(TAG, "VPN permission not granted")
            val ret = JSObject()
            ret.put("success", false)
            ret.put("error", "VPN_PERMISSION_REQUIRED")
            invoke.resolve(ret)
            return
        }

        android.util.Log.i(TAG, "VPN permission OK")

        // Check accessibility permission
        if (!BlockerManager.isAccessibilityEnabled(activity)) {
            android.util.Log.w(TAG, "Accessibility permission not granted")
            val ret = JSObject()
            ret.put("success", false)
            ret.put("error", "ACCESSIBILITY_PERMISSION_REQUIRED")
            invoke.resolve(ret)
            return
        }

        android.util.Log.i(TAG, "Accessibility permission OK")

        // Start blocking
        val success = BlockerManager.startBlocking(activity, durationMinutes)
        android.util.Log.i(TAG, "BlockerManager.startBlocking result: $success")

        if (success) {
            android.util.Log.i(TAG, "Starting VPN service...")
            startVpnServiceIfNeeded()
        }

        val ret = JSObject()
        ret.put("success", success)
        if (!success) {
            ret.put("error", "START_FAILED")
        }
        invoke.resolve(ret)
    }

    private fun startVpnServiceIfNeeded() {
        android.util.Log.i(TAG, "startVpnServiceIfNeeded: isLocked=${BlockerManager.isLocked}")
        if (BlockerManager.isLocked) {
            val intent = Intent(activity, BlockerVpnService::class.java)
            android.util.Log.i(TAG, "Starting BlockerVpnService...")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
                android.util.Log.i(TAG, "VPN service start command sent successfully")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to start VPN service", e)
            }
        } else {
            android.util.Log.w(TAG, "Not starting VPN service - not locked")
        }
    }

    // VPN 권한 결과 처리
    fun handleActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode == VPN_REQUEST_CODE) {
            val ret = JSObject()
            ret.put("granted", resultCode == Activity.RESULT_OK)
            pendingInvoke?.resolve(ret)
            pendingInvoke = null
        }
    }

    @Command
    fun getLockState(invoke: Invoke) {
        BlockerManager.checkAndUpdateLockState()

        val ret = JSObject()
        ret.put("isLocked", BlockerManager.isLocked)
        ret.put("remainingSeconds", BlockerManager.getRemainingSeconds())
        if (BlockerManager.isLocked) {
            ret.put("unlockTime", BlockerManager.unlockTime)
        }
        invoke.resolve(ret)
    }

    @Command
    fun stopBlocking(invoke: Invoke) {
        BlockerManager.stopBlocking(activity)
        invoke.resolve()
    }

    @Command
    fun getBlockedApps(invoke: Invoke) {
        val ret = JSObject()
        val apps = org.json.JSONArray(AppBlockerService.BLOCKED_PACKAGES)
        ret.put("apps", apps)
        invoke.resolve(ret)
    }
}
