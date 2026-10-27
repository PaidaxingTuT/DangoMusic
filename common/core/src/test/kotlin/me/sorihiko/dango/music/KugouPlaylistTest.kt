package me.sorihiko.dango.music

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KugouPlaylistTest {
    @Test fun readsAllPagesAndRejectsRepeatedPages() {
        val requested = mutableListOf<Int>()
        val items = KugouApi.pagedItems(300) { page ->
            requested += page
            val start = (page - 1) * 300
            JsonParser().parse("""{"total":650,"info":[${(start until minOf(start + 300, 650)).joinToString(",") { "{\"id\":$it}" }}]}""").asJsonObject
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(650, items.size)
        assertEquals(649, items.last().get("id").asInt)
        assertFailsWith<IllegalStateException> {
            KugouApi.pagedItems(1) { JsonParser().parse("""{"info":[{"id":1}]}""").asJsonObject }
        }
    }

    @Test fun parsesPublicAndCloudTrackMetadata() {
        val public = JsonParser().parse("""{"hash":"ABC","filename":"歌手 - 歌名","duration":219,"album_audio_id":42,"trans_param":{"union_cover":"http://imge.kugou.com/{size}/cover.jpg"}}""").asJsonObject
        val track = requireNotNull(KugouApi.parsePlaylistTrack(public, false))
        assertEquals("歌名", track.name)
        assertEquals(listOf("歌手"), track.artists)
        assertEquals(219000L, track.durationMs)
        assertEquals(42L, track.albumAudioId)
        assertEquals("https://imge.kugou.com/400/cover.jpg", track.coverUrl)
        val cloud = JsonParser().parse("""{"hash":"DEF","name":"云歌手 - 云歌曲","timelen":123456,"cover":"http://imge.kugou.com/{size}/cloud.jpg"}""").asJsonObject
        val cloudTrack = requireNotNull(KugouApi.parsePlaylistTrack(cloud, true))
        assertEquals(123456L, cloudTrack.durationMs)
        assertEquals("KuGou Lite", cloudTrack.platform)
        assertEquals("云歌曲", cloudTrack.name)
    }

    @Test fun separatesPlaylistSources() {
        val cloud = NeteasePlaylist(1, "", 0, "", true, "KuGou")
        val public = NeteasePlaylist(1, "", 0, "", false, "KuGou", PlaylistKind.SPECIAL)
        val netease = NeteasePlaylist(1, "", 0, "", true)
        assertNotEquals(cloud.cacheKey, public.cacheKey)
        assertNotEquals(cloud.cacheKey, netease.cacheKey)
    }
}
