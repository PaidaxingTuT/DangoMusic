package me.sorihiko.dango.music

/**
 * 酷狗音乐官方标准版服务实现。
 */
object KugouPlatformService : BaseKugouPlatformService(
    id = "kugou",
    displayName = "酷狗音乐",
    isLite = false,
    login = KugouLogin,
)
