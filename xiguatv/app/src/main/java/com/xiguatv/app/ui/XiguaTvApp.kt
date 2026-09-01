package com.xiguatv.app.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.xiguatv.app.data.*
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Detail(val item: VideoItem) : Screen
    data class Player(val detail: VideoDetail) : Screen
}

@Composable
fun XiguaTvApp(
    repository: XiguaRepository,
    readCookie: () -> String,
    saveCookie: (String) -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var screen by remember { mutableStateOf<Screen>(Screen.Home) }
        Surface(Modifier.fillMaxSize(), color = Color(0xFF080A0E)) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(repository, { screen = it }, { screen = Screen.Search }, { screen = Screen.Settings })
                Screen.Search -> SearchScreen(repository, { screen = Screen.Home }, { screen = Screen.Detail(it) })
                Screen.Settings -> SettingsScreen(repository, readCookie, saveCookie) { screen = Screen.Home }
                is Screen.Detail -> DetailScreen(repository, s.item, { screen = Screen.Home }, { screen = Screen.Player(it) })
                is Screen.Player -> PlayerScreen(s.detail) { screen = Screen.Detail(s.detail.item) }
            }
        }
    }
}

@Composable
private fun HomeScreen(repository: XiguaRepository, open: (Screen) -> Unit, search: () -> Unit, settings: () -> Unit) {
    var sections by remember { mutableStateOf<List<VideoSection>>(emptyList()) }
    var selected by remember { mutableStateOf<VideoItem?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        runCatching { repository.home() }.onSuccess { sections = it; selected = it.firstOrNull()?.items?.firstOrNull() }
            .onFailure { error = it.message.orEmpty() }
    }
    Box(Modifier.fillMaxSize()) {
        selected?.coverUrl?.takeIf { it.isNotBlank() }?.let {
            AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xEE080A0E), Color(0xAA080A0E), Color(0x55080A0E)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("西瓜 TV", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(32.dp))
                TvButton("首页") {}
                Spacer(Modifier.width(12.dp)); TvButton("搜索", search)
                Spacer(Modifier.width(12.dp)); TvButton("设置", settings)
            }
            Spacer(Modifier.height(34.dp))
            selected?.let {
                Text(it.title, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                if (it.author.isNotBlank()) Text(it.author, color = Color.LightGray, fontSize = 18.sp)
            }
            Spacer(Modifier.height(26.dp))
            if (sections.isEmpty()) {
                if (error.isBlank()) CircularProgressIndicator() else Text("加载失败：$error")
            } else {
                sections.forEach { section ->
                    Text(section.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(section.items, key = { it.id }) { item ->
                            VideoCard(item, onFocus = { selected = item }) { open(Screen.Detail(item)) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun VideoCard(item: VideoItem, onFocus: () -> Unit, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(220.dp).onKeyEvent {
            if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) { click(); true } else false
        }.border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp)).clickable(click).focusable()
    ) {
        AsyncImage(model = item.coverUrl, contentDescription = item.title, modifier = Modifier.fillMaxWidth().height(124.dp), contentScale = ContentScale.Crop)
        Text(item.title, modifier = Modifier.padding(8.dp), maxLines = 2, fontSize = 15.sp)
    }
    LaunchedEffect(focused) { if (focused) onFocus() }
}

@Composable
private fun SearchScreen(repository: XiguaRepository, back: () -> Unit, open: (VideoItem) -> Unit) {
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { TvButton("返回", back); Spacer(Modifier.width(18.dp)); Text("搜索", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(query, { query = it }, modifier = Modifier.width(620.dp), placeholder = { Text("输入关键词 / 西瓜视频链接 / 视频ID") }, singleLine = true)
            Spacer(Modifier.width(16.dp)); TvButton("搜索") { scope.launch { status = "搜索中..."; runCatching { repository.search(query) }.onSuccess { results = it; status = "${it.size} 条结果" }.onFailure { status = it.message.orEmpty() } } }
        }
        Spacer(Modifier.height(14.dp)); Text(status, color = Color.LightGray)
        Spacer(Modifier.height(18.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) { items(results, key = { it.id }) { VideoCard(it, {}) { open(it) } } }
    }
}

@Composable
private fun DetailScreen(repository: XiguaRepository, seed: VideoItem, back: () -> Unit, play: (VideoDetail) -> Unit) {
    var detail by remember { mutableStateOf<VideoDetail?>(null) }; var error by remember { mutableStateOf("") }
    LaunchedEffect(seed.id) { runCatching { repository.detail(seed) }.onSuccess { detail = it }.onFailure { error = it.message.orEmpty() } }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = detail?.item?.coverUrl ?: seed.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF2080A0E), Color(0xB0080A0E), Color(0x44080A0E)))))
        Column(Modifier.fillMaxHeight().width(760.dp).padding(48.dp), verticalArrangement = Arrangement.Center) {
            Text(detail?.item?.title ?: seed.title, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp)); Text(detail?.item?.description ?: seed.description, color = Color.LightGray, maxLines = 5)
            Spacer(Modifier.height(24.dp))
            Row { TvButton("返回", back); Spacer(Modifier.width(14.dp)); TvButton("播放") { detail?.let(play) } }
            if (detail == null && error.isBlank()) { Spacer(Modifier.height(18.dp)); CircularProgressIndicator() }
            if (error.isNotBlank()) { Spacer(Modifier.height(18.dp)); Text("详情解析失败：$error", color = Color(0xFFFF8A80)) }
            detail?.takeIf { it.alternatives.isEmpty() }?.let { Spacer(Modifier.height(18.dp)); Text("已获取详情，但暂未解析到播放地址", color = Color.Yellow) }
        }
    }
}

@Composable
private fun PlayerScreen(detail: VideoDetail, back: () -> Unit) {
    val context = LocalContext.current; val playback = detail.playback
    if (playback == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("没有可播放地址"); Spacer(Modifier.height(16.dp)); TvButton("返回", back) } }; return }
    val player = remember { ExoPlayer.Builder(context).build() }
    val focus = remember { FocusRequester() }
    DisposableEffect(playback.url) {
        player.setMediaItem(MediaItem.fromUri(playback.url)); player.prepare(); player.playWhenReady = true
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent {
        if (it.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
        when (it.nativeKeyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (player.isPlaying) player.pause() else player.play(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekTo(player.currentPosition + 10000); true }
            KeyEvent.KEYCODE_BACK -> { back(); true }
            else -> false
        }
    }) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SettingsScreen(repository: XiguaRepository, readCookie: () -> String, saveCookie: (String) -> Unit, back: () -> Unit) {
    var cookie by remember { mutableStateOf(readCookie()) }; var status by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row { TvButton("返回", back); Spacer(Modifier.width(18.dp)); Text("设置", fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(28.dp)); Text("西瓜 Cookie（可选）")
        TextField(cookie, { cookie = it }, modifier = Modifier.fillMaxWidth(0.8f), minLines = 3)
        Spacer(Modifier.height(16.dp)); Row { TvButton("保存") { saveCookie(cookie); status = "已保存" }; Spacer(Modifier.width(14.dp)); TvButton("接口自检") { scope.launch { status = "检测中..."; status = repository.diagnose().message } } }
        Spacer(Modifier.height(18.dp)); Text(status)
    }
}

@Composable
private fun TvButton(text: String, action: () -> Unit) {
    Button(onClick = action, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)) { Text(text) }
}
