package com.xiguatv.app.data

data class VideoItem(
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val author: String = "",
    val description: String = "",
    val durationSeconds: Long = 0,
    val playCount: String = ""
) {
    val pageUrl: String get() = "https://www.ixigua.com/$id"
}

data class Playback(
    val url: String,
    val label: String = "自动",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0
)

data class VideoDetail(
    val item: VideoItem,
    val playback: Playback? = null,
    val alternatives: List<Playback> = emptyList()
)

data class VideoSection(val title: String, val items: List<VideoItem>)

data class ApiDiagnostic(
    val ok: Boolean,
    val message: String,
    val searchCount: Int = 0,
    val detailParsed: Boolean = false,
    val playbackCount: Int = 0
)
