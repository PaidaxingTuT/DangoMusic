package me.sorihiko.dango.musicui

import com.google.gson.JsonParser
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.platform.Keys
import me.sorihiko.dango.platform.Platform
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerInputTest {
    @Test fun inheritedTickHandlerOpensPlayerOncePerPress() {
        val dir = Files.createTempDirectory("dango-input-test")
        var down = false
        var inGame = true
        var opens = 0
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, args ->
            when (method.name) {
                "getConfigDir" -> dir
                "getLanguageCode" -> "en_us"
                "getInGame" -> inGame
                "isKeyDown" -> down && args!![0] == Keys.M
                "openMusicScreen" -> { opens++; null }
                else -> null
            }
        } as Platform
        val module = NeteaseMusicModule()
        DangoMusic.start(platform, listOf(module))
        repeat(4) { DangoMusic.callEvent(TickEvent) }
        down = true
        repeat(2) { DangoMusic.callEvent(TickEvent) }
        assertEquals(1, opens, "Inherited BaseMusicModule.onTick must receive events")
        down = false
        DangoMusic.callEvent(TickEvent)
        inGame = false
        down = true
        DangoMusic.callEvent(TickEvent)
        assertEquals(1, opens, "Do not open over chat or menus")
        down = false
        DangoMusic.callEvent(TickEvent)
        inGame = true
        down = true
        DangoMusic.callEvent(TickEvent)
        assertEquals(2, opens)
    }

    @Test fun inheritedSettingsSurviveSaveAndReload() {
        val dir = Files.createTempDirectory("dango-config-test")
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, _ ->
            when (method.name) {
                "getConfigDir" -> dir
                "getLanguageCode" -> "en_us"
                "getInGame", "isKeyDown" -> false
                else -> null
            }
        } as Platform
        val module = NeteaseMusicModule()
        DangoMusic.start(platform, listOf(module))
        repeat(3) { DangoMusic.callEvent(TickEvent) }
        module.openKey.setValue(Keys.F)
        module.setVolume(0.35f)
        DangoMusic.instance.configManager.saveIfChanged()
        val json = JsonParser().parse(String(Files.readAllBytes(dir.resolve("config.json")), Charsets.UTF_8)).asJsonObject
        val values = json.getAsJsonObject("NeteaseMusic").getAsJsonObject("values")
        assertEquals(Keys.F, values.get("openKey")?.asInt)
        assertEquals(0.35f, values.get("volume")?.asFloat)
        module.openKey.setValue(Keys.M)
        module.setVolume(0.8f)
        DangoMusic.instance.configManager.load()
        assertEquals(Keys.F, module.openKey.value)
    }
}
