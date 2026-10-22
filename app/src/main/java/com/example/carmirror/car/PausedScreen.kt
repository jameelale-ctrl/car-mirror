package com.example.carmirror.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/**
 * Covers the car display whenever mirroring is not allowed (car moving,
 * speed unknown, or capture not started). The mirror is also detached from
 * the car surface at the same time, so nothing from the phone is shown.
 */
class PausedScreen(carContext: CarContext, initialMessage: String) : Screen(carContext) {

    var message: String = initialMessage
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        // Back must not reveal the mirror screen; MirrorScreen pops this when allowed.
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(message)
            .setTitle("Car Mirror")
            .setHeaderAction(Action.APP_ICON)
            .build()
}
