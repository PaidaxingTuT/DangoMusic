package me.sorihiko.dango.music

/**
 * 酷狗音乐概念版（Lite）扫码登录实现。
 * AppId: 3116, ClientVer: 11440
 */
object KugouLiteLogin : BaseKugouLogin(
    appid = 3116,
    clientver = 11440,
    platformName = "酷狗概念版",
    cookieFileName = "kugou_lite_credentials.txt",
)
