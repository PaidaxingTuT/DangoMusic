package me.sorihiko.dango.musicui

import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.music.KugouLiteLogin
import me.sorihiko.dango.music.KugouLogin
import me.sorihiko.dango.music.MusicServices
import me.sorihiko.dango.music.NeteaseApi
import me.sorihiko.dango.platform.Platform
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertSame

class StartupPlatformTest {
    @Test fun startupPrefersSavedLoginAndFallsBackWhenNobodyIsLoggedIn() {
        val original = MusicServices.current
        val dir = Files.createTempDirectory("dango-startup-platform")
        val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, _ ->
            if (method.name == "getConfigDir") dir else null
        } as Platform
        DangoMusic.start(platform, emptyList())
        try {
            KugouLogin.logout()
            KugouLiteLogin.logout()
            NeteaseApi.logout()
            MusicServices.selectStartupPlatform()
            assertSame(MusicServices.NETEASE, MusicServices.current)

            dir.resolve("kugou_lite_credentials.txt").toFile().writeText("token=lite-token\nuserid=99\n")
            MusicServices.selectStartupPlatform()
            assertSame(MusicServices.KUGOU_LITE, MusicServices.current)
            assertSame(KugouLiteLogin, LoginDialog.currentLogin)

            dir.resolve("kugou_credentials.txt").toFile().writeText("token=standard-token\nuserid=100\n")
            MusicServices.selectStartupPlatform()
            assertSame(MusicServices.KUGOU, MusicServices.current)

            NeteaseApi.saveCookie("MUSIC_U=test-token")
            MusicServices.selectStartupPlatform()
            assertSame(MusicServices.NETEASE, MusicServices.current)
        } finally {
            KugouLogin.logout()
            KugouLiteLogin.logout()
            NeteaseApi.logout()
            MusicServices.select(original)
        }
    }
}
