package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.music.KugouLogin

/**
 * 酷狗音乐模块实现。
 */
@ModuleMeta(Category.MUSIC, "KugouMusic")
class KugouMusicModule : BaseMusicModule() {

    override fun onEnable() {
        KugouLogin.userId // 触发凭证检查与初始化
    }
}
