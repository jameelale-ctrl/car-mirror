package com.example.carphotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max

/**
 * Photos are copied (downscaled) into the app's private folder, so the car
 * side can show them anytime without needing the gallery or permissions.
 */
object PhotoStore {

    private const val MAX_SIDE = 1920
    private const val PREFS = "settings"
    private const val KEY_INTERVAL = "interval_s"
    val INTERVALS = intArrayOf(5, 10, 30, 60)

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun notifyChanged() = main.post { listeners.forEach { it() } }

    private fun dir(context: Context): File =
        File(context.filesDir, "photos").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Runs on a background thread. Returns how many were added. */
    fun add(context: Context, uris: List<Uri>): Int {
        var added = 0
        for (uri in uris) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > MAX_SIDE) {
                        val s = MAX_SIDE.toFloat() / longest
                        decoder.setTargetSize((w * s).toInt(), (h * s).toInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val out = File(dir(context), "%d_%03d.jpg".format(System.currentTimeMillis(), added))
                FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                added++
            } catch (_: Exception) {
                // Skip anything that can't be decoded.
            }
        }
        if (added > 0) notifyChanged()
        return added
    }

    fun delete(file: File) {
        file.delete()
        notifyChanged()
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        notifyChanged()
    }

    /** Decode a stored photo, sampled down to roughly fit the target size. */
    fun load(file: File, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun intervalSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 10)

    fun setIntervalSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INTERVAL, seconds).apply()
        notifyChanged()
    }
}
