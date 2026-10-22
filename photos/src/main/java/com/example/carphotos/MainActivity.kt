package com.example.carphotos

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Phone side: choose which photos show on the car display. */
class MainActivity : ComponentActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var intervalButton: Button
    private lateinit var grid: GridLayout
    private val onChange: () -> Unit = { refresh() }

    private val picker =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            status.text = "Adding ${uris.size} photo(s)…"
            worker.execute {
                val added = PhotoStore.add(applicationContext, uris)
                runOnUiThread {
                    Toast.makeText(this, "Added $added photo(s)", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        status = TextView(this).apply { textSize = 15f }
        val add = Button(this).apply {
            text = "Add photos"
            setOnClickListener {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
        intervalButton = Button(this).apply { setOnClickListener { nextInterval() } }
        val clear = Button(this).apply {
            text = "Remove all"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remove all photos from Car Photos? (Your gallery isn't affected.)")
                    .setPositiveButton("Remove") { _, _ -> PhotoStore.clear(this@MainActivity) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        grid = GridLayout(this).apply { columnCount = 3 }
        val hint = TextView(this).apply {
            textSize = 13f
            text = "Long-press a photo to remove it.\n\nOn the car: open Car Photos from the Android Auto app list. " +
                "Swipe or use ◀ ▶ to change photos; tap the screen to pause. " +
                "Photos show only while the car is stopped."
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
            addView(TextView(context).apply { text = "Car Photos"; textSize = 26f })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad / 2 })
            addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(intervalButton, LinearLayout.LayoutParams(-1, -2))
            addView(clear, LinearLayout.LayoutParams(-1, -2))
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
            addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onStart() {
        super.onStart()
        PhotoStore.addListener(onChange)
        refresh()
    }

    override fun onStop() {
        PhotoStore.removeListener(onChange)
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun nextInterval() {
        val all = PhotoStore.INTERVALS
        val cur = PhotoStore.intervalSeconds(this)
        val next = all[(all.indexOf(cur) + 1).mod(all.size)]
        PhotoStore.setIntervalSeconds(this, next)
    }

    private fun refresh() {
        val photos = PhotoStore.list(this)
        status.text = if (photos.isEmpty()) "No photos yet." else "${photos.size} photo(s) ready for the car."
        intervalButton.text = "Slideshow: every ${PhotoStore.intervalSeconds(this)} s"

        grid.removeAllViews()
        val size = (resources.displayMetrics.widthPixels - 40 * resources.displayMetrics.density).toInt() / 3
        for (file in photos) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(4, 4, 4, 4)
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Remove this photo?")
                        .setPositiveButton("Remove") { _, _ -> PhotoStore.delete(file) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }
            grid.addView(iv, GridLayout.LayoutParams().apply { width = size; height = size })
            worker.execute {
                val thumb = PhotoStore.load(file, size, size)
                runOnUiThread { iv.setImageBitmap(thumb) }
            }
        }
    }
}
