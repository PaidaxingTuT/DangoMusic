package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics

class SongUrl(val url: String, val trialOnly: Boolean = false)

/** 各平台的统一抽象。界面、播放与歌词均通过具体平台实体调用。 */
abstract class MusicPlatformService {
    abstract val id: String
    abstract val displayName: String
    abstract val trackPlatform: String
    abstract val accentColor: String
    abstract val login: AccountLogin
    abstract val loggedIn: Boolean
    abstract val userId: Long
    abstract val nickname: String
    abstract val avatarUrl: String
    abstract val isVip: Boolean
    open val searchError: String get() = ""

    abstract fun resolveUser(): Long
    abstract fun logout()
    abstract fun userPlaylists(): List<NeteasePlaylist>
    abstract fun recommendPlaylists(): List<NeteasePlaylist>
    abstract fun toplists(): List<NeteasePlaylist>
    abstract fun playlistTracks(playlist: NeteasePlaylist): List<NeteaseTrack>
    abstract fun search(keyword: String, limit: Int = 30): List<NeteaseTrack>
    abstract fun songUrl(track: NeteaseTrack): SongUrl?
    abstract fun fetchLyrics(track: NeteaseTrack): Lyrics?
}
