package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricsProvider

/**
 * 网易云音乐平台服务实现。
 */
object NeteasePlatformService : MusicPlatformService {
    override val id: String = "netease"
    override val displayName: String = "网易云音乐"
    override val login: AccountLogin = NeteaseLogin

    override val loggedIn: Boolean get() = NeteaseApi.loggedIn
    override val userId: Long get() = NeteaseApi.userId
    override val nickname: String get() = NeteaseApi.nickname
    override val avatarUrl: String get() = NeteaseApi.avatarUrl
    override val isVip: Boolean get() = false // 网易云可通过 profile 获取 vipType

    override fun resolveUser(): Long = NeteaseApi.resolveUser()
    override fun logout() = NeteaseApi.logout()

    override fun userPlaylists(): List<NeteasePlaylist> = NeteaseApi.userPlaylists()
    override fun recommendPlaylists(): List<NeteasePlaylist> = NeteaseApi.recommendPlaylists()
    override fun toplists(): List<NeteasePlaylist> = NeteaseApi.toplists()
    override fun playlistTracks(playlist: NeteasePlaylist): List<NeteaseTrack> = NeteaseApi.playlistTracks(playlist.id)
    override fun search(keyword: String, limit: Int): List<NeteaseTrack> = NeteaseApi.search(keyword, limit)

    override fun songUrl(track: NeteaseTrack): String? = NeteaseApi.songUrl(track.id)?.url
    override fun fetchLyrics(track: NeteaseTrack): Lyrics? = LyricsProvider.neteaseLyricById(track.id)
}
