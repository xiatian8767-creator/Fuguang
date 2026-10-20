package com.example.parallaxwallpaper.account

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
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
            .height((screen.screenHeightDp * config.optInt("height",75) / 100).dp),
            shape = RoundedCornerShape(22.dp), color = Color(0xFFF6F5F1)) {
            Box(Modifier.fillMaxSize()) {
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
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
                } else {
                    LaunchedEffect(Unit) { report() }
                    Column(Modifier.fillMaxSize().padding(24.dp).padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(config.optString("title"), style = MaterialTheme.typography.titleLarge, color = Color(0xFF283446))
                        Text(config.optString("body"), modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), color = Color(0xFF283446))
                        if (official(url)) Button(onClick = { browse(url); onClose() }, modifier = Modifier.fillMaxWidth()) { Text(config.optString("button","立即更新")) }
                    }
                }
                Button(onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp).semantics { contentDescription = "关闭活动" },
                    shape = CircleShape, contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xEFFFFFFF), contentColor = Color(0xFF283446)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)) {
                    Text("×", fontSize = 25.sp)
                }
            }
        }
    }
}
