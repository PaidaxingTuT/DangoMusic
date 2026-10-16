package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.platform.Platform
import me.sorihiko.dango.platform.PlayerPose

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW

import java.nio.file.Path

/** 高版本的游戏接口（Fabric 和 Forge 共用）。键码在这里和 GLFW 互相转换，共用代码只看到统一键码 */
object ModernPlatform : Platform {

    private val mc: Minecraft get() = Minecraft.getInstance()

    // .minecraft/config/dangomusic（Fabric 和 Forge 都是这个位置，不依赖任何一边的 API）
    override val configDir: Path get() = mc.gameDirectory.toPath().resolve("config").resolve(DangoMusic.MOD_ID)

    override val playerName: String get() = mc.user.name

    override val windowWidth: Int get() = mc.window.width
    override val windowHeight: Int get() = mc.window.height

    // GLFW 给的是窗口坐标，换算成帧缓冲像素（高 DPI 屏幕上两者不一样）
    override val mouseX: Int
        get() = (mc.mouseHandler.xpos() * mc.window.width / mc.window.screenWidth.coerceAtLeast(1)).toInt()
    override val mouseY: Int
        get() = (mc.mouseHandler.ypos() * mc.window.height / mc.window.screenHeight.coerceAtLeast(1)).toInt()

    override fun isMouseDown(button: Int): Boolean =
        GLFW.glfwGetMouseButton(McCompat.windowHandle(), button) == GLFW.GLFW_PRESS

    override fun isKeyDown(key: Int): Boolean {
        val glfw = KeyMap.toGlfw(key)
        return glfw > 0 && glfwKey(glfw)
    }

    private fun glfwKey(key: Int): Boolean = GLFW.glfwGetKey(McCompat.windowHandle(), key) == GLFW.GLFW_PRESS

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    // Mac 上是 Command 键（和原版 Screen.hasControlDown 一样）
    override val ctrlDown: Boolean
        get() = if (isMac) glfwKey(GLFW.GLFW_KEY_LEFT_SUPER) || glfwKey(GLFW.GLFW_KEY_RIGHT_SUPER)
        else glfwKey(GLFW.GLFW_KEY_LEFT_CONTROL) || glfwKey(GLFW.GLFW_KEY_RIGHT_CONTROL)

    override var clipboard: String
        get() = mc.keyboardHandler.clipboard
        set(value) {
            mc.keyboardHandler.clipboard = value
        }

    override val inGame: Boolean get() = mc.screen == null && mc.level != null

    override val chatOpen: Boolean get() = mc.screen is ChatScreen

    override val guiScale: Int get() = mc.window.guiScale.toInt()

    override val fps: Int get() = mc.fps

    override val fov: Float get() = mc.options.fov().get().toFloat()

    override val pingMs: Int
        get() {
            val p = mc.player ?: return 0
            if (mc.hasSingleplayerServer()) return 0
            return mc.connection?.getPlayerInfo(p.uuid)?.latency ?: 0
        }

    override val player: PlayerPose?
        get() {
            val p = mc.player ?: return null
            return PlayerPose(p.x, p.eyeY, p.z, p.yRot, p.xRot)
        }

    override fun openMusicScreen() {
        mc.setScreen(ModernMusicScreen(null))
    }
}
