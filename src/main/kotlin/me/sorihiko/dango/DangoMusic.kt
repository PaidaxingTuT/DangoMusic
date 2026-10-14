package me.sorihiko.dango

import me.sorihiko.dango.config.ConfigManager
import me.sorihiko.dango.event.Event
import me.sorihiko.dango.event.EventManager
import me.sorihiko.dango.font.FontManager
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.module.impls.music.DynamicIslandModule
import me.sorihiko.dango.module.impls.music.LyricsModule
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.module.impls.music.SongCardModule
import me.sorihiko.dango.shader.ShaderManager
import me.sorihiko.dango.shader.impls.QuadShader
import me.sorihiko.dango.shader.impls.glow.GlowCompositeShader
import me.sorihiko.dango.shader.impls.glow.GlowDownsampleShader
import me.sorihiko.dango.shader.impls.glow.GlowUpsampleShader
import me.sorihiko.dango.shader.impls.ui.FontShader
import me.sorihiko.dango.shader.impls.ui.GlassBlurShader
import me.sorihiko.dango.shader.impls.ui.GlassShader
import me.sorihiko.dango.shader.impls.ui.ImageShader
import me.sorihiko.dango.shader.impls.ui.ShapeShader

import net.minecraft.client.Minecraft

import java.nio.file.Path

lateinit var mc: Minecraft

/**
 * Dango Music：独立的 Forge 音乐 mod（内置网易云播放器 + 歌词 + Song card + 灵动岛 + 一起听歌）。
 * 结构沿用 ImperialSeal：自己的事件系统 + 模块 + 设置，Forge 事件由 [me.sorihiko.dango.forge.ForgeBridge] 转进来。
 */
class DangoMusic private constructor() {
    companion object {
        const val MOD_ID = "dangomusic"
        const val NAME = "Dango Music"
        const val VERSION = "1.0.0"

        lateinit var instance: DangoMusic
            private set

        @JvmStatic
        fun start() {
            mc = Minecraft.getMinecraft()
            instance = DangoMusic()
            instance.init()
        }

        @JvmStatic
        fun callEvent(event: Event) {
            if (::instance.isInitialized) instance.eventManager.callEvent(event)
        }
    }

    /** 配置、登录凭证、缓存都放在 .minecraft/config/dangomusic/ */
    val clientDir: Path = mc.mcDataDir.toPath().resolve("config").resolve(MOD_ID)

    val eventManager = EventManager()
    val moduleManager = ModuleManager(eventManager)
    val shaderManager = ShaderManager(eventManager)
    val fontManager = FontManager()
    val configManager = ConfigManager(moduleManager, clientDir.resolve("config.json"))

    private var glReady = false

    private fun init() {
        clientDir.toFile().mkdirs()

        moduleManager.register(NeteaseMusicModule())
        moduleManager.register(LyricsModule())
        moduleManager.register(SongCardModule())
        moduleManager.register(DynamicIslandModule())
        moduleManager.load()

        configManager.load()
        eventManager.addListener(configManager)
        configManager.installShutdownHook()
    }

    /** 第一次游戏 tick 时调用（这时 OpenGL 已经可用）：编译着色器、加载字体 */
    fun initGl() {
        if (glReady) return
        glReady = true

        shaderManager.register(QuadShader())
        shaderManager.register(GlowDownsampleShader())
        shaderManager.register(GlowUpsampleShader())
        shaderManager.register(GlowCompositeShader())
        shaderManager.register(ShapeShader())
        shaderManager.register(FontShader())
        shaderManager.register(ImageShader())
        shaderManager.register(GlassShader())
        shaderManager.register(GlassBlurShader())
        shaderManager.load()

        fontManager.register("RobotoSlab-Regular-cut.ttf")   // 第一个注册的是默认字体
        fontManager.register("Inter-Regular-cut.ttf")
        fontManager.register("Inter-Medium-cut.ttf")
        fontManager.register("Inter-SemiBold-cut.ttf")
        fontManager.register("InterDisplay-Bold-cut.ttf")
        fontManager.load()
    }
}
