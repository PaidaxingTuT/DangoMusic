package me.sorihiko.dango.music

/**
 * 酷狗音乐概念版（Lite）服务实现。
 */
object KugouLitePlatformService : BaseKugouPlatformService(
    id = "kugou_lite",
    displayName = "酷狗概念版",
    api = KugouLiteApi,
)
