package me.sorihiko.dango.modern

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.lwjgl.opengl.GL30

/** 这个版本的 GlStateManager 在 platform 包里 */
internal typealias GlStateManager = com.mojang.blaze3d.platform.GlStateManager

/**
 * 1.20.1 专用的几处写法（同一件事在不同 Minecraft 版本里 API 不一样）。
 * 每个版本在 src/mc<版本>/ 里放一份，构建时按版本选用，对外的样子完全一样。
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

    /** 当前的投影矩阵（[Fixed] 算 MVP 用）：这些版本直接问原版要 */
    fun projection(): Matrix4f = RenderSystem.getProjectionMatrix()

    /** 换成自己的正交投影（画屏幕上的东西用），原版的字也跟着用它 */
    fun pushScreenProjection(m: Matrix4f) {
        RenderSystem.backupProjectionMatrix()
        RenderSystem.setProjectionMatrix(m, VertexSorting.ORTHOGRAPHIC_Z)
    }

    fun popScreenProjection() {
        RenderSystem.restoreProjectionMatrix()
    }

    /** 世界画完、开始画我们的东西之前（这些版本当前帧缓冲就是主画面，不用做什么） */
    fun beginWorld() {}

    /** 绑定帧缓冲（这些版本原版不记帧缓冲，直接绑） */
    fun bindFramebuffer(target: Int, id: Int) {
        GL30.glBindFramebuffer(target, id)
    }

    /** 让原版画一批东西（这些版本原版直接画在当前绑定的帧缓冲上，不用做什么） */
    inline fun vanillaDraw(block: () -> Unit) = block()

    /** 每次画之前（这些版本不用做什么） */
    fun beforeDraw() {}

    fun cameraXRot(): Float = camera().xRot
    fun cameraYRot(): Float = camera().yRot
    fun cameraPosition(): Vec3 = camera().position
    private fun camera() = Minecraft.getInstance().gameRenderer.mainCamera

    fun windowHandle(): Long = Minecraft.getInstance().window.window

    /** 新建一张贴图交给原版管理，[abgr] 是逐行的像素 */
    fun createTexture(name: String, w: Int, h: Int, abgr: IntArray): ModernTexture {
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixelRGBA(x, y, abgr[y * w + x])
        val texture = DynamicTexture(native)
        val manager = Minecraft.getInstance().textureManager
        val location = manager.register(name, texture)
        return ModernTexture({ texture.id }, { manager.release(location) })
    }
}

/** 界面基类：把原版界面的各种事件统一成简单的几个方法（1.20.1 的滚轮事件只有一个方向） */
abstract class CompatScreen(title: Component) : Screen(title) {
    abstract fun onRender()
    abstract fun onMouseClicked(button: Int)
    abstract fun onMouseDragged()
    abstract fun onMouseReleased()
    abstract fun onScroll(delta: Double)
    abstract fun onKeyPressed(glfwKey: Int)
    abstract fun onCharTyped(c: Char)

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.flush()   // 先把原版排队的东西画掉，再画我们自己的
        onRender()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        onMouseDragged()
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        onMouseReleased()
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        onKeyPressed(keyCode)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        onCharTyped(c)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        onScroll(delta)
        return true
    }
}
