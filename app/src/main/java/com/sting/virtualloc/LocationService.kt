package com.sting.virtualloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that continuously reports a mock location.
 * This ensures the virtual location persists even when the app is in background.
 */
class LocationService : Service() {

    private lateinit var mockLocationManager: MockLocationManager
    private var currentLat = 0.0
    private var currentLng = 0.0
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                mockLocationManager.updateLocation(currentLat, currentLng)
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mockLocationManager = MockLocationManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentLat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                currentLng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                startForeground(NOTIFICATION_ID, createNotification())
                beginLocationUpdates()
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    private fun beginLocationUpdates() {
        val ok = mockLocationManager.startMocking(currentLat, currentLng)
        if (ok) {
            isRunning = true
            handler.post(updateRunnable)
        }
    }

    private fun stopLocationUpdates() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        mockLocationManager.stopMocking()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Virtual Location",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "VirtuaLoc is running in the background"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, LocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VirtuaLoc 运行中")
            .setContentText("虚拟定位: %.6f, %.6f".format(currentLat, currentLng))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "virtualloc_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.sting.virtualloc.START"
        const val ACTION_STOP = "com.sting.virtualloc.STOP"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private const val UPDATE_INTERVAL_MS = 2000L

        fun start(context: Context, lat: Double, lng: Double) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LNG, lng)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
