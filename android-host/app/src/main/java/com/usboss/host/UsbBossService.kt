package com.usboss.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class UsbBossService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(HostRuntime.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                HostRuntime.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_REFRESH -> HostRuntime.refreshDevices(this)
            ACTION_REQUEST_PERMISSIONS -> HostRuntime.requestPermissions(this)
            ACTION_START, null -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                HostRuntime.start(this)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        HostRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                HostRuntime.ACTION_USB_PERMISSION -> {
                    HostRuntime.refreshDevices(context)
                    HostRuntime.updateStatus("USB permission updated")
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    HostRuntime.refreshDevices(context)
                    HostRuntime.requestPermissions(context)
                    HostRuntime.updateStatus("USB device attached")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    HostRuntime.refreshDevices(context)
                    HostRuntime.updateStatus("USB device detached")
                }
            }
        }
    }

    companion object {
        const val ACTION_START = "com.usboss.host.START"
        const val ACTION_STOP = "com.usboss.host.STOP"
        const val ACTION_REFRESH = "com.usboss.host.REFRESH"
        const val ACTION_REQUEST_PERMISSIONS = "com.usboss.host.REQUEST_PERMISSIONS"

        private const val CHANNEL_ID = "usboss-host"
        private const val NOTIFICATION_ID = 1001

        fun intent(context: Context, action: String): Intent {
            return Intent(context, UsbBossService::class.java).setAction(action)
        }
    }
}
