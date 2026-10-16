package me.sorihiko.dango.modern

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 1.20.1 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用。
 */
internal object McCompat {
    /** 把原版的视图矩阵临时换成单位矩阵（1.20.1：PoseStack） */
    fun pushIdentityModelView() {
        val mv = RenderSystem.getModelViewStack()
        mv.pushPose()
        mv.setIdentity()
        RenderSystem.applyModelViewMatrix()
    }

    fun popModelView() {
        RenderSystem.getModelViewStack().popPose()
        RenderSystem.applyModelViewMatrix()
    }
}

/** 界面基类：把滚轮事件统一成"竖直方向滚了几格"（1.20.1 的滚轮事件只有一个方向） */
abstract class CompatScreen(title: Component) : Screen(title) {
    abstract fun onScroll(delta: Double): Boolean

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean = onScroll(delta)
}
