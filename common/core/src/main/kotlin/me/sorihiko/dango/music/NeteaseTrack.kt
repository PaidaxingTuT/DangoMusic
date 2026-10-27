package me.sorihiko.dango.music

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 音乐音轨数据模型（兼容网易云与酷狗音乐平台）。
 *
 * @param id 唯一 ID（网易云为数字 ID，酷狗可根据 hash 生成哈希 ID）
 * @param name 歌曲名称
 * @param artists 歌手列表
 * @param durationMs 时长（毫秒）
 * @param coverUrl 封面图片 URL
 * @param platform 来源平台 ("NetEase", "KuGou", "KuGou Lite")
 * @param hash 酷狗专有的音频文件 Hash（用于获取播放链接、歌词等）
 * @param albumAudioId 酷狗音轨所属的 album_audio_id
 */
open class NeteaseTrack(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val durationMs: Long,
    val coverUrl: String,
    val platform: String = "NetEase",
    val hash: String = "",
    val albumAudioId: Long = 0L,
) {
    val artistText: String get() = if (artists.isEmpty()) "" else artists.joinToString(" / ")

    companion object {
        /** 解析网易云接口返回的歌曲 JSON */
        fun parse(track: JsonObject): NeteaseTrack? {
            val id = track.get("id")?.let { if (it.isJsonNull) null else it.asString.toLongOrNull() } ?: return null
            val name = str(track, "name")
            val artistsEl: JsonElement? = track.get("artists") ?: track.get("ar")
            val artists = if (artistsEl != null && artistsEl.isJsonArray) {
                artistsEl.asJsonArray.mapNotNull { a ->
                    if (a.isJsonObject) str(a.asJsonObject, "name").takeIf { it.isNotEmpty() } else null
                }
            } else emptyList()
            val dur = (track.get("duration") ?: track.get("dt"))
                ?.let { if (it.isJsonNull) 0L else try { it.asLong } catch (_: Exception) { 0L } } ?: 0L
            val album = track.get("album") ?: track.get("al")
            val cover = if (album != null && album.isJsonObject) {
                str(album.asJsonObject, "picUrl").ifEmpty { str(album.asJsonObject, "cover") }
            } else ""
            return NeteaseTrack(id, name, artists, dur, cover, "NetEase")
        }

        private fun str(o: JsonObject, name: String): String {
            val e = o.get(name) ?: return ""
            return if (e.isJsonNull || !e.isJsonPrimitive) "" else e.asString
        }
    }
}
