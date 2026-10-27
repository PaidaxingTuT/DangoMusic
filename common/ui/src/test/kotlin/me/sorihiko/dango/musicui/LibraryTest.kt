package me.sorihiko.dango.musicui

import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.music.MusicPlatformService
import me.sorihiko.dango.music.MusicServices
import me.sorihiko.dango.music.NeteasePlaylist
import me.sorihiko.dango.music.NeteaseTrack
import me.sorihiko.dango.music.SongUrl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    @Test fun oldRequestsCannotOverwriteNewPlatformOrLoadingFlags() {
        val original = MusicServices.current
        val oldStarted = CountDownLatch(1)
        val oldRelease = CountDownLatch(1)
        val newStarted = CountDownLatch(1)
        val newRelease = CountDownLatch(1)
        fun service(name: String, started: CountDownLatch, release: CountDownLatch) =
            object : MusicPlatformService() {
                override val id = name
                override val displayName = name
                override val login get() = MusicServices.NETEASE.login
                override val loggedIn = false
                override val userId = 0L
                override fun logout() {}
                override fun resolveUser() = 0L
                override fun userPlaylists(): List<NeteasePlaylist> {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return listOf(NeteasePlaylist(1, name, 0, "", true))
                }
                override fun recommendPlaylists() = emptyList<NeteasePlaylist>()
                override val trackPlatform = "Test"
                override val accentColor = "#FF0000"
                override val nickname = name
                override val avatarUrl = ""
                override val isVip = false
                override fun toplists() = emptyList<NeteasePlaylist>()
                override fun playlistTracks(playlist: NeteasePlaylist) = emptyList<NeteaseTrack>()
                override fun search(keyword: String, limit: Int) = emptyList<NeteaseTrack>()
                override fun songUrl(track: NeteaseTrack): SongUrl? = null
                override fun fetchLyrics(track: NeteaseTrack): Lyrics? = null
            }
        try {
            MusicServices.select(service("old", oldStarted, oldRelease))
            Library.refreshAll()
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
            ScreenState.page = Page.PLAYLIST
            ScreenState.opened = NeteasePlaylist(1, "old", 0, "", true)
            MusicServices.select(service("new", newStarted, newRelease))
            Library.refreshAll()
            oldRelease.countDown()
            assertTrue(newStarted.await(5, TimeUnit.SECONDS))
            assertTrue(Library.playlists.isEmpty())
            assertTrue(Library.playlistsLoading)
            assertNull(ScreenState.opened)
            assertEquals(Page.HOME, ScreenState.page)
            newRelease.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (Library.playlistsLoading && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("new", Library.playlists.single().name)
        } finally {
            oldRelease.countDown()
            newRelease.countDown()
            MusicServices.select(original)
        }
    }
}
