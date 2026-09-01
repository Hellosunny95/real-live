package com.xiguatv.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class XiguaApi(private val cookieProvider: () -> String) {
    private val base = "https://www.ixigua.com"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126 Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .build()

    suspend fun homeFeed(limit: Int = 30): List<VideoItem> = withContext(Dispatchers.IO) {
        val queries = listOf("热门", "电影", "纪录片")
        queries.flatMap { runCatching { searchSync(it) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }.take(limit)
    }

    suspend fun search(query: String, offset: Int = 0): List<VideoItem> = withContext(Dispatchers.IO) {
        searchSync(query, offset)
    }

    suspend fun detail(video: VideoItem): VideoDetail = withContext(Dispatchers.IO) {
        val html = execute(video.pageUrl, "text/html,*/*", base)
        val root = hydrated(html) ?: throw XiguaApiException("DETAIL", "未找到西瓜页面数据")
        val playbacks = LinkedHashMap<String, Playback>()
        walk(root) { obj ->
            val raw = first(obj, "main_url", "mainUrl", "play_url")
            if (raw.isNotBlank()) {
                val ptk = first(obj, "ptk")
                decodeUrl(raw, ptk)?.let { url ->
                    if (url.startsWith("http")) {
                        val h = long(obj, "vheight", "height").toInt()
                        val label = first(obj, "definition", "quality", "gear_name").ifBlank {
                            if (h > 0) "${h}P" else "自动"
                        }
                        playbacks[url] = Playback(url, label, height = h)
                    }
                }
            }
        }
        val title = findString(root, "title", "videoTitle").ifBlank { video.title }
        val cover = findString(root, "poster_url", "cover_url", "image_url").ifBlank { video.coverUrl }
        val item = video.copy(title = title, coverUrl = normalize(cover))
        val list = playbacks.values.toList().sortedByDescending { it.height }
        VideoDetail(item, list.firstOrNull(), list)
    }

    suspend fun diagnose(): ApiDiagnostic = withContext(Dispatchers.IO) {
        runCatching {
            val items = searchSync("纪录片")
            ApiDiagnostic(items.isNotEmpty(), "搜索接口正常，返回 ${items.size} 条", items.size)
        }.getOrElse { ApiDiagnostic(false, it.message ?: "接口错误") }
    }

    private fun searchSync(query: String, offset: Int = 0): List<VideoItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val direct = Regex("(?:ixigua\\.com/)?(?:video/)?(\\d{15,22})").find(q)?.groupValues?.getOrNull(1)
        if (direct != null) return listOf(VideoItem(direct, "视频 $direct"))
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url = "$base/api/searchv2/complex/$encoded/$offset".toHttpUrl().newBuilder()
            .addQueryParameter("aid", "1768")
            .addQueryParameter("msToken", "")
            .addQueryParameter("X-Bogus", "")
            .addQueryParameter("_signature", "")
            .build().toString()
        val text = execute(url, "application/json,text/plain,*/*", "$base/search/$encoded/")
        val root = JSONObject(text)
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { obj ->
            val id = listOf("group_id", "groupId", "item_id", "itemId", "video_id", "videoId")
                .firstNotNullOfOrNull { key -> digits(obj.opt(key)) } ?: return@walk
            if (out.containsKey(id)) return@walk
            val title = findLocal(obj, "title", "video_title", "videoTitle").replace(Regex("<[^>]+>"), "").trim()
            if (title.length < 2) return@walk
            val cover = findImage(obj)
            val author = findLocal(obj, "author_name", "nickname", "user_name")
            out[id] = VideoItem(id, title, cover, author)
        }
        return out.values.filter { it.coverUrl.isNotBlank() }.take(40).ifEmpty { out.values.take(40) }
    }

    private fun execute(url: String, accept: String, referer: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua).header("Accept", accept).header("Referer", referer)
            .apply { cookieProvider().trim().takeIf { it.isNotEmpty() }?.let { header("Cookie", it) } }
            .build()
        client.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw XiguaApiException("HTTP", "HTTP ${r.code}")
            if (body.isBlank()) throw XiguaApiException("EMPTY", "接口返回空内容")
            return body
        }
    }

    private fun hydrated(html: String): JSONObject? {
        val marker = "window._SSR_HYDRATED_DATA"
        val m = html.indexOf(marker); if (m < 0) return null
        val start = html.indexOf('{', html.indexOf('=', m)); if (start < 0) return null
        var depth = 0; var quoted = false; var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') quoted = false; continue }
            if (c == '"') quoted = true else if (c == '{') depth++ else if (c == '}') {
                depth--; if (depth == 0) return runCatching { JSONObject(html.substring(start, i + 1).replace(Regex("\\bundefined\\b"), "null")) }.getOrNull()
            }
        }
        return null
    }

    private fun walk(v: Any?, visit: (JSONObject) -> Unit) {
        when (v) {
            is JSONObject -> { visit(v); v.keys().forEach { walk(v.opt(it), visit) } }
            is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), visit)
        }
    }

    private fun digits(v: Any?): String? {
        val s = when (v) { is Number -> v.toLong().toString(); is String -> v.filter(Char::isDigit); else -> "" }
        return s.takeIf { it.length in 15..22 }
    }
    private fun first(o: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } } ?: ""
    private fun long(o: JSONObject, vararg keys: String): Long = keys.firstNotNullOfOrNull { k -> o.opt(k)?.toString()?.toLongOrNull() } ?: 0
    private fun findLocal(o: JSONObject, vararg keys: String): String = first(o, *keys)
    private fun findString(root: Any?, vararg keys: String): String { var result = ""; walk(root) { if (result.isBlank()) result = first(it, *keys) }; return result }
    private fun findImage(o: JSONObject): String {
        for (k in listOf("large_image_url", "image_url", "poster_url", "cover_url", "coverUrl")) normalize(o.optString(k)).takeIf { it.isNotBlank() }?.let { return it }
        walk(o) { child -> if (child !== o) normalize(first(child, "url", "image_url")).takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }
    private fun normalize(raw: String): String = raw.trim().replace("\\u002F", "/").let { if (it.startsWith("//")) "https:$it" else it }

    private fun decodeUrl(raw: String, ptk: String): String? {
        val v = normalize(raw); if (v.startsWith("http")) return v
        if (ptk.toByteArray(StandardCharsets.UTF_8).size in setOf(16, 24, 32)) runCatching {
            val key = ptk.toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
            val stage = String(cipher.doFinal(Base64.decode(v, Base64.DEFAULT)), StandardCharsets.UTF_8).trim()
            return runCatching { String(Base64.decode(stage, Base64.DEFAULT), StandardCharsets.UTF_8).trim() }.getOrDefault(stage)
        }
        return runCatching { String(Base64.decode(v, Base64.DEFAULT), StandardCharsets.UTF_8).trim() }.getOrNull()
    }
}

class XiguaApiException(val code: String, message: String) : IllegalStateException("[$code] $message")
