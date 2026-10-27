package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics

/**
 * 酷狗音乐基础平台服务抽象（酷狗标准版和概念版共用大部分接口协议与解析）。
 */
abstract class BaseKugouPlatformService(
    override val id: String,
    override val displayName: String,
    val isLite: Boolean,
    override val login: BaseKugouLogin,
) : MusicPlatformService {

    override val loggedIn: Boolean get() = login.token.isNotEmpty()
    override val userId: Long get() = login.userId
    @Volatile override var nickname: String = ""
        protected set
    @Volatile override var avatarUrl: String = ""
        protected set
    @Volatile override var isVip: Boolean = false
        protected set

    override fun resolveUser(): Long {
        if (!loggedIn) return 0L
        if (nickname.isNotEmpty()) return userId
        // 获取用户资料
        val info = KugouApi.getUserInfo(userId, login.token, isLite)
        if (info != null) {
            nickname = info.nickname
            avatarUrl = info.avatar
            isVip = info.isVip
        } else {
            nickname = "酷狗用户_${userId % 10000}"
        }
        return userId
    }

    override fun logout() {
        login.logout()
        nickname = ""
        avatarUrl = ""
        isVip = false
    }

    override fun userPlaylists(): List<NeteasePlaylist> {
        if (!loggedIn) return emptyList()
        return KugouApi.getUserPlaylists(userId, login.token, isLite)
    }

    override fun recommendPlaylists(): List<NeteasePlaylist> = KugouApi.recommendPlaylists(isLite)
    override fun toplists(): List<NeteasePlaylist> = KugouApi.recommendPlaylists(isLite)

    override fun playlistTracks(playlistId: Long): List<NeteaseTrack> {
        return KugouApi.getPlaylistTracks(playlistId, userId, login.token, isLite)
    }

    override fun search(keyword: String, limit: Int): List<NeteaseTrack> {
        return KugouApi.search(keyword, isLite, page = 1, pageSize = limit)
    }

    override fun songUrl(track: NeteaseTrack): String? = KugouApi.songUrl(track)

    override fun fetchLyrics(track: NeteaseTrack): Lyrics? = KugouApi.fetchLyrics(track)
}
