package com.example.carmirror

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Phone-side screen: start/stop capture and turn on tap control. */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var touchButton: Button

    private val captureRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val intent = Intent(this, MirrorService::class.java)
                    .putExtra(MirrorService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(MirrorService.EXTRA_RESULT_DATA, result.data)
                startForegroundService(intent)
            }
            // The service starts asynchronously; update the status once it's up.
            status.postDelayed({ refresh() }, 500)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 16f }
        startStop = Button(this).apply { setOnClickListener { toggleMirroring() } }
        touchButton = Button(this).apply {
            text = "Set up tap control"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val note = TextView(this).apply {
            textSize = 13f
            text = "The car display shows your phone only while the car is stopped. " +
                "When moving, mirroring pauses automatically.\n\n" +
                "Tap control: in Accessibility settings, open \"Car Mirror tap control\" and turn it on. " +
                "If it is greyed out, go to App info → ⋮ → Allow restricted settings first."
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply { text = "Car Mirror"; textSize = 26f })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(startStop, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(touchButton, LinearLayout.LayoutParams(-1, -2))
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun toggleMirroring() {
        if (MirrorService.isRunning) {
            MirrorService.stop(this)
            startStop.postDelayed({ refresh() }, 300)
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // On Android 14+ choose "Entire screen" in the system prompt to mirror everything.
            captureRequest.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun refresh() {
        val mirroring = MirrorService.isRunning
        val touch = TouchService.isEnabled
        status.text = buildString {
            append(if (mirroring) "● Capturing screen" else "○ Not capturing")
            append('\n')
            append(if (touch) "● Tap control on" else "○ Tap control off (car screen will be view-only)")
            append("\n\nThen connect to the car and open Car Mirror from the Android Auto app launcher.")
        }
        startStop.text = if (mirroring) "Stop mirroring" else "Start mirroring"
        touchButton.isEnabled = !touch
    }
}
