package me.sorihiko.dango.music

import me.sorihiko.dango.lyrics.Lyrics

/**
 * 通用音乐服务平台抽象接口。
 * 网易云音乐 (NeteasePlatformService)、酷狗音乐 (KugouPlatformService)、
 * 酷狗概念版 (KugouLitePlatformService) 分别实现该接口。
 */
interface MusicPlatformService {
    /** 平台标识 */
    val id: String
    /** 平台展示名称 */
    val displayName: String

    /** 关联的扫码登录实现 */
    val login: AccountLogin

    /** 是否已登录 */
    val loggedIn: Boolean
    /** 用户 ID */
    val userId: Long
    /** 用户昵称 */
    val nickname: String
    /** 用户头像 URL */
    val avatarUrl: String
    /** 是否拥有 VIP 特权 */
    val isVip: Boolean

    /** 解析/刷新当前登录用户信息 */
    fun resolveUser(): Long

    /** 退出登录并清除当前凭证 */
    fun logout()

    /** 获取用户歌单列表（包括创建与收藏的） */
    fun userPlaylists(): List<NeteasePlaylist>

    /** 获取推荐歌单列表 */
    fun recommendPlaylists(): List<NeteasePlaylist>

    /** 获取排行榜歌单列表 */
    fun toplists(): List<NeteasePlaylist>

    /** 获取指定歌单内的歌曲列表 */
    fun playlistTracks(playlistId: Long): List<NeteaseTrack>

    /** 搜索歌曲 */
    fun search(keyword: String, limit: Int = 30): List<NeteaseTrack>

    /** 获取歌曲可播放直链 */
    fun songUrl(track: NeteaseTrack): String?

    /** 获取该歌曲的逐字/逐行歌词 */
    fun fetchLyrics(track: NeteaseTrack): Lyrics?
}
