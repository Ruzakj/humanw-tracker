package com.ric.humanwmaps.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ric.humanwmaps.MainActivity
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant

class TrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var writer: BufferedWriter? = null
    private var points = 0
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (writer != null) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        startedAt = System.currentTimeMillis()
        points = 0

        val tracksDir = File(filesDir, "tracks").apply { mkdirs() }
        val trackFile = File(tracksDir, "track_$startedAt.csv")
        writer = BufferedWriter(FileWriter(trackFile, true)).also {
            it.write("timestamp,latitude,longitude,altitude,speed_mps,bearing,accuracy\n")
            it.flush()
        }

        val notification = buildNotification("Waiting for GPS…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                locationManager.requestLocationUpdates(provider, 1000L, 1f, this)
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val row = listOf(
            location.time,
            location.latitude,
            location.longitude,
            if (location.hasAltitude()) location.altitude else "",
            if (location.hasSpeed()) location.speed else "",
            if (location.hasBearing()) location.bearing else "",
            location.accuracy
        ).joinToString(",")

        runCatching {
            writer?.apply {
                write(row)
                newLine()
                flush()
            }
            points++
        }

        val text = "${points} pts • ±${"%.0f".format(location.accuracy)} m • ${"%.1f".format(location.speed * 3.6f)} km/h"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopTracking() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { writer?.close() }
        writer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("HumanW Maps • Recording")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "STOP", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent location recording status"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.ric.humanwmaps.action.START_TRACKING"
        const val ACTION_STOP = "com.ric.humanwmaps.action.STOP_TRACKING"
        private const val CHANNEL_ID = "humanw_tracking"
        private const val NOTIFICATION_ID = 2001
    }
}
