package me.sorihiko.dango.music

import com.google.gson.JsonObject

/** Concept has its own source identity and account while reusing the Kugou protocol base. */
object KugouConceptMusicSource : MusicSourceApi {
    override val source = MusicSource.KUGOU_CONCEPT
    private val catalog = KugouCatalog(source)

    override fun playlists() = catalog.playlists()
    override fun recommend(): List<NeteasePlaylist> {
        val special = runCatching { catalog.specialRecommend() }.getOrDefault(emptyList())
        return conceptRecommend() + special.ifEmpty { catalog.recommend() }
    }
    override fun charts() = catalog.charts()
    override fun playlistTracks(id: Long) = catalog.tracks(id)
    override fun search(query: String): List<NeteaseTrack> {
        val root = KugouConcept.request("/v2/search/song", mapOf(
            "keyword" to query, "page" to "1", "pagesize" to "40", "platform" to "AndroidFilter",
            "iscorrection" to "1", "privilegefilter" to "0", "area_code" to "1",
            "dopicfull" to "1", "clientver" to "201",
        ), null, NativeAccounts.get(source))
        val list = root.array("lists").takeIf { it.size() > 0 } ?: root.array("list")
        return list.mapNotNull { item ->
            if (item.isJsonObject) KugouMusicSource.parseTrack(item.asJsonObject, source) else null
        }.distinctBy { it.key }
    }
    override fun songUrl(track: NeteaseTrack) = KugouConcept.songUrl(track)
    override fun lyrics(track: NeteaseTrack) = KugouMusicSource.lyrics(track)

    private fun conceptRecommend(): List<NeteasePlaylist> {
        val data = KugouConcept.request("/concepts/v1/ai/recommend_song", emptyMap(), JsonObject().apply {
            addProperty("apiver", 2); addProperty("source", 2); addProperty("pagesize", 30); addProperty("page", 1)
        }, null)
        val tracks = (data.array("song_list").takeIf { it.size() > 0 } ?: data.array("list").takeIf { it.size() > 0 } ?: data.array("info")).mapNotNull { item ->
            if (item.isJsonObject) KugouMusicSource.parseTrack(item.asJsonObject, source) else null
        }
        if (tracks.isEmpty()) throw SourceAccessException("Kugou recommendations are unavailable. Please retry.")
        val id = MusicSources.stableId(source, "concept:recommend")
        catalog.cacheRecommendations(id, tracks)
        return listOf(NeteasePlaylist(id, "概念版推荐", tracks.size, tracks.first().coverUrl, false))
    }
}
