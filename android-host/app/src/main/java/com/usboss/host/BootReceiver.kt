package com.usboss.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        HostRuntime.initialize(context)
        if (!HostRuntime.shouldStartOnBoot(context)) {
            HostRuntime.debug("Ignoring boot/package broadcast because start-on-boot is disabled")
            return
        }

        HostRuntime.note("Auto-starting USBoss after ${action.substringAfterLast('.')}", addToRecent = true)
        ContextCompat.startForegroundService(
            context,
            UsbBossService.intent(context, UsbBossService.ACTION_START),
        )
    }
}
