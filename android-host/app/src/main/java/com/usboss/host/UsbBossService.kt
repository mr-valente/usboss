package com.usboss.host

import android.content.pm.ServiceInfo
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.util.Log

class UsbBossService : Service() {
    override fun onCreate() {
        super.onCreate()
        try {
            HostRuntime.initialize(this)
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
            HostRuntime.note("Foreground service created and USB receivers registered")
        } catch (error: Throwable) {
            Log.e(TAG, "Service initialization failed", error)
            HostRuntime.updateError("USBoss service init failed: ${error.message}")
            throw error
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            HostRuntime.debug("Service action received: ${intent?.action ?: "null"}")
            when (intent?.action) {
                ACTION_STOP -> {
                    HostRuntime.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                ACTION_REFRESH -> HostRuntime.refreshDevices(this)
                ACTION_REQUEST_PERMISSIONS -> HostRuntime.requestPermissions(this)
                ACTION_START, null -> {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                    HostRuntime.start(this)
                }
            }
            START_STICKY
        } catch (error: Throwable) {
            Log.e(TAG, "Service start command failed: ${intent?.action}", error)
            HostRuntime.updateError("USBoss service error: ${error.message}")
            HostRuntime.updateStatus("Service failed to start")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        HostRuntime.note("Foreground service destroyed", addToRecent = true)
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
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    HostRuntime.refreshDevices(context)
                    if (granted) {
                        HostRuntime.requestPermissions(context)
                        HostRuntime.note(
                            "USB permission granted; requesting any remaining controllers",
                            addToRecent = true,
                        )
                    } else {
                        HostRuntime.note("USB permission denied", addToRecent = true)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    HostRuntime.refreshDevices(context)
                    HostRuntime.requestPermissions(context)
                    HostRuntime.note("USB device attached", addToRecent = true)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    HostRuntime.refreshDevices(context)
                    HostRuntime.note("USB device detached", addToRecent = true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "USBoss"
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
