package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.events.WorldRenderEndEvent

import net.minecraft.client.Minecraft

/** Fabric / Forge 入口调用：启动和事件转发（两个加载器共用） */
object ModernDango {
    @JvmStatic
    fun start() {
        DangoClient.start(ModernPlatform, ModernGfx)
    }

    /** 每个游戏 tick 开头 */
    @JvmStatic
    fun tick() {
        try {
            ModernGl.init()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music GL init failed: {}", t)
        }
        DangoMusic.callEvent(TickEvent)
    }

    /**
     * 世界画完（手和 HUD 之前）。
     *
     * 镜头转向自己按镜头算，不用加载器事件给的矩阵：Fabric 和 Forge 给的矩阵不一样
     * （Forge 那边会让歌词左右反过来），自己算两边结果一致，和原版画实体用的完全相同。
     * 画的期间把原版的视图矩阵设成单位矩阵，镜头转向只算这一次。
     */
    @JvmStatic
    fun worldRenderEnd() {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        ModernWorld.basePose.identity()
            .rotateX(Math.toRadians(camera.xRot.toDouble()).toFloat())
            .rotateY(Math.toRadians(camera.yRot.toDouble() + 180.0).toFloat())
        McCompat.pushIdentityModelView()
        try {
            // partialTicks 目前没有地方用到，各版本拿它的写法又不一样，就不传了
            DangoMusic.callEvent(WorldRenderEndEvent(0f))
        } finally {
            McCompat.popModelView()
        }
    }
}
