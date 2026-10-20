package com.example.parallaxwallpaper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parallaxwallpaper.data.WallpaperPreferences
import com.example.parallaxwallpaper.account.AccountClient
import com.example.parallaxwallpaper.account.AccountException
import org.json.JSONObject
import java.util.UUID
import com.example.parallaxwallpaper.gallery.PhotoEntry
import com.example.parallaxwallpaper.gallery.PhotoStore
import com.example.parallaxwallpaper.gallery.VideoExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress = _exportProgress.asStateFlow()
    private var exportJob: Job? = null
    fun cancelExport() { exportJob?.cancel() }
    fun exportVideo(photo: PhotoEntry, save: Boolean, onShare: (Uri) -> Unit) {
        if (_exportProgress.value != null) return
        _exportProgress.value = 0f
        exportJob = viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val file = withContext(Dispatchers.Default) {
                    VideoExporter.export(app, photo) { _exportProgress.value = it }
                }
                if (save) {
                    withContext(Dispatchers.IO) { VideoExporter.saveToAlbum(app, file) }
                    file.delete()
                    _message.value = "视频已保存到相册"
                } else onShare(VideoExporter.shareUri(app, file))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _message.value = "导出失败：${e.localizedMessage ?: "请稍后重试"}" }
            finally { _exportProgress.value = null }
        }
    }
    private val store = PhotoStore(app)
    private val prefs = WallpaperPreferences(app)
    private val client = AccountClient(app)
    private val sessionPrefs = app.getSharedPreferences("fuguang_onboarding", Application.MODE_PRIVATE)
    private val _loginRequired = MutableStateFlow(!sessionPrefs.getBoolean("completed",false) || !client.hasSession())
    val loginRequired = _loginRequired.asStateFlow()
    private val _account = MutableStateFlow<JSONObject?>(null)
    val account = _account.asStateFlow()
    private val _accountBusy = MutableStateFlow(false)
    val accountBusy = _accountBusy.asStateFlow()
    private val _accountMessage = MutableStateFlow<String?>(null)
    val accountMessage = _accountMessage.asStateFlow()
    private val _campaign = MutableStateFlow<JSONObject?>(null)
    val campaign = _campaign.asStateFlow()
    private var campaignChecked = false
    fun checkCampaign() {
        if (campaignChecked || !client.hasSession()) return
        campaignChecked = true
        viewModelScope.launch {
            try { _campaign.value = withContext(Dispatchers.IO) { client.campaign() } } catch (_: Exception) { }
        }
    }
    fun dismissCampaign() { _campaign.value = null }
    fun campaignDisplayed(config: JSONObject) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { client.displayed(config.getString("id"),config.getInt("revision")) } } catch (_: Exception) { }
        }
    }
    private fun accountAction(block: () -> JSONObject?) {
        if (_accountBusy.value || _busy.value != null) return
        _accountBusy.value = true; _accountMessage.value = null
        viewModelScope.launch {
            try { _account.value = withContext(Dispatchers.IO) { block() }; if (_account.value != null) checkCampaign() }
            catch (e: Exception) {
                if (!client.hasSession()) { _account.value = null; _campaign.value = null; _loginRequired.value = true }
                _accountMessage.value = e.localizedMessage ?: "无法连接会员服务"
            }
            finally { _accountBusy.value = false }
        }
    }
    fun refreshAccount() {
        if (client.hasSession()) accountAction {
            client.me().also { sessionPrefs.edit().putBoolean("completed",true).commit(); _loginRequired.value=false }
        } else _account.value = null
    }
    fun authenticate(username: String, password: String, register: Boolean) = accountAction {
        client.login(username,password,register).also {
            sessionPrefs.edit().putBoolean("completed",true).commit()
            _loginRequired.value=false
        }
    }
    fun redeem(code: String) = accountAction { client.redeem(code) }
    fun logout() = accountAction { client.logout(); _loginRequired.value=true; _campaign.value=null; campaignChecked=false; null }
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
        if (_busy.value != null || _accountBusy.value) return
        if (!client.hasSession()) { _loginRequired.value=true; return }
        _busy.value = "正在准备照片…"
        viewModelScope.launch {
            val job = UUID.randomUUID().toString()
            var reserved = false
            var entry: PhotoEntry? = null
            try {
                val ticket = withContext(Dispatchers.IO) { client.reserve(job) }
                reserved = true
                entry = withContext(Dispatchers.IO) { store.importPhoto(uri, job, ticket.getString("uid")) { _busy.value = it } }
                withContext(Dispatchers.IO) { client.commit(job) }
                entry?.let { generated -> withContext(Dispatchers.IO) { store.clearPending(generated) } }
                refresh()
                _selected.value = entry
                try { _account.value = withContext(Dispatchers.IO) { client.me() } } catch (_: Exception) { }
            } catch (e: Exception) {
                if (!client.hasSession()) _account.value = null
                // A successfully generated local photo is kept when final confirmation loses connectivity.
                if (entry != null) {
                    _message.value = "照片已生成，联网确认额度后会显示在相册中，请稍后重新打开应用"
                } else if (reserved) { try { withContext(Dispatchers.IO) { client.cancel(job) } } catch (_: Exception) { } }
                if (entry == null) {
                    _message.value = "生成失败：${e.localizedMessage ?: "请稍后重试"}"
                }
            } finally { _busy.value = null }
        }
    }
    fun reconcilePending() {
        if (!client.hasSession()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val owner = try { client.me().getString("id") } catch (_: Exception) { return@withContext }
                store.pending().forEach { (photo, job, photoOwner) ->
                    if (owner != photoOwner) return@forEach
                    try { client.commit(job); store.clearPending(photo) }
                    catch (e: AccountException) {
                        if (e.status == 409 || e.status == 404) {
                            store.delete(photo)
                            _message.value = "有一张照片未在30分钟内确认额度，未扣费。请重新导入原照片。"
                        }
                    } catch (_: Exception) { }
                }
            }
            refresh()
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
