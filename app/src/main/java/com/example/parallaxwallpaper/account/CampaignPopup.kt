package com.example.parallaxwallpaper.account

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CampaignPopup(config: JSONObject, onDisplayed: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val screen = LocalConfiguration.current
    val url = config.optString("url")
    fun official(value: String): Boolean {
        val uri = Uri.parse(value)
        return uri.scheme == "https" && uri.host == "fuguang.startimetable.xyz" && uri.port in listOf(-1, 443) && uri.userInfo == null
    }
    fun browse(value: String) { if (official(value)) try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) } catch (_: Exception) { } }
    var reported by remember(config.optString("id")) { mutableStateOf(false) }
    fun report() { if (!reported) { reported = true; onDisplayed() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width((screen.screenWidthDp * config.optInt("width",88) / 100).dp)
            .heightIn(max = (screen.screenHeightDp * config.optInt("height",75) / 100).dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(config.optString("title"), style = MaterialTheme.typography.titleLarge)
                if (config.optString("mode") == "web" && official(url)) {
                    val web = remember(url) {
                        WebView(context).apply {
                            var failed = false
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false; settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, loaded: String?) { if (!failed && loaded == url) report() }
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    request?.url?.toString()?.let { browse(it) }; return true
                                }
                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                    if (request?.isForMainFrame == true) { failed = true; onClose() }
                                }
                                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceResponse?) {
                                    if (request?.isForMainFrame == true) { failed = true; onClose() }
                                }
                                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                                    failed = true; handler?.cancel(); onClose()
                                }
                            }
                            setDownloadListener { link, _, _, _, _ -> browse(link) }
                            loadUrl(url)
                        }
                    }
                    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(380.dp))
                } else {
                    LaunchedEffect(Unit) { report() }
                    Text(config.optString("body"), modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                }
                if (official(url)) Button(onClick = { browse(url); onClose() }, modifier = Modifier.fillMaxWidth()) { Text(config.optString("button","立即更新")) }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("稍后再说") }
            }
        }
    }
}
