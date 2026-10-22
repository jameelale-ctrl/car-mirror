package com.example.carphotos.car

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.carphotos.PhotoStore
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-screen photo slideshow drawn on the Android Auto surface.
 * Photos are shown only when the car reports it has been stopped for 2 s;
 * moving or unknown speed → a plain "paused" message instead.
 */
class PhotoScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private enum class Motion { UNKNOWN, MOVING, STOPPED }

    companion object {
        private const val STOPPED_SPEED_MPS = 0.3f
        private const val PARKED_CONFIRM_MS = 2_000L
        private const val SPEED_TIMEOUT_MS = 4_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(carContext)
    private val decoder = Executors.newSingleThreadExecutor()

    private var container: SurfaceContainer? = null
    private var visibleArea: Rect? = null

    private var photos: List<File> = emptyList()
    private var index = 0
    private var playing = true
    private var bitmap: Bitmap? = null
    private var bitmapFile: File? = null
    private var loadToken = 0

    private var motion = Motion.UNKNOWN
    private var stoppedSince = 0L
    private var lastSpeedAt = 0L

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val onPhotosChanged: () -> Unit = { reloadList() }
    private val speedListener = OnCarDataAvailableListener<Speed> { onSpeed(it) }

    private val advance = object : Runnable {
        override fun run() {
            if (playing && isParked() && photos.size > 1) step(+1)
            scheduleAdvance()
        }
    }

    private val speedWatchdog = object : Runnable {
        override fun run() {
            if (motion != Motion.UNKNOWN && SystemClock.elapsedRealtime() - lastSpeedAt > SPEED_TIMEOUT_MS) {
                motion = Motion.UNKNOWN
                render()
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                PhotoStore.addListener(onPhotosChanged)
                reloadList()
                startSpeedUpdates()
                handler.post(speedWatchdog)
                scheduleAdvance()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                PhotoStore.removeListener(onPhotosChanged)
                stopSpeedUpdates()
                decoder.shutdownNow()
            }
        })
    }

    // ---- Template: ◀  ⏯  ▶ ------------------------------------------------

    override fun onGetTemplate(): Template {
        // Icon-only: navigation action strips allow at most one titled action.
        @Suppress("UNUSED_PARAMETER")
        fun action(icon: Int, title: String, onClick: () -> Unit) = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { onClick() }
            .build()

        val strip = ActionStrip.Builder()
            .addAction(action(android.R.drawable.ic_media_previous, "Prev") { step(-1) })
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause") { togglePlay() }
                else action(android.R.drawable.ic_media_play, "Play") { togglePlay() }
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next") { step(+1) })
            .build()
        return NavigationTemplate.Builder().setActionStrip(strip).build()
    }

    // ---- Slideshow -----------------------------------------------------------

    private fun reloadList() {
        val current = photos.getOrNull(index)
        photos = PhotoStore.list(carContext)
        index = current?.let { photos.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        bitmapFile = null
        loadCurrent()
        scheduleAdvance()
    }

    private fun step(delta: Int) {
        if (photos.isEmpty()) return
        index = (index + delta).mod(photos.size)
        loadCurrent()
        scheduleAdvance()
    }

    private fun togglePlay() {
        playing = !playing
        invalidate()
        scheduleAdvance()
    }

    private fun scheduleAdvance() {
        handler.removeCallbacks(advance)
        handler.postDelayed(advance, PhotoStore.intervalSeconds(carContext) * 1_000L)
    }

    private fun loadCurrent() {
        val file = photos.getOrNull(index)
        if (file == null) {
            bitmap = null
            render()
            return
        }
        if (file == bitmapFile && bitmap != null) {
            render()
            return
        }
        val c = container
        val w = c?.width ?: 1280
        val h = c?.height ?: 720
        val token = ++loadToken
        try {
            decoder.execute {
                val bmp = PhotoStore.load(file, w, h)
                handler.post {
                    if (token == loadToken) {
                        bitmap = bmp
                        bitmapFile = file
                        render()
                    }
                }
            }
        } catch (_: Exception) {
            // Executor shut down: screen is going away.
        }
    }

    // ---- Drawing -------------------------------------------------------------

    private fun render() {
        val c = container ?: return
        val surface = c.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (_: Exception) {
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
            val message = when {
                motion == Motion.MOVING -> "Photos are hidden while the car is moving."
                motion == Motion.UNKNOWN -> "Waiting for the car to report its speed…"
                !isParked() -> "Car stopped. Photos will appear in a moment…"
                photos.isEmpty() -> "No photos yet.\nOpen Car Photos on your phone and tap Add photos."
                else -> null
            }
            val bmp = bitmap
            if (message != null) {
                drawMessage(canvas, area, message, c.dpi)
            } else if (bmp != null) {
                val scale = min(area.width().toFloat() / bmp.width, area.height().toFloat() / bmp.height)
                val dw = bmp.width * scale
                val dh = bmp.height * scale
                val left = area.left + (area.width() - dw) / 2f
                val top = area.top + (area.height() - dh) / 2f
                canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), bitmapPaint)
                if (photos.size > 1) {
                    textPaint.textSize = 14f * c.dpi / 160f
                    textPaint.alpha = 170
                    canvas.drawText("${index + 1} / ${photos.size}", area.left + 16f, area.bottom - 16f, textPaint)
                    textPaint.alpha = 255
                }
            }
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawMessage(canvas: android.graphics.Canvas, area: Rect, text: String, dpi: Int) {
        textPaint.textSize = 22f * dpi / 160f
        val width = (area.width() * 0.8f).toInt().coerceAtLeast(100)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate(area.left + (area.width() - width) / 2f, area.top + (area.height() - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- SurfaceCallback -------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        bitmapFile = null // re-decode at the car screen's size
        loadCurrent()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = Rect(area)
        render()
    }

    override fun onClick(x: Float, y: Float) = togglePlay()

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (abs(velocityX) > abs(velocityY)) step(if (velocityX < 0) +1 else -1)
    }

    // ---- Vehicle speed ---------------------------------------------------------

    private fun startSpeedUpdates() {
        try {
            carContext.getCarService(CarHardwareManager::class.java)
                .carInfo.addSpeedListener(executor, speedListener)
        } catch (_: Exception) {
            motion = Motion.UNKNOWN
        }
    }

    private fun stopSpeedUpdates() {
        try {
            carContext.getCarService(CarHardwareManager::class.java)
                .carInfo.removeSpeedListener(speedListener)
        } catch (_: Exception) {
        }
    }

    private fun onSpeed(speed: Speed) {
        val v = speed.rawSpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
            ?: speed.displaySpeedMetersPerSecond.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value
        val now = SystemClock.elapsedRealtime()
        val before = isParked() to motion
        if (v == null) {
            motion = Motion.UNKNOWN
        } else {
            lastSpeedAt = now
            if (abs(v) > STOPPED_SPEED_MPS) {
                motion = Motion.MOVING
                stoppedSince = 0L
            } else {
                if (stoppedSince == 0L) {
                    stoppedSince = now
                    handler.postDelayed({ render() }, PARKED_CONFIRM_MS + 50)
                }
                motion = Motion.STOPPED
            }
        }
        if ((isParked() to motion) != before) render()
    }

    private fun isParked(): Boolean =
        motion == Motion.STOPPED && stoppedSince != 0L &&
            SystemClock.elapsedRealtime() - stoppedSince >= PARKED_CONFIRM_MS
}
