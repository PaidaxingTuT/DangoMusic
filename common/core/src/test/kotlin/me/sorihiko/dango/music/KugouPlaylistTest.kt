package me.sorihiko.dango.music

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.math.BigInteger
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class KugouPlaylistTest {
    private val key = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"

    @Test fun userProfileUsesRawRsaPadding() {
        val plain = """{"token":"abc","clienttime":1700000000}"""
        val size = 128
        val keyBytes = java.util.Base64.getDecoder().decode(key)
        val modulus = (java.security.KeyFactory.getInstance("RSA").generatePublic(java.security.spec.X509EncodedKeySpec(keyBytes))
                as java.security.interfaces.RSAPublicKey).modulus
        val expected = BigInteger(1, plain.toByteArray().copyOf(size)).modPow(BigInteger("65537"), modulus).toString(16).padStart(size * 2, '0')
        assertEquals(expected, api(key, platform = "KuGou Lite").encryptProfile(plain.toByteArray()).lowercase())
    }

    @Test fun accountRequestsRequireCompleteCredentials() {
        assertFails { api(key).getUserInfo(0, "") }
        assertFails { api(key).getUserInfo(7, "") }
    }

    private fun api(key: String, platform: String = "KuGou") = object : BaseKugouApi() {
        override val appid = if (platform == "KuGou") 1005 else 3116
        override val clientver = if (platform == "KuGou") 20489 else 11440
        override val salt = "salt"
        override val signKeySalt = "sign"
        override val publicKeyBase64 = key
        override val trackPlatform = platform
        override val login get() = if (platform == "KuGou") KugouLogin else KugouLiteLogin
        override val pageId = "1"
        override val pid = "2"
        override val parentPageId = "3"
    }

    @Test fun readsAllPagesAndRejectsRepeatedPages() {
        val requested = mutableListOf<Int>()
        val items = api(key).pagedItems(300) { page ->
            requested += page
            val start = (page - 1) * 300
            JsonParser().parse("""{"total":650,"info":[${(start until minOf(start + 300, 650)).joinToString(",") { "{\"id\":$it}" }}]}""").asJsonObject
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(650, items.size)
        assertEquals(649, items.last().get("id").asInt)
        assertFailsWith<IllegalStateException> {
            api(key).pagedItems(1) { JsonParser().parse("""{"info":[{"id":1}]}""").asJsonObject }
        }
    }

    @Test fun parsesPublicAndCloudTrackMetadata() {
        val public = JsonParser().parse("""{"hash":"ABC","filename":"歌手 - 歌名","duration":219,"album_audio_id":42,"trans_param":{"union_cover":"http://imge.kugou.com/{size}/cover.jpg"}}""").asJsonObject
        val track = requireNotNull(api(key).parsePlaylistTrack(public))
        assertEquals("歌名", track.name)
        assertEquals(listOf("歌手"), track.artists)
        assertEquals(219000L, track.durationMs)
        assertEquals(42L, track.albumAudioId)
        assertEquals("https://imge.kugou.com/400/cover.jpg", track.coverUrl)
        val cloud = JsonParser().parse("""{"hash":"DEF","name":"云歌手 - 云歌曲","timelen":123456,"cover":"http://imge.kugou.com/{size}/cloud.jpg"}""").asJsonObject
        val cloudTrack = requireNotNull(api(key, platform = "KuGou Lite").parsePlaylistTrack(cloud))
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
