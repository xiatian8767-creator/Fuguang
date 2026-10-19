package com.example.parallaxwallpaper.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.parallaxwallpaper.gallery.PhotoEntry
import com.example.parallaxwallpaper.gallery.PhotoPreviewView
import com.example.parallaxwallpaper.wallpaper.ParallaxWallpaperService
import java.io.File

private val Ink = Color(0xFF28243C)
private val Purple = Color(0xFF7460C8)
private val Paper = Color(0xFFF6F4FA)
private val Muted = Color(0xFF817C92)

@Composable
fun SetupScreen(modifier: Modifier = Modifier, vm: SetupViewModel = viewModel()) {
    val photos by vm.photos.collectAsState()
    val selected by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(vm::pickImage)
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Purple, onPrimary = Color.White,
        background = Paper, surface = Color.White, onSurface = Ink, onBackground = Ink)) {
        Surface(modifier = modifier, color = Paper) {
            val photo = selected
            if (photo == null) AlbumHome(photos, { picker.launch("image/*") }, vm::open)
            else key(photo.id) { PhotoDetail(photo, vm) }
        }
        busy?.let { status ->
            Dialog(onDismissRequest = {}, properties = DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false)) {
                Surface(shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CircularProgressIndicator(Modifier.size(38.dp), color = Purple)
                        Text("让照片有一点空间", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(status, color = Muted, fontSize = 14.sp)
                        Text("照片只在本机处理，完成后自动存入相册", color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        message?.let {
            AlertDialog(onDismissRequest = vm::clearMessage, title = { Text("温馨提示") },
                text = { Text(it) }, confirmButton = { TextButton(onClick = vm::clearMessage) { Text("知道了") } })
        }
    }
}

@Composable
private fun AlbumHome(photos: List<PhotoEntry>, onImport: () -> Unit, onOpen: (PhotoEntry) -> Unit) {
    Scaffold(containerColor = Paper,
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onImport, containerColor = Ink, contentColor = Color.White,
                icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("导入照片") })
        }) { padding ->
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("浮光", color = Ink, fontWeight = FontWeight.Bold, fontSize = 34.sp)
                    Text("你的随身 3D 相册", color = Muted, fontSize = 14.sp)
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFE9E1FC), Color(0xFFE4EDF8))))
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Purple, modifier = Modifier.size(30.dp))
                    Text("轻轻一转，回忆鲜活起来", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("一张普通照片，也能拥有立体视差。\n导入一次，随时打开，还能设为动态壁纸。",
                        fontSize = 13.sp, color = Color(0xFF686178), lineHeight = 21.sp)
                    Text("本机生成  ·  离线查看  ·  无需注册", fontSize = 11.sp, color = Purple)
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("我的相册", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("${photos.size} 张", color = Muted, fontSize = 13.sp)
                }
            }
            if (photos.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(56.dp), tint = Color(0xFFB4A7D6))
                    Text("第一张立体照片，从这里开始", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    Text("试试主体清晰、前后景分明的照片", color = Muted, fontSize = 13.sp)
                    Button(onClick = onImport, shape = RoundedCornerShape(16.dp)) { Text("选择一张照片") }
                }
            }
            items(photos, key = { it.id }) { photo ->
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White,
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(photo) }) {
                    Column {
                        Box {
                            AsyncImage(model = File(photo.imagePath), contentDescription = photo.title,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.82f), contentScale = ContentScale.Crop)
                            Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.5f)) {
                                Text("3D", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(photo.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("轻点探索立体视角", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoDetail(photo: PhotoEntry, vm: SetupViewModel) {
    val context = LocalContext.current
    var strength by remember { mutableFloatStateOf(photo.strength) }
    var immersive by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var title by remember(photo.title) { mutableStateOf(photo.title) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val preview = remember(photo.id) { PhotoPreviewView(context, photo) { previewError = it } }
    DisposableEffect(preview) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> preview.setActive(true)
                Lifecycle.Event.ON_PAUSE -> preview.setActive(false)
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        preview.setActive(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false)
        onDispose { owner?.lifecycle?.removeObserver(observer); preview.dispose() }
    }
    BackHandler { if (immersive) immersive = false else vm.close() }
    Column(Modifier.fillMaxSize().background(Color(0xFF121119))) {
        if (!immersive) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::close) { Icon(Icons.Default.ArrowBack, "返回相册", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(photo.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                Text("立体照片 · 已保存", color = Color(0xFFAAA2BB), fontSize = 11.sp)
            }
            IconButton(onClick = { renameDialog = true }) { Icon(Icons.Default.Edit, "重命名", tint = Color.White) }
            IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Default.DeleteOutline, "删除照片", tint = Color(0xFFE3B9C6)) }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize(), update = { it.setStrength(strength) })
            if (previewError != null) Surface(color = Ink.copy(alpha = 0.9f), shape = RoundedCornerShape(12.dp)) {
                Text(previewError!!, Modifier.padding(16.dp), color = Color.White)
            }
            if (immersive) TextButton(onClick = { immersive = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White, containerColor = Ink.copy(alpha = 0.7f))) {
                Text("显示操作")
            }
        }
        if (!immersive) Surface(color = Paper, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("转动手机，或在照片上拖动", color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("立体强度", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (strength < 0.015f) "轻柔" else if (strength < 0.026f) "自然" else "明显", color = Purple, fontSize = 13.sp)
                }
                Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.005f..0.035f,
                    onValueChangeFinished = { vm.save(photo.copy(strength = strength)) }, modifier = Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { preview.recenter() }, modifier = Modifier.weight(1f)) { Text("视角回正") }
                    OutlinedButton(onClick = { immersive = true }, modifier = Modifier.weight(1f)) { Text("沉浸查看") }
                }
                Button(onClick = {
                    vm.setWallpaper(photo.copy(strength = strength)) {
                        try {
                            context.startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, ParallaxWallpaperService::class.java))
                            })
                        } catch (_: Exception) { previewError = "请在系统的动态壁纸列表中选择「浮光」" }
                    }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Wallpaper, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("设为动态壁纸")
                }
            }
        }
    }
    if (deleteDialog) AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("删除这张照片？") },
        text = { Text("会移除相册里的照片和立体效果，手机原图不受影响。已应用的壁纸会保留。") },
        confirmButton = { TextButton(onClick = { deleteDialog = false; vm.delete(photo) }) { Text("删除", color = Color(0xFFB64C65)) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("取消") } })
    if (renameDialog) AlertDialog(onDismissRequest = { renameDialog = false }, title = { Text("给照片起个名字") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it.take(30) }, label = { Text("照片名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (title.isNotBlank()) { vm.save(photo.copy(title = title.trim(), strength = strength)); renameDialog = false }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("取消") } })
}
