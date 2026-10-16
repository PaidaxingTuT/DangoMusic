package me.sorihiko.dango.modern

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 1.21.1 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用。
 */
internal object McCompat {
    /** 把原版的视图矩阵临时换成单位矩阵（1.20.5 起是 JOML 的 Matrix4fStack） */
    fun pushIdentityModelView() {
        val mv = RenderSystem.getModelViewStack()
        mv.pushMatrix()
        mv.identity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popMatrix()
        RenderSystem.applyModelViewMatrix()
    }
}

/** 界面基类：把滚轮事件统一成"竖直方向滚了几格"（1.20.2 起滚轮事件分横向 / 竖向） */
abstract class CompatScreen(title: Component) : Screen(title) {
    abstract fun onScroll(delta: Double): Boolean

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = onScroll(scrollY)
}
