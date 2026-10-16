package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.platform.Keys

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** 内置播放器在高版本里的外壳：把原版界面的事件转给共用的 [me.sorihiko.dango.music.MusicScreen] */
class ModernMusicScreen(
    /** 关闭后回到哪个界面（null = 回到游戏） */
    private val parent: Screen?,
) : Screen(Component.literal("Dango Music")) {

    private val screen = DangoClient.newMusicScreen { minecraft?.setScreen(parent) }

    override fun init() {
        screen.opened()
    }

    override fun isPauseScreen() = false

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.flush()   // 先把原版排队的东西画掉，再画我们自己的
        screen.render()
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        screen.mouseClicked(button)
        return true
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        screen.mouseDragged()
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean {
        screen.mouseReleased()
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean {
        // 1.8.9 每格滚轮是 ±120；这里换算成一样的数
        val wheel = (delta * 120).toInt()
        if (wheel != 0) screen.scroll(wheel, ModernPlatform.mouseX, ModernPlatform.mouseY)
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val key = KeyMap.fromGlfw(keyCode)
        if (key != Keys.NONE) screen.keyTyped('\u0000', key)
        return true
    }

    override fun charTyped(c: Char, modifiers: Int): Boolean {
        // 打字单独一个事件（1.8.9 是和按键一起来的）：键码给 NONE
        screen.keyTyped(c, Keys.NONE)
        return true
    }
}
