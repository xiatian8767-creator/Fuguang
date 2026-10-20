package com.example.parallaxwallpaper.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.parallaxwallpaper.depth.DepthEstimator
import com.example.parallaxwallpaper.wallpaper.ParallaxEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Uses the same renderer as the preview. Exporting cached photos consumes no quota. */
object VideoExporter {
    suspend fun export(context: Context, photo: PhotoEntry, progress: (Float) -> Unit): File {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(folder, "Fuguang-${System.currentTimeMillis()}.mp4")
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var surface: android.view.Surface? = null
        var renderer: ParallaxEngine? = null
        var started = false
        var complete = false
        var track = -1
        var samples = 0
        val info = MediaCodec.BufferInfo()
        val coroutine = currentCoroutineContext()
        try {
            val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            val scale = min(1f, min(1080f / max(photo.width, photo.height),
                min(caps.supportedWidths.upper.toFloat() / photo.width, caps.supportedHeights.upper.toFloat() / photo.height)))
            fun aligned(size: Int, alignment: Int, minimum: Int) = max(minimum, size / alignment * alignment)
            val width = aligned((photo.width * scale).toInt(), caps.widthAlignment, caps.supportedWidths.lower)
            val height = aligned((photo.height * scale).toInt(), caps.heightAlignment, caps.supportedHeights.lower)
            check(caps.isSizeSupported(width, height)) { "当前设备不支持这张照片的视频尺寸" }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            encoder.start()
            val output = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = output
            suspend fun drain(end: Boolean) {
                val deadline = System.nanoTime() + 30_000_000_000L
                while (true) {
                    coroutine.ensureActive()
                    when (val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end) return
                            check(System.nanoTime() < deadline) { "视频编码超时，请重试" }
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!started)
                            track = output.addTrack(encoder.outputFormat)
                            output.start(); started = true
                        }
                        else -> if (index >= 0) {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    check(started)
                                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    output.writeSampleData(track, buffer, info); samples++
                                }
                            } finally { encoder.releaseOutputBuffer(index, false) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }
            val engine = ParallaxEngine().apply { fitImage = true; parallaxStrength = photo.strength }
            renderer = engine
            engine.init(surface, width, height, recordable = true)
            val bitmap = BitmapFactory.decodeFile(photo.imagePath) ?: error("照片读取失败")
            try { engine.uploadImage(bitmap) } finally { bitmap.recycle() }
            engine.uploadDepth(PhotoStore(context).readDepth(photo), DepthEstimator.INPUT_SIZE, DepthEstimator.INPUT_SIZE)
            // Six seconds, 30 fps, a smooth closed camera path, with no UI or black bars.
            for (frame in 0 until 180) {
                coroutine.ensureActive()
                drain(false)
                val phase = frame * 2.0 * Math.PI / 180
                engine.draw((sin(phase) * 0.8).toFloat(), (cos(phase) * 0.35).toFloat(), frame * 1_000_000_000L / 30)
                progress((frame + 1) / 180f * 0.98f)
            }
            encoder.signalEndOfInputStream()
            drain(true)
            check(started && samples > 0) { "视频编码失败" }
            output.stop(); started = false
            check(file.length() > 0)
            complete = true
            progress(1f)
            return file
        } finally {
            runCatching { renderer?.release() }
            surface?.release()
            runCatching { encoder.stop() }; encoder.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            if (!complete) file.delete()
        }
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".exports", file)

    fun saveToAlbum(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Fuguang")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法保存到相册")
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}
