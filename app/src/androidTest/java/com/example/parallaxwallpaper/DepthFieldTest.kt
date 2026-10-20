package com.example.parallaxwallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.depth.DepthField
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class DepthFieldTest {
    private fun checkField(field: FloatArray, w: Int, h: Int) {
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            assertTrue(field[i].isFinite() && field[i] in 0f..1f)
            if (x > 0) assertTrue(abs(field[i] - field[i - 1]) <= DepthField.MAX_UV_SLOPE / w + 1e-6f)
            if (y > 0) assertTrue(abs(field[i] - field[i - w]) <= DepthField.MAX_UV_SLOPE / h + 1e-6f)
        }
        val packed = DepthField.pack(field)
        for (i in field.indices) {
            val decoded = ((packed[i * 4].toInt() and 255) * 256 + (packed[i * 4 + 1].toInt() and 255)) / 65535f
            assertEquals(field[i], decoded, 0.5f / 65535f + 1e-7f)
        }
    }

    @Test fun discontinuitiesNoiseAndInvalidDepthRemainStable() {
        val w = 518; val h = 259
        val source = FloatArray(w * h) { i ->
            if (i % w in 150..368) 0.9f else if (i % 2 == 0) 0.05f else 0.15f
        }
        source[0] = Float.NaN; source[1] = Float.POSITIVE_INFINITY
        val prepared = DepthField.prepare(source, w, h)
        assertTrue(source[0].isNaN()) // Never modify the stored depth.
        checkField(prepared, w, h)
        assertTrue(prepared[h / 2 * w + w / 2] > 0.85f) // Keep useful depth, not a flat image.
        assertTrue(prepared[h / 2 * w + 40] < 0.15f)
        checkField(DepthField.prepare(floatArrayOf(0.5f), 1, 1), 1, 1)
        assertArrayEquals(FloatArray(32 * 16) { 0.5f }, DepthField.prepare(FloatArray(32 * 16) { 0.5f }, 32, 16), 1e-6f)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "depth-research").apply { mkdirs() }
        val square = FloatArray(518 * 518) { i ->
            if (i % 518 in 150 until 368 && i / 518 in 70 until 448) 0.9f else 0.1f
        }
        DataOutputStream(File(folder, "synthetic-prepared.bin").outputStream().buffered()).use { out ->
            for (v in DepthField.prepare(square, 518, 518)) out.writeFloat(v)
        }
    }

    @Test fun compareHighAndLowResolutionPhotoDepth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "depth-research").apply { mkdirs() }
        val photo = File(folder, "input.png")
        // A local QA fixture, never uploaded to the server or added to the APK.
        if (!photo.exists()) return
        val high = BitmapFactory.decodeFile(photo.absolutePath)!!
        val low = Bitmap.createScaledBitmap(high, high.width / 4, high.height / 4, true)
        DepthEstimator(context).use { estimator ->
            for ((label, bitmap) in listOf("high" to high, "low" to low)) {
                val raw = estimator.estimate(bitmap).copyOf()
                val started = System.nanoTime()
                val prepared = DepthField.prepare(raw, 518, 518)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                checkField(prepared, 518, 518)
                for ((kind, data) in listOf("raw" to raw, "prepared" to prepared)) {
                    DataOutputStream(File(folder, "$label-$kind.bin").outputStream().buffered()).use { out ->
                        for (v in data) out.writeFloat(v)
                    }
                }
                File(folder, "$label-timing.txt").writeText("prepare_ms=$elapsed")
            }
        }
        low.recycle(); high.recycle()
    }
}
