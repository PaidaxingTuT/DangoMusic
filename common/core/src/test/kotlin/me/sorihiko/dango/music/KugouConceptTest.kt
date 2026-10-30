package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.party.PartyTrackCodec
import java.math.BigInteger
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KugouConceptTest {
    private val publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    @Test fun profileUsesRawRsaAndRightPadding() {
        val plain = """{"token":"abc","clienttime":1700000000}""".toByteArray(UTF_8)
        val rsa = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))) as RSAPublicKey
        val size = (rsa.modulus.bitLength() + 7) / 8
        val expected = BigInteger(1, plain.copyOf(size)).modPow(rsa.publicExponent, rsa.modulus).toString(16).padStart(size * 2, '0')
        assertEquals(expected, KugouConcept.encryptProfile(plain).lowercase())
    }

    @Test fun signatureIncludesExactJsonBody() {
        val params = mapOf("token" to "tok+&=\"value", "userid" to "12345", "appid" to "3116")
        val body = """{"userid":12345,"token":"tok+&=\"value"}"""
        val salt = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        val expected = MessageDigest.getInstance("MD5").digest((salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt).toByteArray(UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, KugouConcept.signature(params, body))
    }

    @Test fun parsesAlbumAudioIdAndNormalizesCoverSources() {
        val row = JsonParser().parse("""{"hash":"${"A".repeat(32)}","name":"歌手 - 歌名","duration":219,"album_audio_id":42,"trans_param":{"union_cover":"custom/{size}/cover.jpg"}}""").asJsonObject
        val track = requireNotNull(KugouMusicSource.parseTrack(row, MusicSource.KUGOU_CONCEPT))
        assertEquals("歌名", track.name)
        assertEquals(219000L, track.durationMs)
        assertEquals("42", track.albumAudioId)
        assertEquals("https://c1.kgimg.com/custom/300/cover.jpg", track.coverUrl)
        assertEquals("https://imge.kugou.com/stdmusic/300/2023/a.jpg", KugouCatalog.image("stdmusic/2023/a.jpg"))
        assertEquals("https://imge.kugou.com/soft/collection/300/b.jpg", KugouCatalog.image("//imge.kugou.com/soft/collection/{size}/b.jpg"))
    }

    @Test fun specialPlaylistsReadAllPagesAndSortAfterCombining() {
        val pages = mutableListOf<Int>()
        val catalog = KugouCatalog(source = MusicSource.KUGOU_CONCEPT, call = { path, values, body, _ ->
            when (path) {
                "/v2/special_recommend" -> {
                    assertEquals(30, body!!.get("pagesize").asInt)
                    JsonObject().apply { add("special_list", JsonParser().parse("""[{"specialid":8,"specialname":"推荐","songcount":4,"flexible_cover":"custom/{size}/8.jpg","pic":"https://imge.kugou.com/avatar.jpg"}]""").asJsonArray) }
                }
                "/api/v3/special/song" -> {
                    val page = values.getValue("page").toInt()
                    pages += page
                    assertEquals("300", values.getValue("pagesize"))
                    val rows = if (page == 1)
                        """{"hash":"${"A".repeat(32)}","name":"old","collecttime":1700000000},
                            {"hash":"${"B".repeat(32)}","name":"tie","collecttime":1800000000},
                            {"hash":"${"C".repeat(32)}","name":"unknown","collecttime":null}"""
                        else """{"hash":"${"D".repeat(32)}","name":"new","collecttime":1900000000000,"album_audio_id":42}"""
                    JsonParser().parse("""{"total":4,"info":[$rows]}""").asJsonObject
                }
                else -> error("Unexpected request: $path")
            }
        })
        val playlist = catalog.specialRecommend().single()
        assertEquals("https://c1.kgimg.com/custom/300/8.jpg", playlist.coverUrl)
        val tracks = catalog.tracks(playlist.id)
        assertEquals(listOf("new", "tie", "old", "unknown"), tracks.map { it.name })
        assertEquals("42", tracks.first().albumAudioId)
        assertEquals(listOf(1, 2), pages)
    }

    @Test fun standardRecommendUsesStandardProtocol() {
        val catalog = KugouCatalog(source = MusicSource.KUGOU, call = { path, _, body, _ ->
            assertEquals("/v2/special_recommend", path)
            assertEquals(1005, body!!.get("appid").asInt)
            assertEquals(20489, body.get("clientver").asInt)
            JsonParser().parse("""{"special_list":[{"specialid":9,"specialname":"酷狗推荐","songcount":20}]}""").asJsonObject
        })
        val playlist = catalog.specialRecommend().single()
        assertEquals("酷狗推荐", playlist.name)
        assertEquals(20, playlist.trackCount)
    }

    @Test fun repeatedSpecialPageIsRejected() {
        val catalog = KugouCatalog(source = MusicSource.KUGOU_CONCEPT, call = { path, _, _, _ ->
            if (path == "/v2/special_recommend")
                JsonParser().parse("""{"special_list":[{"specialid":8,"specialname":"推荐"}]}""").asJsonObject
            else JsonParser().parse("""{"total":2,"info":[{"hash":"${"A".repeat(32)}","name":"same"}]}""").asJsonObject
        })
        val playlist = catalog.specialRecommend().single()
        assertFailsWith<IllegalStateException> { catalog.tracks(playlist.id) }
    }

    @Test fun partyKeepsAlbumAudioId() {
        val track = NeteaseTrack(1, "歌名", listOf("歌手"), 1000, "", MusicSource.KUGOU_CONCEPT,
            "A".repeat(32), "A".repeat(32), "9", "42")
        val decoded = requireNotNull(PartyTrackCodec.read(PartyTrackCodec.write(track)))
        assertEquals("42", decoded.albumAudioId)
        assertEquals(track.key, decoded.key)
        assertTrue(decoded.source == MusicSource.KUGOU_CONCEPT)
    }
}
