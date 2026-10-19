package com.example.parallaxwallpaper.gallery

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.sensor.GyroscopeSmoother
import com.example.parallaxwallpaper.wallpaper.ParallaxEngine
import java.util.concurrent.Executors

/** EGL uploads, drawing and destruction share one dedicated thread. */
class PhotoPreviewView(context: Context, private val photo: PhotoEntry,
                       private val onError: (String) -> Unit) : SurfaceView(context), SurfaceHolder.Callback {
    private val thread = HandlerThread("AlbumPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val loader = Executors.newSingleThreadExecutor()
    private val renderer = ParallaxEngine().apply { fitImage = true; parallaxStrength = photo.strength }
    private val gyro = GyroscopeSmoother(context)
    @Volatile private var released = false
    private var ready = false
    private var active = true
    private var dragging = false
    private var touchX = 0f
    private var touchY = 0f
    private var originX = 0f
    private var originY = 0f
    private var generation = 0
    init { holder.addCallback(this) }
    private val frame = object : Runnable {
        override fun run() {
            if (!ready || !active || released) return
            val tilt = gyro.currentTilt
            renderer.draw(if (dragging) touchX else tilt.y, if (dragging) touchY else -tilt.x)
            handler.postDelayed(this, 16)
        }
    }
    override fun surfaceCreated(holder: SurfaceHolder) = Unit
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (released) return
        handler.post {
            if (ready) { renderer.onSurfaceChanged(width, height); return@post }
            try {
                renderer.init(holder.surface, width, height)
                ready = true
                val token = ++generation
                loader.execute {
                    try {
                        val bitmap = BitmapFactory.decodeFile(photo.imagePath) ?: error("照片读取失败")
                        try {
                            val depth = PhotoStore(context).readDepth(photo)
                            handler.post {
                                try {
                                    if (ready && generation == token && !released) {
                                        renderer.uploadImage(bitmap)
                                        renderer.uploadDepth(depth, DepthEstimator.INPUT_SIZE, DepthEstimator.INPUT_SIZE)
                                        handler.removeCallbacks(frame)
                                        if (active) handler.post(frame)
                                    }
                                } finally { bitmap.recycle() }
                            }
                        } catch (e: Exception) { bitmap.recycle(); throw e }
                    } catch (_: Exception) { post { if (!released) onError("预览读取失败，请重新导入照片") } }
                }
            } catch (_: Exception) { post { onError("当前设备无法创建立体预览") } }
        }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            try { generation++; handler.removeCallbacks(frame); if (ready) renderer.release(); ready = false }
            finally { done.countDown() }
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }
    fun setStrength(value: Float) { if (!released) handler.post { renderer.parallaxStrength = value } }
    fun setActive(value: Boolean) {
        if (released) return
        if (value) gyro.start() else gyro.stop()
        handler.post {
            active = value
            handler.removeCallbacks(frame)
            if (active && ready) handler.post(frame)
        }
    }
    fun recenter() { gyro.reset(); handler.post { touchX = 0f; touchY = 0f } }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                originX = event.x; originY = event.y
                handler.post { dragging = true; touchX = 0f; touchY = 0f }
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ((event.x - originX) / (width * 0.25f)).coerceIn(-1f, 1f)
                val y = ((event.y - originY) / (height * 0.25f)).coerceIn(-1f, 1f)
                handler.post { touchX = x; touchY = y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.post { dragging = false }
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun dispose() {
        if (released) return
        released = true
        gyro.stop()
        holder.removeCallback(this)
        loader.shutdown()
        handler.post {
            handler.removeCallbacks(frame)
            if (ready) renderer.release()
            ready = false
            thread.quitSafely()
        }
    }
}
