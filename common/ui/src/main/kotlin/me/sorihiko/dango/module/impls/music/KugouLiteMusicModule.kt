package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.music.KugouLiteLogin

/**
 * 酷狗概念版音乐模块实现。
 */
@ModuleMeta(Category.MUSIC, "KugouLiteMusic")
class KugouLiteMusicModule : BaseMusicModule() {

    override fun onEnable() {
        KugouLiteLogin.userId // 触发凭证检查与初始化
    }
}
