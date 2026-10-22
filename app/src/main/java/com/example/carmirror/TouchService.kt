package com.example.carmirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Turns taps and swipes from the car screen into touches on the phone.
 * Android has no other way for a normal app to inject touches into other apps.
 * This service never reads screen content; it only dispatches gestures.
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TouchService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 0L, 60L)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, 0L, durationMs.coerceIn(50L, 1000L))
    }

    private fun dispatch(path: Path, start: Long, duration: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, start, duration))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
