package me.sorihiko.dango.module.impls.music

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.module.Category
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleMeta
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue
import me.sorihiko.dango.music.MusicPlayer
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.platform.Keys

/**
 * 内置网易云播放器。按 Open key（默认 M）打开播放器界面，播放自己的网易云歌单。
 * 登录：把网易云网页版的 MUSIC_U Cookie 填进 config/dangomusic/netease_cookie.txt
 * 开着 Lyrics 模块时，会优先用这里的播放进度、歌曲 ID 和封面。
 */
@ModuleMeta(Category.MUSIC, "NeteaseMusic")
class NeteaseMusicModule : Module() {

    @ModuleValue("Volume", "Playback volume")
    private val volume = FloatValue(0f, 1f, 0.5f, 2)

    @ModuleValue("Shuffle", "Play songs in random order")
    private val shuffle = BooleanValue(false)

    @ModuleValue("Open key", "Key that opens the player")
    val openKey = IntValue(1, 255, Keys.M)

    // 其它快捷键：默认都不绑定（0 = 无），在播放器设置里自己设
    @ModuleValue("Play / Pause", "Hotkey to play or pause")
    private val keyPause = IntValue(0, 255, 0)

    @ModuleValue("Previous", "Hotkey for the previous song")
    private val keyPrev = IntValue(0, 255, 0)

    @ModuleValue("Next", "Hotkey for the next song")
    private val keyNext = IntValue(0, 255, 0)

    @ModuleValue("Volume up", "Hotkey to raise the volume by 5%")
    private val keyVolUp = IntValue(0, 255, 0)

    @ModuleValue("Volume down", "Hotkey to lower the volume by 5%")
    private val keyVolDown = IntValue(0, 255, 0)

    @ModuleValue("Toggle lyrics", "Hotkey to turn lyrics on or off")
    private val keyLyrics = IntValue(0, 255, 0)

    private var wasDown = false
    /** 每个快捷键上一刻是否按着（只在按下的瞬间触发一次） */
    private val hotkeyDown = HashMap<IntValue, Boolean>()

    private val hotkeys: List<Pair<IntValue, () -> Unit>> by lazy {
        listOf(
            keyPause to { MusicPlayer.togglePause() },
            keyPrev to { MusicPlayer.prev() },
            keyNext to { MusicPlayer.next() },
            keyVolUp to { changeVolume(0.05f) },
            keyVolDown to { changeVolume(-0.05f) },
            keyLyrics to { lyrics()?.toggleLyrics() },
        )
    }

    override fun onEnable() {
        NeteaseApi.ensureCookieFile()
    }

    override fun onDisable() {
        MusicPlayer.stop()
    }

    fun setVolume(v: Float) {
        volume.setValue(v)
    }

    private fun changeVolume(d: Float) {
        volume.setValue((volume.value + d).coerceIn(0f, 1f))
        MusicPlayer.volume = volume.value
    }

    fun toggleShuffle() {
        shuffle.toggle()
        MusicPlayer.shuffle = shuffle.get()
    }

    /** 播放器界面里按键时调用：是某个快捷键就执行并返回 true */
    fun handleHotkey(keyCode: Int): Boolean {
        if (keyCode <= 0) return false
        val hit = hotkeys.firstOrNull { it.first.value == keyCode } ?: return false
        hit.second()
        return true
    }

    private fun lyrics(): LyricsModule? = try {
        DangoMusic.instance.moduleManager.get(LyricsModule::class.java)
    } catch (_: Exception) {
        null
    }

    private var tickCount = 0

    @EventHandler
    fun onTick(e: TickEvent) {
        if (!enabled) return
        // 歌词模块一直开着（显示与否由 3D lyrics 控制）；等配置读回之后再检查
        if (++tickCount > 5) lyrics()?.let { if (!it.enabled) it.enabled = true }
        MusicPlayer.volume = volume.value
        MusicPlayer.shuffle = shuffle.get()

        // 只在游戏画面里（没有打开任何界面、不在聊天）响应按键
        val platform = DangoMusic.instance.platform
        val inGame = platform.inGame
        val down = try { platform.isKeyDown(openKey.value) } catch (_: Exception) { false }
        if (down && !wasDown && inGame) {
            platform.openMusicScreen()
        }
        wasDown = down

        for ((key, action) in hotkeys) {
            val code = key.value
            val now = code > 0 && try { platform.isKeyDown(code) } catch (_: Exception) { false }
            if (now && hotkeyDown[key] != true && inGame) action()
            hotkeyDown[key] = now
        }
    }
}
