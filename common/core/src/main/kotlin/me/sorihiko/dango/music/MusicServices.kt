package me.sorihiko.dango.music

/**
 * 音乐服务平台注册与调度中心。
 * 统一管理当前激活的音乐服务实例。
 */
object MusicServices {

    val NETEASE: MusicPlatformService = NeteasePlatformService
    val KUGOU: MusicPlatformService = KugouPlatformService
    val KUGOU_LITE: MusicPlatformService = KugouLitePlatformService

    val all: List<MusicPlatformService> = listOf(NETEASE, KUGOU, KUGOU_LITE)

    /** 当前选中的音乐平台服务，默认网易云 */
    @Volatile
    var current: MusicPlatformService = NETEASE
        private set

    /** 在客户端和配置目录初始化后调用，仅检查本地登录凭证。 */
    fun selectStartupPlatform() {
        current = all.firstOrNull { runCatching { it.loggedIn }.getOrDefault(false) } ?: NETEASE
    }

    fun select(service: MusicPlatformService) {
        current = service
    }

    fun forTrack(track: NeteaseTrack): MusicPlatformService =
        all.firstOrNull { it.trackPlatform == track.platform }
            ?: error("Unknown music platform: ${track.platform}")

    fun forLogin(login: AccountLogin): MusicPlatformService =
        all.first { it.login === login }

    fun selectById(id: String) {
        current = all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: NETEASE
    }
}
