package com.example.carmirror.car

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.example.carmirror.MirrorService
import com.example.carmirror.TouchService
import kotlin.math.abs
import kotlin.math.min

/**
 * The car-side screen. As a navigation app it gets the full-screen car
 * surface, which is handed to MirrorService's VirtualDisplay while parked.
 *
 * Safety rule: the mirror is shown only when the car reports a speed of
 * ~0 for PARKED_CONFIRM_MS. Moving, or speed not reported → paused.
 */
class MirrorScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private enum class Motion { UNKNOWN, MOVING, STOPPED }

    companion object {
        private const val STOPPED_SPEED_MPS = 0.3f      // ~1 km/h
        private const val PARKED_CONFIRM_MS = 2_000L
        private const val SPEED_TIMEOUT_MS = 4_000L
        private const val SCROLL_FLUSH_MS = 120L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = ContextCompat.getMainExecutor(carContext)

    private var surfaceContainer: SurfaceContainer? = null
    private var motion = Motion.UNKNOWN
    private var stoppedSince = 0L
    private var lastSpeedAt = 0L
    private var paused: PausedScreen? = null

    // Scroll gestures arrive as many small deltas; batch them into one swipe.
    private var scrollDx = 0f
    private var scrollDy = 0f
    private val flushScroll = Runnable { dispatchSwipe(scrollDx, scrollDy, 250L); scrollDx = 0f; scrollDy = 0f }

    private val speedListener = OnCarDataAvailableListener<Speed> { speed -> onSpeed(speed) }

    private val speedWatchdog = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() - lastSpeedAt > SPEED_TIMEOUT_MS && motion != Motion.UNKNOWN) {
                motion = Motion.UNKNOWN
                update()
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                MirrorService.onStateChanged = { update() }
                startSpeedUpdates()
                handler.post(speedWatchdog)
                // Defer: don't push the paused screen while the root is still being created.
                handler.post { update() }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                handler.removeCallbacksAndMessages(null)
                stopSpeedUpdates()
                MirrorService.onStateChanged = null
                MirrorService.instance?.attachCarSurface(null, 0, 0, 0)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val stop = Action.Builder()
            .setTitle("Stop")
            .setIcon(
                CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_media_pause)
                ).build()
            )
            .setOnClickListener { MirrorService.stop(carContext) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(stop).build())
            .build()
    }

    // ---- Vehicle speed ------------------------------------------------------

    private fun startSpeedUpdates() {
        try {
            carContext.getCarService(CarHardwareManager::class.java)
                .carInfo.addSpeedListener(executor, speedListener)
        } catch (e: Exception) {
            // Host doesn't provide speed (or permission denied): stays UNKNOWN = paused.
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
        val value = pick(speed.rawSpeedMetersPerSecond) ?: pick(speed.displaySpeedMetersPerSecond)
        val now = SystemClock.elapsedRealtime()
        if (value == null) {
            motion = Motion.UNKNOWN
        } else {
            lastSpeedAt = now
            if (abs(value) > STOPPED_SPEED_MPS) {
                motion = Motion.MOVING
                stoppedSince = 0L
            } else {
                if (stoppedSince == 0L) stoppedSince = now
                motion = Motion.STOPPED
                if (now - stoppedSince < PARKED_CONFIRM_MS) {
                    // Re-check once the confirm window has passed.
                    handler.postDelayed({ update() }, PARKED_CONFIRM_MS)
                }
            }
        }
        update()
    }

    private fun pick(v: CarValue<Float>): Float? =
        if (v.status == CarValue.STATUS_SUCCESS) v.value else null

    private fun isParked(): Boolean =
        motion == Motion.STOPPED &&
            stoppedSince != 0L &&
            SystemClock.elapsedRealtime() - stoppedSince >= PARKED_CONFIRM_MS

    // ---- Show / hide the mirror --------------------------------------------

    private fun update() {
        val service = MirrorService.instance
        val reason = when {
            service == null || !MirrorService.isRunning ->
                "Open Car Mirror on your phone and tap Start mirroring."
            motion == Motion.MOVING ->
                "Mirroring is paused while the car is moving."
            motion == Motion.UNKNOWN ->
                "Waiting for the car to report its speed. Mirroring stays off until the car is confirmed stopped."
            !isParked() ->
                "Car stopped. Mirroring will resume in a moment…"
            else -> null
        }

        if (reason == null) {
            val c = surfaceContainer
            if (c?.surface != null) {
                service!!.attachCarSurface(c.surface, c.width, c.height, c.dpi)
            }
            if (paused != null) {
                paused = null
                screenManager.popToRoot()
            }
        } else {
            service?.attachCarSurface(null, 0, 0, 0)
            val p = paused
            if (p == null) {
                paused = PausedScreen(carContext, reason).also { screenManager.push(it) }
            } else {
                p.message = reason
            }
        }
    }

    // ---- SurfaceCallback ---------------------------------------------------

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surfaceContainer = container
        update()
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surfaceContainer = null
        MirrorService.instance?.attachCarSurface(null, 0, 0, 0)
    }

    override fun onClick(x: Float, y: Float) {
        if (paused != null) return
        val (px, py) = toPhone(x, y) ?: return
        TouchService.instance?.tap(px, py)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (paused != null) return
        scrollDx += distanceX
        scrollDy += distanceY
        handler.removeCallbacks(flushScroll)
        handler.postDelayed(flushScroll, SCROLL_FLUSH_MS)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (paused != null) return
        handler.removeCallbacks(flushScroll)
        // Fold any pending scroll into one quick flick.
        val dx = scrollDx - velocityX * 0.1f
        val dy = scrollDy - velocityY * 0.1f
        scrollDx = 0f
        scrollDy = 0f
        dispatchSwipe(dx, dy, 100L)
    }

    // ---- Coordinate mapping --------------------------------------------------

    /** Car-screen pixel → phone pixel, undoing the letterbox scaling. */
    private fun toPhone(x: Float, y: Float): Pair<Float, Float>? {
        val c = surfaceContainer ?: return null
        val phone = MirrorService.instance?.phoneScreenSize() ?: return null
        val scale = min(c.width.toFloat() / phone.x, c.height.toFloat() / phone.y)
        val offX = (c.width - phone.x * scale) / 2f
        val offY = (c.height - phone.y * scale) / 2f
        val px = (x - offX) / scale
        val py = (y - offY) / scale
        if (px < 0 || py < 0 || px >= phone.x || py >= phone.y) return null
        return px to py
    }

    /** Scroll deltas are "content moved by"; the finger moves the opposite way. */
    private fun dispatchSwipe(carDx: Float, carDy: Float, durationMs: Long) {
        val touch = TouchService.instance ?: return
        val c = surfaceContainer ?: return
        val phone = MirrorService.instance?.phoneScreenSize() ?: return
        if (abs(carDx) < 2f && abs(carDy) < 2f) return
        val scale = min(c.width.toFloat() / phone.x, c.height.toFloat() / phone.y)
        val cx = phone.x / 2f
        val cy = phone.y / 2f
        val ex = (cx - carDx / scale).coerceIn(1f, phone.x - 1f)
        val ey = (cy - carDy / scale).coerceIn(1f, phone.y - 1f)
        touch.swipe(cx, cy, ex, ey, durationMs)
    }
}
