package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.music.NeteaseApi

/**
 * 网易云音乐模块实现。
 */
@ModuleMeta(Category.MUSIC, "NeteaseMusic")
class NeteaseMusicModule : BaseMusicModule() {

    override fun onEnable() {
        NeteaseApi.ensureCookieFile()
    }
}
