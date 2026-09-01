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
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun homeFeed(limit: Int = 30): List<VideoItem> = withContext(Dispatchers.IO) {
        listOf("热门", "电影", "纪录片")
            .flatMap { runCatching { searchSync(it) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .take(limit)
    }

    suspend fun search(query: String, offset: Int = 0): List<VideoItem> = withContext(Dispatchers.IO) {
        searchSync(query, offset)
    }

    suspend fun detail(video: VideoItem): VideoDetail = withContext(Dispatchers.IO) {
        val html = execute(video.pageUrl, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", base, false)
        val root = hydrated(html) ?: throw XiguaApiException("DETAIL", "未找到西瓜页面数据")
        val playbacks = LinkedHashMap<String, Playback>()
        walk(root) { obj ->
            val raw = first(obj, "main_url", "mainUrl", "play_url")
            if (raw.isNotBlank()) {
                decodeUrl(raw, first(obj, "ptk"))?.let { url ->
                    if (url.startsWith("http")) {
                        val height = long(obj, "vheight", "height").toInt()
                        val label = first(obj, "definition", "quality", "gear_name")
                            .ifBlank { if (height > 0) "${height}P" else "自动" }
                        playbacks[url] = Playback(url, label, height = height)
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
            if (items.isEmpty()) ApiDiagnostic(false, "搜索接口可访问，但没有解析到结果")
            else ApiDiagnostic(true, "搜索接口正常，返回 ${items.size} 条", items.size)
        }.getOrElse { ApiDiagnostic(false, it.message ?: "接口错误") }
    }

    private fun searchSync(query: String, offset: Int = 0): List<VideoItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val direct = Regex("(?:ixigua\\.com/)?(?:video/)?(\\d{15,22})")
            .find(q)?.groupValues?.getOrNull(1)
        if (direct != null) return listOf(VideoItem(direct, "视频 $direct"))

        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url = "$base/api/searchv2/complex/$encoded/$offset".toHttpUrl().newBuilder()
            .addQueryParameter("search_id", "")
            .addQueryParameter("fss", "default_search")
            .addQueryParameter("aid", "1768")
            .addQueryParameter("msToken", "")
            .addQueryParameter("X-Bogus", "")
            .addQueryParameter("_signature", "")
            .build().toString()

        val root = JSONObject(execute(url, "application/json, text/plain, */*", "$base/search/$encoded/", true))
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { obj ->
            val id = listOf("group_id", "groupId", "item_id", "itemId", "video_id", "videoId")
                .firstNotNullOfOrNull { key -> digits(obj.opt(key)) } ?: return@walk
            if (out.containsKey(id)) return@walk
            val title = findLocal(obj, "title", "video_title", "videoTitle")
                .replace(Regex("<[^>]+>"), "").trim()
            if (title.length < 2) return@walk
            out[id] = VideoItem(
                id = id,
                title = title,
                coverUrl = findImage(obj),
                author = findLocal(obj, "author_name", "nickname", "user_name")
            )
        }
        return out.values.filter { it.coverUrl.isNotBlank() }.take(40)
            .ifEmpty { out.values.take(40) }
    }

    private fun execute(url: String, accept: String, referer: String, cors: Boolean): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", accept)
            .header("Referer", referer)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Microsoft Edge\";v=\"126\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
        if (cors) {
            builder.header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .header("x-secsdk-csrf-token", "")
        } else {
            builder.header("sec-fetch-dest", "document")
                .header("sec-fetch-mode", "navigate")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-user", "?1")
                .header("upgrade-insecure-requests", "1")
        }
        cookieProvider().trim().takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw XiguaApiException("HTTP", "HTTP ${response.code}")
            if (body.isBlank()) throw XiguaApiException("EMPTY", "接口返回空内容")
            return body
        }
    }

    private fun hydrated(html: String): JSONObject? {
        val marker = "window._SSR_HYDRATED_DATA"
        val markerPos = html.indexOf(marker)
        if (markerPos < 0) return null
        val equalsPos = html.indexOf('=', markerPos)
        val start = html.indexOf('{', equalsPos)
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val json = html.substring(start, i + 1).replace(Regex("\\bundefined\\b"), "null")
                        return runCatching { JSONObject(json) }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun walk(value: Any?, visit: (JSONObject) -> Unit) {
        when (value) {
            is JSONObject -> {
                visit(value)
                val keys = value.keys()
                while (keys.hasNext()) walk(value.opt(keys.next()), visit)
            }
            is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), visit)
        }
    }

    private fun digits(value: Any?): String? {
        val s = when (value) {
            is Number -> value.toLong().toString()
            is String -> value.filter(Char::isDigit)
            else -> ""
        }
        return s.takeIf { it.length in 15..22 }
    }

    private fun first(obj: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> obj.optString(key).takeIf { it.isNotBlank() } } ?: ""

    private fun long(obj: JSONObject, vararg keys: String): Long =
        keys.firstNotNullOfOrNull { key -> obj.opt(key)?.toString()?.toLongOrNull() } ?: 0

    private fun findLocal(obj: JSONObject, vararg keys: String): String = first(obj, *keys)

    private fun findString(root: Any?, vararg keys: String): String {
        var result = ""
        walk(root) { if (result.isBlank()) result = first(it, *keys) }
        return result
    }

    private fun findImage(obj: JSONObject): String {
        for (key in listOf("large_image_url", "image_url", "poster_url", "cover_url", "coverUrl")) {
            val candidate = normalize(obj.optString(key))
            if (candidate.isNotBlank()) return candidate
        }
        return findNestedImage(obj)
    }

    private fun findNestedImage(value: Any?): String {
        when (value) {
            is JSONObject -> {
                val direct = normalize(first(value, "url", "image_url"))
                if (direct.startsWith("http")) return direct
                val keys = value.keys()
                while (keys.hasNext()) {
                    val found = findNestedImage(value.opt(keys.next()))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> for (i in 0 until value.length()) {
                val found = findNestedImage(value.opt(i))
                if (found.isNotBlank()) return found
            }
        }
        return ""
    }

    private fun normalize(raw: String): String = raw.trim()
        .replace("\\u002F", "/")
        .let { if (it.startsWith("//")) "https:$it" else it }

    private fun decodeUrl(raw: String, ptk: String): String? {
        val value = normalize(raw)
        if (value.startsWith("http")) return value
        val key = ptk.toByteArray(StandardCharsets.UTF_8)
        if (key.size in setOf(16, 24, 32)) {
            val decrypted = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOfRange(0, 16)))
                String(cipher.doFinal(Base64.decode(value, Base64.DEFAULT)), StandardCharsets.UTF_8).trim()
            }.getOrNull()
            if (!decrypted.isNullOrBlank()) {
                return runCatching { String(Base64.decode(decrypted, Base64.DEFAULT), StandardCharsets.UTF_8).trim() }
                    .getOrDefault(decrypted)
            }
        }
        return runCatching { String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8).trim() }.getOrNull()
    }
}

class XiguaApiException(val code: String, message: String) : IllegalStateException("[$code] $message")
