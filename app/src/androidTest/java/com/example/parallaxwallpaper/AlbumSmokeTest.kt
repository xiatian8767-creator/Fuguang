package com.example.parallaxwallpaper

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.gallery.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AlbumSmokeTest {
    @Test fun importRotatedPhotoPersistAndRequireLogin(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "orientation-test.jpg")
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        for (y in 0 until 144) for (x in 0 until 96)
            bitmap.setPixel(x, y, if (x in 25..70 && y in 30..110) Color.RED else Color.rgb(x * 2, y, 170))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val store = PhotoStore(context)
        val entry = store.importPhoto(Uri.fromFile(source)) {}
        assertEquals(144, entry.width)
        assertEquals(96, entry.height)
        val depth = store.readDepth(entry)
        assertEquals(DepthEstimator.INPUT_SIZE * DepthEstimator.INPUT_SIZE, depth.size)
        assertTrue(depth.all { it.isFinite() && it in 0f..1f })
        assertEquals(entry.id, PhotoStore(context).list().first().id)
        val named = entry.copy(title = "立体预览测试")
        store.writeMeta(named)
        context.startActivity(Intent.makeMainActivity(android.content.ComponentName(context, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.wait(Until.hasObject(By.text("登录浮光")), 20000))
        val screenshots = context.getExternalFilesDir(null)!!
        device.takeScreenshot(File(screenshots, "01-first-login.png"))
        device.pressBack()
        assertTrue(device.hasObject(By.text("登录浮光")))
        assertFalse(device.hasObject(By.text("立体预览测试")))
        device.takeScreenshot(File(screenshots, "02-login-required.png"))
        assertEquals("立体预览测试", PhotoStore(context).list().first().title)
        source.delete()
        Unit
    }
}
