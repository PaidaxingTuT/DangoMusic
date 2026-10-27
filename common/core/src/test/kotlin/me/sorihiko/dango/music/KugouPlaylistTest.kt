package me.sorihiko.dango.music

import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KugouPlaylistTest {
    companion object {
        private const val STANDARD_RSA = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"
        private const val LITE_RSA = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
        private const val STANDARD_SALT = "OIlwieks28dk2k092lksi2UIkp"
        private const val LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    }
    private class RecordingApi(val config: BaseKugouApi, val account: BaseKugouLogin = config.login, val respond: (KugouRequest) -> String) : BaseKugouApi() {
        val seen = mutableListOf<KugouRequest>()
        override val appid = config.appid
        override val clientver = config.clientver
        public override val salt = if (config === KugouLiteApi) LITE_SALT else STANDARD_SALT
        override val signKeySalt = "unused"
        override val publicKeyBase64 = if (config === KugouLiteApi) LITE_RSA else STANDARD_RSA
        override val trackPlatform = config.trackPlatform
        override val login get() = account
        override val pageId = "unused"
        override val pid = "unused"
        override val parentPageId = "unused"
        override fun post(request: KugouRequest): String {
            seen += request
            return respond(request)
        }
    }

    @Test fun profileUsesEachPlatformsRawRsaKeyAndRightPadding() {
        val plain = """{"token":"abc","clienttime":1700000000}""".toByteArray(UTF_8)
        val keys = listOf(KugouApi to STANDARD_RSA, KugouLiteApi to LITE_RSA)
        val encrypted = keys.map { (api, key) ->
            val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key))) as RSAPublicKey
            val size = (rsa.modulus.bitLength() + 7) / 8
            val expected = BigInteger(1, plain.copyOf(size)).modPow(rsa.publicExponent, rsa.modulus).toString(16).padStart(size * 2, '0')
            api.encryptProfile(plain).also { assertEquals(expected, it.lowercase()) }
        }
        assertNotEquals(encrypted[0], encrypted[1])
    }

    @Test fun incompleteCredentialsNeverSendAnAccountRequest() {
        val api = RecordingApi(KugouLiteApi) { error("Must not send a request") }
        assertFailsWith<IllegalArgumentException> { api.getUserInfo(0, "token") }
        assertFailsWith<IllegalArgumentException> { api.getUserPlaylists(7, "") }
        assertTrue(api.seen.isEmpty())
    }

    @Test fun profileAndCloudRequestsSignIdentityAndExactJsonBody() {
        for (config in listOf(KugouApi, KugouLiteApi)) {
            val api = RecordingApi(config) { request ->
                if ("/v3/get_my_info?" in request.url)
                    """{"status":1,"data":{"nickname":"测试用户","pic":"http://imge.kugou.com/{size}/a.jpg","vip_type":6}}"""
                else if ("/v7/get_all_list?" in request.url)
                    """{"status":1,"data":{"total":1,"info":[{"listid":8,"name":"测试歌单","count":1,"pic":"http://imge.kugou.com/{size}/p.jpg"}]}}"""
                else """{"status":1,"data":{"total":1,"info":[{"hash":"ABC","name":"歌手 - 歌名","timelen":120000}]}}"""
            }
            val token = "tok+&=\"value"
            val profile = api.getUserInfo(12345, token)
            assertEquals("测试用户", profile.nickname)
            assertEquals("https://imge.kugou.com/400/a.jpg", profile.avatar)
            assertTrue(profile.isVip)
            val playlist = api.getUserPlaylists(12345, token).single()
            assertEquals(config.trackPlatform, playlist.platform)
            assertEquals("测试歌单", playlist.name)
            assertEquals("歌名", api.getPlaylistTracks(playlist.id, 12345, token).single().name)
            for ((index, request) in api.seen.withIndex()) {
                val uri = URI(request.url)
                assertEquals("https", uri.scheme)
                assertEquals("gateway.kugou.com", uri.host)
                assertEquals(listOf("/v3/get_my_info", "/v7/get_all_list", "/v4/get_list_all_file_v3")[index], uri.path)
                val params = uri.rawQuery.split('&').associate { part ->
                    URLDecoder.decode(part.substringBefore('='), "UTF-8") to URLDecoder.decode(part.substringAfter('='), "UTF-8")
                }.toMutableMap()
                val signature = params.remove("signature")
                assertEquals(config.appid.toString(), params["appid"])
                assertEquals(config.clientver.toString(), params["clientver"])
                assertEquals("12345", params["userid"])
                assertEquals(token, params["token"])
                val expected = MessageDigest.getInstance("MD5").digest((api.salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + request.body + api.salt).toByteArray(UTF_8))
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, signature)
                assertEquals(params["mid"], request.headers["mid"])
                assertEquals(params["clienttime"], request.headers["clienttime"])
                assertEquals(if (index == 0) "usercenter.kugou.com" else "cloudlist.service.kugou.com", request.headers["x-router"])
                assertEquals("application/json;charset=utf-8", request.headers["Content-Type"])
                val body = JsonParser().parse(request.body).asJsonObject
                assertEquals(12345, body.get("userid").asInt)
                if (index == 0) assertTrue(body.get("p").asString.matches(Regex("[0-9A-F]{256}")))
                else {
                    assertEquals(token, body.get("token").asString)
                    assertEquals(1, body.get("page").asInt)
                    assertEquals(if (index == 1) 50 else 300, body.get("pagesize").asInt)
                }
            }
        }
    }

    @Test fun profileFailureCanRetryAndDoesNotBlockPlaylists() {
        val dir = java.nio.file.Files.createTempDirectory("dango-profile-test")
        val platform = java.lang.reflect.Proxy.newProxyInstance(
            me.sorihiko.dango.platform.Platform::class.java.classLoader,
            arrayOf(me.sorihiko.dango.platform.Platform::class.java),
        ) { _, method, _ -> if (method.name == "getConfigDir") dir else null } as me.sorihiko.dango.platform.Platform
        me.sorihiko.dango.DangoMusic.start(platform, emptyList())
        val login = object : BaseKugouLogin(3116, 11440, "Test", "test.txt") {}
        var failProfile = true
        val api = RecordingApi(KugouLiteApi, login) { request ->
            if ("/v3/get_my_info?" in request.url) {
                if (failProfile) """{"status":0,"error_code":20009}"""
                else """{"status":1,"data":{"nickname":"概念版用户","pic":"https://example.com/a.jpg","vip_type":6}}"""
            } else """{"status":1,"data":{"info":[{"listid":1,"name":"我的歌单","count":1}]}}"""
        }
        val service = object : BaseKugouPlatformService("test", "Test", api) {}
        try {
            login.saveCredentials("first", 99)
            assertFailsWith<IllegalStateException> { service.resolveUser() }
            assertEquals("", service.nickname)
            assertEquals("我的歌单", service.userPlaylists().single().name)
            failProfile = false
            assertEquals(99L, service.resolveUser())
            assertEquals("概念版用户", service.nickname)
            assertEquals("https://example.com/a.jpg", service.avatarUrl)
            assertTrue(service.isVip)
            login.saveCredentials("second", 100)
            assertEquals("", service.nickname, "Do not reuse the previous account profile")
            service.resolveUser()
            assertEquals(100L, service.userId)
            assertEquals("概念版用户", service.nickname)
            service.logout()
            assertEquals("", service.avatarUrl)
            assertEquals(false, service.loggedIn)
        } finally {
            login.logout()
        }
    }

    @Test fun readsAllPagesAndRejectsRepeatedPages() {
        val requested = mutableListOf<Int>()
        val items = KugouLiteApi.pagedItems(300) { page ->
            requested += page
            val start = (page - 1) * 300
            JsonParser().parse("""{"total":650,"info":[${(start until minOf(start + 300, 650)).joinToString(",") { "{\"id\":$it}" }}]}""").asJsonObject
        }
        assertEquals(listOf(1, 2, 3), requested)
        assertEquals(650, items.size)
        assertEquals(649, items.last().get("id").asInt)
        assertFailsWith<IllegalStateException> {
            KugouLiteApi.pagedItems(1) { JsonParser().parse("""{"info":[{"id":1}]}""").asJsonObject }
        }
    }

    @Test fun parsesPublicAndCloudTrackMetadata() {
        val public = JsonParser().parse("""{"hash":"ABC","filename":"歌手 - 歌名","duration":219,"album_audio_id":42,"trans_param":{"union_cover":"http://imge.kugou.com/{size}/cover.jpg"}}""").asJsonObject
        val track = requireNotNull(KugouApi.parsePlaylistTrack(public))
        assertEquals("歌名", track.name)
        assertEquals(listOf("歌手"), track.artists)
        assertEquals(219000L, track.durationMs)
        assertEquals(42L, track.albumAudioId)
        assertEquals("https://imge.kugou.com/400/cover.jpg", track.coverUrl)
        val cloud = JsonParser().parse("""{"hash":"DEF","name":"云歌手 - 云歌曲","timelen":123456,"cover":"http://imge.kugou.com/{size}/cloud.jpg"}""").asJsonObject
        val cloudTrack = requireNotNull(KugouLiteApi.parsePlaylistTrack(cloud))
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
