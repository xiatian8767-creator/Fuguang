package com.example.parallaxwallpaper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parallaxwallpaper.data.WallpaperPreferences
import com.example.parallaxwallpaper.gallery.PhotoEntry
import com.example.parallaxwallpaper.gallery.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PhotoStore(app)
    private val prefs = WallpaperPreferences(app)
    private val _photos = MutableStateFlow<List<PhotoEntry>>(emptyList())
    val photos = _photos.asStateFlow()
    private val _selected = MutableStateFlow<PhotoEntry?>(null)
    val selected = _selected.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    init { viewModelScope.launch { refresh() } }
    private suspend fun refresh() { _photos.value = withContext(Dispatchers.IO) { store.list() } }
    fun open(photo: PhotoEntry) { _selected.value = photo }
    fun close() { _selected.value = null }
    fun clearMessage() { _message.value = null }
    fun pickImage(uri: Uri) {
        if (_busy.value != null) return
        _busy.value = "正在准备照片…"
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { store.importPhoto(uri) { _busy.value = it } }
                refresh()
                _selected.value = entry
            } catch (e: Exception) {
                _message.value = "生成失败：${e.localizedMessage ?: "请稍后重试"}"
            } finally { _busy.value = null }
        }
    }
    fun save(photo: PhotoEntry) {
        _selected.value = photo
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.writeMeta(photo) }
                refresh()
            } catch (_: Exception) { _message.value = "保存失败，请重试" }
        }
    }
    fun delete(photo: PhotoEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.delete(photo) }
                _selected.value = null
                refresh()
            } catch (_: Exception) { _message.value = "删除失败，请重试" }
        }
    }
    fun setWallpaper(photo: PhotoEntry, onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val dir = java.io.File(app.filesDir, "active_wallpaper/${photo.id}").apply { mkdirs() }
                    val image = java.io.File(dir, "photo.jpg")
                    java.io.File(photo.imagePath).copyTo(image, overwrite = true)
                    java.io.File(java.io.File(photo.imagePath).parentFile, "depth.bin")
                        .copyTo(java.io.File(dir, "depth.bin"), overwrite = true)
                    image.absolutePath
                }
                prefs.setImagePath(path)
                prefs.setParallaxStrength(photo.strength)
                prefs.setTargetFps(60)
                onReady()
            } catch (_: Exception) { _message.value = "无法设置壁纸，请稍后重试" }
        }
    }
}
