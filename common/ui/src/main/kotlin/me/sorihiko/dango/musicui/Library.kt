package me.sorihiko.dango.musicui

import me.sorihiko.dango.gfx.GuiCanvas
import me.sorihiko.dango.music.MusicPlatformService
import me.sorihiko.dango.music.MusicServices
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal enum class Page { HOME, CHARTS, PLAYLIST, SEARCH, QUEUE, PARTY, SETTINGS }

/** 关掉播放器再打开时还保留的界面状态 */
internal object ScreenState {
    var page = Page.HOME
    /** 设置页：0 = Lyrics，1 = Music & HUD；以及从哪一页点进来的 */
    var settingsTab = 0
    var settingsBack = Page.HOME
    var backPage: Page? = null
    var opened: NeteasePlaylist? = null
    /** 歌单页显示方式：false = 列表，true = 封面墙 */
    var coverView = false
    var lastQuery = ""

    // 液态玻璃画布（和菜单同一套），第一次打开播放器时创建
    var context: GuiCanvas? = null
}

/** 音乐平台数据。每次切换平台或账号都替换整份数据，旧请求只能写回旧数据。 */
internal object Library {
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "Dango-MusicUI").apply { isDaemon = true } }

    private class Data(val service: MusicPlatformService) {
        @Volatile var playlists: List<NeteasePlaylist> = emptyList()
        @Volatile var playlistsLoading = false
        @Volatile var status = ""
        val tracksCache = ConcurrentHashMap<String, List<NeteaseTrack>>()
        val tracksLoading = ConcurrentHashMap.newKeySet<String>()
        val loadedPlaylists = ConcurrentHashMap<String, NeteasePlaylist>()
        @Volatile var recommend: List<NeteasePlaylist>? = null
        @Volatile var recommendLoading = false
        @Volatile var charts: List<NeteasePlaylist>? = null
        @Volatile var chartsLoading = false
        @Volatile var searchResults: List<NeteaseTrack>? = null
        @Volatile var searching = false
        @Volatile var searchVersion = 0
        @Volatile var lastLoadAt = 0L
    }

    @Volatile private var data = Data(MusicServices.current)
    val playlists get() = data.playlists
    val playlistsLoading get() = data.playlistsLoading
    val status get() = data.status
    val recommend get() = data.recommend
    val recommendLoading get() = data.recommendLoading
    val charts get() = data.charts
    val chartsLoading get() = data.chartsLoading
    val searchResults get() = data.searchResults
    val searching get() = data.searching

    private const val STALE_MS = 5 * 60_000L
    val stale: Boolean get() = System.currentTimeMillis() - data.lastLoadAt > STALE_MS

    fun tracks(playlist: NeteasePlaylist): List<NeteaseTrack>? = data.tracksCache[playlist.cacheKey]

    fun reloadPlaylists() {
        val d = data
        if (d.playlistsLoading) return
        d.playlistsLoading = true
        d.lastLoadAt = System.currentTimeMillis()
        d.status = "正在读取歌单…"
        worker.execute {
            try {
                val profileLoaded = runCatching { d.service.resolveUser() }.isSuccess
                val list = d.service.userPlaylists()
                d.playlists = list
                d.status = when {
                    list.isEmpty() -> "没有读到歌单（检查网络或登录）"
                    !profileLoaded -> "读取账号信息失败（请重试或重新登录）"
                    else -> ""
                }
            } catch (_: Exception) {
                d.status = "读取歌单失败"
            } finally {
                d.playlistsLoading = false
            }
        }
    }

    fun loadRecommend() {
        val d = data
        if (d.recommend != null || d.recommendLoading) return
        d.recommendLoading = true
        worker.execute {
            d.recommend = try { d.service.recommendPlaylists() } catch (_: Exception) { emptyList() }
            d.recommendLoading = false
        }
    }

    fun loadCharts() {
        val d = data
        if (d.charts != null || d.chartsLoading) return
        d.chartsLoading = true
        worker.execute {
            d.charts = try { d.service.toplists() } catch (_: Exception) { emptyList() }
            d.chartsLoading = false
        }
    }

    /** 读一个歌单里的歌（已经读过或正在读就跳过）。来源类型也是缓存键的一部分。 */
    fun loadTracks(playlist: NeteasePlaylist) {
        val d = data
        val key = playlist.cacheKey
        if (d.tracksCache.containsKey(key) || !d.tracksLoading.add(key)) return
        d.loadedPlaylists[key] = playlist
        worker.execute {
            try {
                d.tracksCache[key] = d.service.playlistTracks(playlist)
            } catch (_: Exception) {
                d.status = "读取歌单失败"
            } finally {
                d.tracksLoading.remove(key)
            }
        }
    }

    fun search(q: String) {
        val d = data
        val version = ++d.searchVersion
        d.searching = true
        d.searchResults = null
        worker.execute {
            val results = try { d.service.search(q) } catch (_: Exception) { emptyList() }
            synchronized(d) {
                if (version == d.searchVersion) {
                    d.searchResults = results
                    d.searching = false
                }
            }
        }
    }

    /** 后台刷新我的歌单和已打开的歌单，界面继续显示已有数据。 */
    fun softRefresh() {
        val d = data
        d.lastLoadAt = System.currentTimeMillis()
        worker.execute {
            try {
                runCatching { d.service.resolveUser() }
                d.playlists = d.service.userPlaylists()
            } catch (_: Exception) {
            }
            for ((key, playlist) in d.loadedPlaylists) {
                if (d !== data) return@execute
                try {
                    d.tracksCache[key] = d.service.playlistTracks(playlist)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 登录 / 退出 / 切换平台后全部重新读。旧任务不会改动新数据或加载标记。 */
    fun refreshAll() {
        data = Data(MusicServices.current)
        ScreenState.opened = null
        ScreenState.backPage = null
        ScreenState.lastQuery = ""
        ScreenState.settingsBack = Page.HOME
        if (ScreenState.page == Page.PLAYLIST || ScreenState.page == Page.SEARCH) ScreenState.page = Page.HOME
        reloadPlaylists()
        loadRecommend()
        if (ScreenState.page == Page.CHARTS) loadCharts()
    }
}
