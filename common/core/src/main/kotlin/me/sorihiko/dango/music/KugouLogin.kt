package me.sorihiko.dango.music

/**
 * 酷狗音乐官方版扫码登录实现。
 * AppId: 1005, ClientVer: 20489
 */
object KugouLogin : BaseKugouLogin(
    appid = 1005,
    clientver = 20489,
    platformName = "酷狗音乐",
    cookieFileName = "kugou_credentials.txt",
)
