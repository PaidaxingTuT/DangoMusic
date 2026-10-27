package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.impls.music.NeteaseMusicModule
import me.sorihiko.dango.music.BaseKugouLogin
import me.sorihiko.dango.platform.Platform
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformSwitchTest {
    @Test fun credentialsLoadAfterStartupAndSurviveRestart() {
        val dir = Files.createTempDirectory("dango-credentials-test")
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, _ ->
            when (method.name) {
                "getConfigDir" -> dir
                "getLanguageCode" -> "en_us"
                "getInGame", "isKeyDown" -> false
                else -> null
            }
        } as Platform
        DangoMusic.start(platform, listOf(NeteaseMusicModule()))
        fun login(file: String) = object : BaseKugouLogin(3116, 11440, "Test Lite", file) {}
        val lite = login("lite.txt")
        val standard = login("standard.txt")
        assertFalse(lite.credentialsReady())
        dir.resolve("lite.txt").toFile().writeText("token=abc\n")
        assertFalse(lite.credentialsReady(), "A token without user ID is incomplete")
        dir.resolve("lite.txt").toFile().writeText("userid=99\n")
        assertFalse(lite.credentialsReady(), "Do not combine incomplete files into a valid session")
        dir.resolve("lite.txt").toFile().writeText("token=abc\nuserid=99\n")
        assertTrue(lite.credentialsReady())
        assertEquals(99L, lite.userId)
        assertFalse(standard.credentialsReady(), "Platforms use independent credentials")
        val restarted = login("lite.txt")
        assertTrue(restarted.credentialsReady())
        restarted.saveCredentials("new-token", 100L)
        assertEquals(100L, login("lite.txt").userId)
        assertFailsWith<IllegalArgumentException> { restarted.saveCredentials("bad", 0) }
        restarted.logout()
        assertFalse(restarted.credentialsReady())
        assertFalse(Files.exists(dir.resolve("lite.txt")))
    }
}
