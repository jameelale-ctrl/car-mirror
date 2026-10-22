package com.example.carmirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.core.content.IntentCompat

/**
 * Holds the screen-capture session. The phone's screen is mirrored into a
 * VirtualDisplay; the car side hands that VirtualDisplay the Android Auto
 * surface, so frames go straight to the car with no copying in app code.
 */
class MirrorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.example.carmirror.STOP"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var instance: MirrorService? = null
            private set

        /** Called on the main thread whenever capture starts or stops. */
        @Volatile
        var onStateChanged: (() -> Unit)? = null

        val isRunning: Boolean get() = instance?.virtualDisplay != null

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var attachedSurface: Surface? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be in the foreground with type mediaProjection BEFORE getMediaProjection().
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        releaseCapture()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+: the callback must be registered before createVirtualDisplay.
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        val size = phoneScreenSize()
        // Android 14+ allows only ONE createVirtualDisplay per projection, so we
        // create it now with no surface and later swap surfaces/resize it.
        virtualDisplay = p.createVirtualDisplay(
            "CarMirror",
            size.x, size.y, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            null, null, handler
        )
        projection = p
        instance = this
        notifyChanged()
        return START_NOT_STICKY
    }

    /**
     * Point the mirror at the car's surface (or detach with null).
     * AUTO_MIRROR scales the phone image to fit, keeping its aspect ratio,
     * so a portrait phone shows centred with black bars on the car screen.
     */
    fun attachCarSurface(surface: Surface?, width: Int, height: Int, dpi: Int) {
        val vd = virtualDisplay ?: return
        if (surface == null) {
            if (attachedSurface != null) {
                vd.surface = null
                attachedSurface = null
            }
            return
        }
        if (surface === attachedSurface) return
        vd.resize(width, height, dpi)
        vd.surface = surface
        attachedSurface = surface
    }

    /** The phone's current full-screen size in pixels (rotation-aware). */
    fun phoneScreenSize(): Point {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(p)
        return p
    }

    override fun onDestroy() {
        releaseCapture()
        instance = null
        notifyChanged()
        super.onDestroy()
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        attachedSurface = null
        projection?.stop()
        projection = null
    }

    private fun notifyChanged() {
        handler.post { onStateChanged?.invoke() }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Car Mirror is capturing your screen")
            .setContentText("Shown on the car display only while parked")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
