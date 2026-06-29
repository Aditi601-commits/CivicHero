package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

class ShakeDetectionService : Service() {
    private val TAG = "ShakeDetectionService"
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    
    private var lastUpdate: Long = 0
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var lastShakeTime: Long = 0
    
    companion object {
        const val CHANNEL_ID = "shake_detection_channel"
        const val NOTIFICATION_ID = 4099
        const val SHAKE_THRESHOLD = 500
        
        // Helper to easily start/stop the background service
        fun startService(context: Context) {
            val intent = Intent(context, ShakeDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, ShakeDetectionService::class.java)
            context.stopService(intent)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            val currentTime = System.currentTimeMillis()
            val diffTime = currentTime - lastUpdate
            if (diffTime > 100) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                if (lastUpdate != 0L) {
                    val deltaX = x - lastX
                    val deltaY = y - lastY
                    val deltaZ = z - lastZ
                    
                    // Euclidean formula for velocity/shake detection
                    val speed = Math.sqrt((deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble()).toFloat() / diffTime * 10000
                    
                    if (speed > SHAKE_THRESHOLD) {
                        val now = System.currentTimeMillis()
                        if (now - lastShakeTime > 2000) {
                            lastShakeTime = now
                            Log.d(TAG, "Background Shake Detected!")
                            triggerReportLaunch()
                        }
                    }
                }
                
                lastUpdate = currentTime
                lastX = x
                lastY = y
                lastZ = z
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // Register sensor listener
        sensorManager?.registerListener(
            sensorListener,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        
        // Start as Foreground Service immediately with a clean persistent notification and robust error-catching
        try {
            val notification = createNotification("Community Hero Shake-to-Report is active in background.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}. App remains running safely.", e)
        }
        
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        sensorManager?.unregisterListener(sensorListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shake to Report Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors phone shakes in background to quickly open report form"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("LAUNCH_QUICK_REPORT", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Community Hero")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // fallback default icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun triggerReportLaunch() {
        // 1. Try direct activity launch
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("LAUNCH_QUICK_REPORT", true)
        }
        
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Direct launch failed: ${e.message}")
            }
        }
    }
}
