package com.xiguatv.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class XiguaRepository(cookieProvider: () -> String) {
    private val api = XiguaApi(cookieProvider)

    suspend fun home(): List<VideoSection> = coroutineScope {
        val recommended = async { runCatching { api.homeFeed(36) }.getOrDefault(emptyList()) }
        val categories = listOf("电影", "纪录片", "知识").map { query ->
            query to async { runCatching { api.search(query) }.getOrDefault(emptyList()) }
        }

        buildList {
            recommended.await().takeIf { it.isNotEmpty() }?.let { add(VideoSection("推荐", it)) }
            categories.forEach { (title, request) ->
                request.await().takeIf { it.isNotEmpty() }?.let { add(VideoSection(title, it)) }
            }
        }
    }

    suspend fun search(query: String): List<VideoItem> = api.search(query)
    suspend fun detail(video: VideoItem): VideoDetail = api.detail(video)
    suspend fun diagnose(): ApiDiagnostic = api.diagnose()
}
