package com.xiguatv.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

class XiguaRepository(cookieProvider: () -> String) {
    private val api = XiguaApi(cookieProvider)

    suspend fun home(): List<VideoSection> = withTimeout(12_000) {
        supervisorScope {
            val requests = listOf(
                "推荐" to async { runCatching { api.homeFeed(28) }.getOrDefault(emptyList()) },
                "电影" to async { runCatching { api.search("电影") }.getOrDefault(emptyList()) },
                "纪录片" to async { runCatching { api.search("纪录片") }.getOrDefault(emptyList()) },
                "知识" to async { runCatching { api.search("知识") }.getOrDefault(emptyList()) }
            )

            val sections = requests.mapNotNull { (title, request) ->
                request.await().takeIf { it.isNotEmpty() }?.let { VideoSection(title, it) }
            }

            if (sections.isEmpty()) {
                throw XiguaApiException(
                    "HOME_EMPTY",
                    "西瓜接口没有返回首页数据。请重试，或先进入搜索/设置里的接口自检。"
                )
            }
            sections
        }
    }

    suspend fun search(query: String): List<VideoItem> = withTimeout(12_000) {
        api.search(query)
    }

    suspend fun detail(video: VideoItem): VideoDetail = withTimeout(15_000) {
        api.detail(video)
    }

    suspend fun diagnose(): ApiDiagnostic = withTimeout(12_000) {
        api.diagnose()
    }
}
