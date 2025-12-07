package com.focuslock.detox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Device booted, checking lock state")

            // BlockerManager 초기화 및 상태 확인
            BlockerManager.init(context)

            if (BlockerManager.checkAndUpdateLockState()) {
                Log.i(TAG, "Lock is still active, checking VPN permission")

                // VPN 권한이 이미 있는지 확인
                val vpnPrepareIntent = VpnService.prepare(context)
                if (vpnPrepareIntent == null) {
                    // VPN 권한이 있음 - 서비스 시작
                    Log.i(TAG, "VPN permission granted, restarting VPN service")
                    val vpnIntent = Intent(context, BlockerVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                } else {
                    // VPN 권한이 없음 - 사용자가 앱을 열어야 함
                    Log.w(TAG, "VPN permission not granted, user needs to open app")
                }
            } else {
                Log.i(TAG, "No active lock")
            }
        }
    }
}
