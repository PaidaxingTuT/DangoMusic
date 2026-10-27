package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics

/** 酷狗标准版与概念版共用账号状态及业务逻辑，具体实体提供各自 API。 */
abstract class BaseKugouPlatformService(
    override val id: String,
    override val displayName: String,
    private val api: BaseKugouApi,
) : MusicPlatformService() {
    override val login: BaseKugouLogin get() = api.login
    override val trackPlatform: String get() = api.trackPlatform
    override val accentColor = "#33A3F5"
    override val loggedIn: Boolean get() = login.credentialsReady()
    override val userId: Long get() { login.credentialsReady(); return login.userId }

    private class Profile(val userId: Long, val token: String, val info: KugouUserInfo)
    @Volatile private var profile: Profile? = null
    private val info: KugouUserInfo?
        get() = profile?.takeIf { it.userId == userId && it.token == login.token }?.info
    override val nickname: String get() = info?.nickname ?: ""
    override val avatarUrl: String get() = info?.avatar ?: ""
    override val isVip: Boolean get() = info?.isVip ?: false

    override fun resolveUser(): Long {
        if (!loggedIn) return 0L
        if (info != null) return userId
        val uid = userId
        val token = login.token
        val loaded = api.getUserInfo(uid, token)
        if (uid == userId && token == login.token) profile = Profile(uid, token, loaded)
        return uid
    }

    override fun logout() {
        login.logout()
        profile = null
    }

    override fun userPlaylists(): List<NeteasePlaylist> =
        if (loggedIn) api.getUserPlaylists(userId, login.token) else emptyList()
    override fun recommendPlaylists(): List<NeteasePlaylist> = api.recommendPlaylists()
    override fun toplists(): List<NeteasePlaylist> = api.recommendPlaylists()
    override fun playlistTracks(playlist: NeteasePlaylist): List<NeteaseTrack> =
        if (playlist.kind == PlaylistKind.SPECIAL) api.getSpecialTracks(playlist.id)
        else api.getPlaylistTracks(playlist.id, userId, login.token)
    override fun search(keyword: String, limit: Int): List<NeteaseTrack> = api.search(keyword, page = 1, pageSize = limit)
    override fun songUrl(track: NeteaseTrack): SongUrl? = api.songUrl(track)
    override fun fetchLyrics(track: NeteaseTrack): Lyrics? = api.fetchLyrics(track)
}
