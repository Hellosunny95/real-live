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
import androidx.compose.ui.focus.onFocusChanged
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
private fun HomeScreen(
    repository: XiguaRepository,
    open: (Screen) -> Unit,
    search: () -> Unit,
    settings: () -> Unit
) {
    var sections by remember { mutableStateOf<List<VideoSection>>(emptyList()) }
    var selected by remember { mutableStateOf<VideoItem?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var diagnostic by remember { mutableStateOf("") }

    LaunchedEffect(reloadKey) {
        loading = true
        error = ""
        diagnostic = ""
        sections = emptyList()
        selected = null
        runCatching { repository.home() }
            .onSuccess {
                sections = it
                selected = it.firstOrNull()?.items?.firstOrNull()
                if (it.isEmpty()) error = "西瓜接口返回了空首页"
            }
            .onFailure { error = it.message ?: "首页加载失败" }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        selected?.coverUrl?.takeIf { it.isNotBlank() }?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xF2080A0E), Color(0xC0080A0E), Color(0x88080A0E), Color(0x33080A0E))
                )
            )
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("西瓜 TV", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(32.dp))
                TvButton("首页") {}
                Spacer(Modifier.width(12.dp))
                TvButton("搜索", search)
                Spacer(Modifier.width(12.dp))
                TvButton("设置", settings)
            }

            Spacer(Modifier.height(34.dp))
            selected?.let {
                Text(it.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                if (it.author.isNotBlank()) {
                    Text(it.author, color = Color.LightGray, fontSize = 18.sp)
                }
                Spacer(Modifier.height(26.dp))
            }

            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(18.dp))
                        Column {
                            Text("正在连接西瓜视频…", color = Color.White, fontSize = 22.sp)
                            Text("最多等待 12 秒，不会再无限转圈", color = Color.Gray, fontSize = 16.sp)
                        }
                    }
                }

                sections.isNotEmpty() -> {
                    sections.forEach { section ->
                        Text(section.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(section.items, key = { it.id }) { item ->
                                VideoCard(item, onFocus = { selected = item }) {
                                    open(Screen.Detail(item))
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }

                else -> {
                    Column(Modifier.widthIn(max = 900.dp)) {
                        Text("首页数据没有加载出来", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            error.ifBlank { "西瓜当前接口没有返回可解析内容。" },
                            color = Color(0xFFFFB4AB),
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.height(22.dp))
                        Row {
                            TvButton("重新加载") { reloadKey++ }
                            Spacer(Modifier.width(14.dp))
                            TvButton("去搜索", search)
                            Spacer(Modifier.width(14.dp))
                            TvButton("设置 Cookie", settings)
                            Spacer(Modifier.width(14.dp))
                            TvButton("接口自检") {
                                scope.launch {
                                    diagnostic = "检测中…"
                                    diagnostic = runCatching { repository.diagnose().message }
                                        .getOrElse { it.message ?: "自检失败" }
                                }
                            }
                        }
                        if (diagnostic.isNotBlank()) {
                            Spacer(Modifier.height(18.dp))
                            Text("接口状态：$diagnostic", color = Color.LightGray, fontSize = 17.sp)
                        }
                        Spacer(Modifier.height(30.dp))
                        Text("你仍然可以直接进入“搜索”测试关键词或西瓜视频 ID。", color = Color.Gray, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(item: VideoItem, onFocus: () -> Unit, click: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier
            .width(220.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .onKeyEvent {
                if (
                    it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    click()
                    true
                } else false
            }
            .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB316191F))
            .clickable(onClick = click)
            .focusable()
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth().height(124.dp),
            contentScale = ContentScale.Crop
        )
        Text(item.title, color = Color.White, modifier = Modifier.padding(8.dp), maxLines = 2, fontSize = 15.sp)
    }
}

@Composable
private fun SearchScreen(
    repository: XiguaRepository,
    back: () -> Unit,
    open: (VideoItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvButton("返回", back)
            Spacer(Modifier.width(18.dp))
            Text("搜索", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.width(620.dp),
                placeholder = { Text("输入关键词 / 西瓜视频链接 / 视频ID") },
                singleLine = true
            )
            Spacer(Modifier.width(16.dp))
            TvButton(if (searching) "搜索中…" else "搜索") {
                if (!searching) scope.launch {
                    searching = true
                    status = "正在请求西瓜搜索接口…"
                    runCatching { repository.search(query) }
                        .onSuccess {
                            results = it
                            status = if (it.isEmpty()) "接口返回成功，但没有解析到结果" else "${it.size} 条结果"
                        }
                        .onFailure { status = "搜索失败：${it.message ?: "未知错误"}" }
                    searching = false
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(status, color = if (status.startsWith("搜索失败")) Color(0xFFFFB4AB) else Color.LightGray)
        Spacer(Modifier.height(18.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(results, key = { it.id }) { VideoCard(it, {}) { open(it) } }
        }
    }
}

@Composable
private fun DetailScreen(
    repository: XiguaRepository,
    seed: VideoItem,
    back: () -> Unit,
    play: (VideoDetail) -> Unit
) {
    var detail by remember { mutableStateOf<VideoDetail?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(seed.id) {
        loading = true
        runCatching { repository.detail(seed) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "详情解析失败" }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        AsyncImage(
            model = detail?.item?.coverUrl ?: seed.coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xF2080A0E), Color(0xB0080A0E), Color(0x44080A0E))
                )
            )
        )
        Column(
            Modifier.fillMaxHeight().width(760.dp).padding(48.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(detail?.item?.title ?: seed.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(detail?.item?.description ?: seed.description, color = Color.LightGray, maxLines = 5)
            Spacer(Modifier.height(24.dp))
            Row {
                TvButton("返回", back)
                Spacer(Modifier.width(14.dp))
                TvButton("播放") { detail?.let(play) }
            }
            if (loading) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("解析详情与播放地址…", color = Color.LightGray)
                }
            }
            if (!loading && error.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("详情解析失败：$error", color = Color(0xFFFF8A80))
            }
            detail?.takeIf { it.alternatives.isEmpty() }?.let {
                Spacer(Modifier.height(18.dp))
                Text("已获取详情，但暂未解析到播放地址", color = Color.Yellow)
            }
        }
    }
}

@Composable
private fun PlayerScreen(detail: VideoDetail, back: () -> Unit) {
    val context = LocalContext.current
    val playback = detail.playback
    if (playback == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("没有可播放地址", color = Color.White)
                Spacer(Modifier.height(16.dp))
                TvButton("返回", back)
            }
        }
        return
    }

    val player = remember { ExoPlayer.Builder(context).build() }
    val focus = remember { FocusRequester() }
    DisposableEffect(playback.url) {
        player.setMediaItem(MediaItem.fromUri(playback.url))
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent {
                if (it.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (player.isPlaying) player.pause() else player.play()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        player.seekTo(player.currentPosition + 10_000)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        back()
                        true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SettingsScreen(
    repository: XiguaRepository,
    readCookie: () -> String,
    saveCookie: (String) -> Unit,
    back: () -> Unit
) {
    var cookie by remember { mutableStateOf(readCookie()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(48.dp)) {
        Row {
            TvButton("返回", back)
            Spacer(Modifier.width(18.dp))
            Text("设置", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text("西瓜 Cookie（可选）", color = Color.White)
        TextField(
            value = cookie,
            onValueChange = { cookie = it },
            modifier = Modifier.fillMaxWidth(0.8f),
            minLines = 3
        )
        Spacer(Modifier.height(16.dp))
        Row {
            TvButton("保存") {
                saveCookie(cookie)
                status = "已保存"
            }
            Spacer(Modifier.width(14.dp))
            TvButton("接口自检") {
                scope.launch {
                    status = "检测中…"
                    status = runCatching { repository.diagnose().message }
                        .getOrElse { "自检失败：${it.message}" }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(status, color = Color.LightGray)
    }
}

@Composable
private fun TvButton(text: String, action: () -> Unit) {
    Button(
        onClick = action,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(text)
    }
}
