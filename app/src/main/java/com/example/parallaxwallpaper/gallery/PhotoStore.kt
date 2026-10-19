package com.example.parallaxwallpaper.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.parallaxwallpaper.depth.DepthEstimator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

data class PhotoEntry(val id: String, val title: String, val created: Long,
                      val imagePath: String, val width: Int, val height: Int,
                      val strength: Float = 0.018f)

/** Each photo owns its oriented image, cached depth, and editable metadata. */
class PhotoStore(private val context: Context) {
    private val root = File(context.filesDir, "gallery").apply { mkdirs() }
    companion object { private val inferenceLock = Mutex() }

    fun list(): List<PhotoEntry> = root.listFiles().orEmpty().mapNotNull { dir ->
        try {
            val meta = JSONObject(File(dir, "photo.json").readText())
            if (!File(dir, "photo.jpg").isFile || !File(dir, "depth.bin").isFile) return@mapNotNull null
            PhotoEntry(dir.name, meta.getString("title"), meta.getLong("created"),
                File(dir, "photo.jpg").absolutePath, meta.getInt("width"), meta.getInt("height"),
                meta.optDouble("strength", 0.018).toFloat())
        } catch (_: Exception) { null }
    }.sortedByDescending { it.created }

    suspend fun importPhoto(uri: Uri, status: (String) -> Unit): PhotoEntry {
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            status("正在读取照片…")
            val source = File(dir, "source")
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取这张照片，请重新选择")
            val bitmap = decodeSource(source)
            try {
                File(dir, "photo.jpg").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) { "照片保存失败" }
                }
                status("正在生成立体效果，首次可能需要十几秒…")
                val depth = inferenceLock.withLock {
                    DepthEstimator(context).use { it.estimate(bitmap).copyOf() }
                }
                DataOutputStream(File(dir, "depth.bin").outputStream().buffered()).use { out ->
                    out.writeInt(DepthEstimator.INPUT_SIZE)
                    out.writeInt(DepthEstimator.INPUT_SIZE)
                    depth.forEach { out.writeFloat(it) }
                }
                val now = System.currentTimeMillis()
                val title = java.text.SimpleDateFormat("M月d日 · HH:mm", java.util.Locale.CHINA).format(java.util.Date(now))
                val entry = PhotoEntry(dir.name, title, now, File(dir, "photo.jpg").absolutePath,
                    bitmap.width, bitmap.height)
                writeMeta(entry)
                source.delete()
                return entry
            } finally { bitmap.recycle() }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun readDepth(entry: PhotoEntry): FloatArray = DataInputStream(
        File(root, "${entry.id}/depth.bin").inputStream().buffered()).use { input ->
        val w = input.readInt(); val h = input.readInt()
        require(w == DepthEstimator.INPUT_SIZE && h == DepthEstimator.INPUT_SIZE)
        FloatArray(w * h) { input.readFloat() }
    }

    fun entryForPath(path: String?): PhotoEntry? = list().firstOrNull { it.imagePath == path }
    fun delete(entry: PhotoEntry) { File(root, entry.id).deleteRecursively() }
    fun writeMeta(entry: PhotoEntry) {
        val dir = File(root, entry.id)
        val json = JSONObject().put("title", entry.title).put("created", entry.created)
            .put("width", entry.width).put("height", entry.height).put("strength", entry.strength)
        val temp = File(dir, "photo.json.tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(File(dir, "photo.json"))) { "无法保存照片信息" }
    }

    fun decodeSource(source: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "暂不支持这种图片格式" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val original = BitmapFactory.decodeFile(source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("照片解码失败")
        val orientation = try { ExifInterface(source.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } catch (_: Exception) { 1 }
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f, 1f) }
                8 -> setRotate(-90f)
            }
        }
        val oriented = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        if (oriented !== original) original.recycle()
        if (max(oriented.width, oriented.height) <= 2048) return oriented
        val scale = 2048f / max(oriented.width, oriented.height)
        val resized = Bitmap.createScaledBitmap(oriented, (oriented.width * scale).toInt().coerceAtLeast(1),
            (oriented.height * scale).toInt().coerceAtLeast(1), true)
        if (resized !== oriented) oriented.recycle()
        return resized
    }
}
