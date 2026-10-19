package com.example.parallaxwallpaper.wallpaper

import com.example.parallaxwallpaper.gallery.PhotoStore
import android.os.Handler
import android.os.HandlerThread
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import com.example.parallaxwallpaper.data.WallpaperPreferences
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.sensor.GyroscopeSmoother
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ParallaxWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WallpaperEngine()
    inner class WallpaperEngine : Engine() {
        private val renderThread = HandlerThread("WallpaperRender").apply { start() }
        private val handler = Handler(renderThread.looper)
        private val renderer = ParallaxEngine()
        private val gyro = GyroscopeSmoother(this@ParallaxWallpaperService)
        private val prefs = WallpaperPreferences(this@ParallaxWallpaperService)
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val surfaceState = MutableStateFlow(false)
        private val generation = AtomicInteger(0)
        private var ready = false
        private var visible = false
        private var destroyed = false
        private var targetFps = 60
        private val frame = object : Runnable {
            override fun run() {
                if (!ready || !visible || destroyed) return
                val tilt = gyro.currentTilt
                renderer.draw(tilt.y, -tilt.x)
                handler.postDelayed(this, 1000L / targetFps)
            }
        }
        init {
            scope.launch {
                combine(prefs.settingsFlow, surfaceState) { settings, surface -> settings to surface }
                    .collectLatest { (settings, surface) ->
                        if (!surface) return@collectLatest
                        val token = generation.incrementAndGet()
                        val path = settings.imagePath ?: return@collectLatest
                        val bitmap = try { PhotoStore(this@ParallaxWallpaperService).decodeSource(File(path)) }
                            catch (e: Exception) { Log.e("FloatingPhoto", "Cannot decode wallpaper", e); return@collectLatest }
                        try {
                            handler.post {
                                if (ready && generation.get() == token && !destroyed) {
                                    targetFps = settings.targetFps.coerceIn(30, 90)
                                    renderer.parallaxStrength = settings.parallaxStrength.coerceAtMost(0.035f)
                                }
                            }
                            val cached = File(File(path).parentFile, "depth.bin")
                            val depth = if (cached.isFile) {
                                DataInputStream(cached.inputStream().buffered()).use { input ->
                                    require(input.readInt() == DepthEstimator.INPUT_SIZE)
                                    require(input.readInt() == DepthEstimator.INPUT_SIZE)
                                    FloatArray(DepthEstimator.INPUT_SIZE * DepthEstimator.INPUT_SIZE) { input.readFloat() }
                                }
                            } else {
                                DepthEstimator(this@ParallaxWallpaperService, settings.useNnapi).use { it.estimate(bitmap).copyOf() }
                            }
                            currentCoroutineContext().ensureActive()
                            // Publish image + depth together; never render a new image with stale depth.
                            val accepted = handler.post {
                                try {
                                    if (ready && generation.get() == token && !destroyed) {
                                        renderer.uploadImage(bitmap)
                                        renderer.uploadDepth(depth, DepthEstimator.INPUT_SIZE, DepthEstimator.INPUT_SIZE)
                                        gyro.reset()
                                        handler.removeCallbacks(frame)
                                        if (visible) handler.post(frame)
                                    }
                                } finally { bitmap.recycle() }
                            }
                            if (!accepted) bitmap.recycle()
                        } catch (e: CancellationException) { bitmap.recycle(); throw e }
                        catch (e: Exception) { bitmap.recycle(); Log.e("FloatingPhoto", "Wallpaper loading failed", e) }
                    }
            }
        }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            handler.post {
                if (!ready) {
                    renderer.init(holder.surface, width, height)
                    ready = true
                    surfaceState.value = true
                } else renderer.onSurfaceChanged(width, height)
                handler.removeCallbacks(frame)
                if (visible) handler.post(frame)
            }
        }
        override fun onVisibilityChanged(value: Boolean) {
            super.onVisibilityChanged(value)
            if (value) { gyro.reset(); gyro.start() } else gyro.stop()
            handler.post {
                visible = value
                handler.removeCallbacks(frame)
                if (visible && ready) handler.post(frame)
            }
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceState.value = false
            generation.incrementAndGet()
            val done = CountDownLatch(1)
            handler.post {
                try { handler.removeCallbacks(frame); if (ready) renderer.release(); ready = false }
                finally { done.countDown() }
            }
            done.await(2, TimeUnit.SECONDS)
            super.onSurfaceDestroyed(holder)
        }
        override fun onDestroy() {
            scope.cancel()
            gyro.stop()
            generation.incrementAndGet()
            handler.post {
                destroyed = true
                handler.removeCallbacks(frame)
                if (ready) renderer.release()
                ready = false
                renderThread.quitSafely()
            }
            super.onDestroy()
        }
    }
}
