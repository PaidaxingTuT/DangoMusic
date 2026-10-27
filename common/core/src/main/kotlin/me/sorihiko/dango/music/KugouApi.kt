package me.sorihiko.dango.music

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.lyrics.Lyrics
import me.sorihiko.dango.lyrics.LyricLine
import me.sorihiko.dango.lyrics.LyricWord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 用户信息数据模型 */
class KugouUserInfo(
    val nickname: String,
    val avatar: String,
    val isVip: Boolean,
)

/**
 * 酷狗音乐 / 酷狗概念版 API 核心封装：
 * 包含搜索、推荐、用户资料、用户歌单（纯 Java RSA + AES-128-CBC 加密）、歌单内歌曲、播放直链、KRC 逐字歌词。
 */
object KugouApi {

    private const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val LITE_ANDROID_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"

    private const val SIGN_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"
    private const val LITE_SIGN_KEY_SALT = "185672dd44712f60bb1736df5a377e82"

    private val KRC_EN_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)

    // 酷狗 RSA 公钥（PEM base64 字符串），用于云歌单与个人信息接口的加密
    private const val PUBLIC_RSA_KEY_BASE64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"

    private const val PUBLIC_LITE_RSA_KEY_BASE64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    private val rsaKeyStandard: PublicKey by lazy {
        loadPublicKey(PUBLIC_RSA_KEY_BASE64)
    }

    private val rsaKeyLite: PublicKey by lazy {
        loadPublicKey(PUBLIC_LITE_RSA_KEY_BASE64)
    }

    private fun loadPublicKey(base64: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    fun isKugou(track: NeteaseTrack): Boolean = track.platform.startsWith("KuGou")

    /**
     * 获取用户信息（昵称、头像、VIP）
     */
    fun getUserInfo(userId: Long, token: String, isLite: Boolean): KugouUserInfo? {
        val appid = if (isLite) 3116 else 1005
        val clientver = if (isLite) 11440 else 20489
        val salt = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val clienttimeSec = System.currentTimeMillis() / 1000
        val clienttime = clienttimeSec.toString()

        val rsaKey = if (isLite) rsaKeyLite else rsaKeyStandard
        val pPlain = """{"token":"$token","clienttime":$clienttimeSec}"""
        val p = rsaEncrypt(pPlain.toByteArray(StandardCharsets.UTF_8), rsaKey)

        val body = """{"visit_time":$clienttimeSec,"usertype":1,"p":"$p","userid":$userId}"""
        val params = linkedMapOf(
            "appid" to appid.toString(),
            "clienttime" to clienttime,
            "clientver" to clientver.toString(),
            "dfid" to "-",
            "mid" to "1",
            "plat" to "1",
            "uuid" to "1",
        )
        val query = signAndBuild(params, salt, body)
        val jsonStr = httpPost(
            "https://gateway.kugou.com/v3/get_my_info$query",
            body = body.toByteArray(StandardCharsets.UTF_8),
            headers = mapOf(
                "x-router" to "usercenter.kugou.com",
                "clienttime" to clienttime,
                "dfid" to "-",
                "Content-Type" to "application/json",
            )
        ) ?: return null

        return try {
            val root = JsonParser().parse(jsonStr).asJsonObject
            val data = root.getAsJsonObject("data") ?: return null
            val nickname = data.get("nickname")?.asString ?: "酷狗用户"
            val pic = data.get("pic")?.asString ?: ""
            val vipType = data.get("vip_type")?.asInt ?: 0
            val isVip = (vipType > 0)
            KugouUserInfo(nickname, pic, isVip)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取用户歌单列表（含“我喜欢的音乐”及自建歌单）
     * 采用酷狗 cloudlist 服务的 AES-128-CBC + RSA 会话加密协议
     */
    fun getUserPlaylists(userId: Long, token: String, isLite: Boolean): List<NeteasePlaylist> {
        val postData = """{"userid":$userId,"token":"$token","total_ver":979,"type":2,"page":1,"pagesize":50}"""
        val jsonStr = callCloudListApi("/v7/get_all_list", postData, userId, token, isLite) ?: return emptyList()

        val result = mutableListOf<NeteasePlaylist>()
        try {
            val root = JsonParser().parse(jsonStr).asJsonObject
            val list = root.getAsJsonObject("data")?.getAsJsonArray("info") ?: return emptyList()
            for (el in list) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val listId = o.get("listid")?.asLong ?: continue
                val name = o.get("name")?.asString ?: "歌单"
                val count = o.get("count")?.asInt ?: 0
                val pic = o.get("pic")?.asString ?: ""
                result += NeteasePlaylist(listId, name, count, pic, owned = true)
            }
        } catch (e: Exception) {
            DangoLogger.warn("Kugou user playlists parse failed: {}", e.toString())
        }
        return result
    }

    /**
     * 获取歌单内的歌曲列表
     * 采用酷狗 cloudlist 服务的 AES-128-CBC + RSA 会话加密协议
     */
    fun getPlaylistTracks(listId: Long, userId: Long, token: String, isLite: Boolean): List<NeteaseTrack> {
        val postData = """{"listid":$listId,"userid":$userId,"token":"$token","type":0,"page":1,"pagesize":300,"area_code":1,"allplatform":1,"show_cover":1}"""
        val jsonStr = callCloudListApi("/v4/get_list_all_file_v3", postData, userId, token, isLite) ?: return emptyList()

        val result = mutableListOf<NeteaseTrack>()
        try {
            val root = JsonParser().parse(jsonStr).asJsonObject
            val arr = root.getAsJsonObject("data")?.getAsJsonArray("info") ?: return emptyList()
            for (el in arr) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val hash = o.get("hash")?.asString ?: ""
                if (hash.isEmpty()) continue
                val name = o.get("name")?.asString ?: ""
                val singer = o.get("singername")?.asString ?: ""
                val artists = if (singer.isNotEmpty()) singer.split("、", "/", ",") else emptyList()
                val duration = (o.get("timelen")?.asLong ?: 0L)
                val coverTpl = o.get("cover")?.asString ?: ""
                val cover = if (coverTpl.isNotEmpty()) coverTpl.replace("{size}", "400") else ""
                val id = hash.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
                val plat = if (isLite) "KuGou Lite" else "KuGou"
                result += NeteaseTrack(
                    id = id,
                    name = name,
                    artists = artists,
                    durationMs = duration,
                    coverUrl = cover,
                    platform = plat,
                    hash = hash,
                )
            }
        } catch (e: Exception) {
            DangoLogger.warn("Kugou playlist tracks parse failed: {}", e.toString())
        }
        return result
    }

    /**
     * 酷狗 cloudlist 云歌单统一加密请求
     */
    private fun callCloudListApi(path: String, postData: String, userId: Long, token: String, isLite: Boolean): String? {
        val appid = if (isLite) 3116 else 1005
        val clientver = if (isLite) 11440 else 20489
        val appkey = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val rsaKey = if (isLite) rsaKeyLite else rsaKeyStandard

        // 1. 随机生成 6 位字符并推导 AES key 和 IV
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val aesRandom = (1..6).map { alphabet.random() }.joinToString("")
        val aesMd5 = md5(aesRandom)
        val aesKeyStr = aesMd5.substring(0, 16)
        val aesIvStr = aesMd5.substring(16, 32)

        // 2. 构造会话加密串 portrait (p)
        val clienttime = System.currentTimeMillis() / 1000
        val key = md5("$appid$appkey$clientver$clienttime").lowercase()
        val portraitPlain = """{"aes":"$aesRandom","uid":$userId,"token":"$token"}"""
        val p = rsaEncrypt(portraitPlain.toByteArray(StandardCharsets.UTF_8), rsaKey)

        // 3. AES-128-CBC 加密请求体
        val encryptedBody = aesEncrypt(postData.toByteArray(StandardCharsets.UTF_8), aesKeyStr.toByteArray(StandardCharsets.UTF_8), aesIvStr.toByteArray(StandardCharsets.UTF_8))

        // 4. 发送 HTTP POST
        val params = linkedMapOf(
            "appid" to appid.toString(),
            "clientver" to clientver.toString(),
            "mid" to "",
            "clienttime" to clienttime.toString(),
            "key" to key,
            "dfid" to "-",
            "p" to p,
        )
        val qs = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

        val respBytes = httpPostBytes(
            "https://gateway.kugou.com$path?$qs",
            body = encryptedBody,
            headers = mapOf(
                "x-router" to "cloudlist.service.kugou.com",
                "dfid" to "-",
                "clienttime" to clienttime.toString(),
                "mid" to "",
                "kg-rc" to "1",
                "kg-thash" to "5d816a0",
                "kg-rec" to "1",
                "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
                "Content-Type" to "application/json;charset=utf-8",
            )
        ) ?: return null

        // 5. 尝试用 AES-128-CBC 解密；如果响应为未加密的纯 JSON 错误信息则直接返回
        val decrypted = try {
            val decBytes = aesDecrypt(respBytes, aesKeyStr.toByteArray(StandardCharsets.UTF_8), aesIvStr.toByteArray(StandardCharsets.UTF_8))
            String(decBytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            String(respBytes, StandardCharsets.UTF_8)
        }
        return decrypted
    }

    /**
     * 搜索酷狗歌曲列表
     */
    fun search(keyword: String, isLite: Boolean = false, page: Int = 1, pageSize: Int = 30): List<NeteaseTrack> {
        val appid = if (isLite) 3116 else 1005
        val clientver = if (isLite) 11440 else 20489
        val salt = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val clienttime = (System.currentTimeMillis() / 1000).toString()

        val params = linkedMapOf(
            "appid" to appid.toString(),
            "area_code" to "1",
            "clienttime" to clienttime,
            "clientver" to clientver.toString(),
            "dfid" to "-",
            "dopicfull" to "1",
            "iscorrection" to "1",
            "keyword" to keyword,
            "mid" to "0",
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            "platform" to "AndroidFilter",
            "privilegefilter" to "0",
            "uuid" to "0",
        )

        val query = signAndBuild(params, salt)
        val jsonStr = httpGet(
            "https://gateway.kugou.com/v2/search/song$query",
            headers = mapOf(
                "x-router" to "complexsearch.kugou.com",
                "clienttime" to clienttime,
                "dfid" to "-",
            )
        ) ?: return emptyList()

        val result = mutableListOf<NeteaseTrack>()
        try {
            val root = JsonParser().parse(jsonStr).asJsonObject
            val lists = root.getAsJsonObject("data")?.getAsJsonArray("lists") ?: return emptyList()
            for (el in lists) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val hash = o.get("FileHash")?.asString ?: o.get("HQFileHash")?.asString ?: ""
                if (hash.isEmpty()) continue

                val songName = o.get("SongName")?.asString ?: ""
                val singerName = o.get("SingerName")?.asString ?: ""
                val artists = if (singerName.isNotEmpty()) singerName.split("、", "/", ",") else emptyList()
                val duration = (o.get("Duration")?.asLong ?: 0L) * 1000L
                val albumAudioId = o.get("AlbumAudioID")?.asLong ?: 0L

                val imageTpl = o.get("Image")?.asString ?: ""
                val cover = if (imageTpl.isNotEmpty()) imageTpl.replace("{size}", "400") else ""

                val id = hash.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
                val plat = if (isLite) "KuGou Lite" else "KuGou"
                result += NeteaseTrack(
                    id = id,
                    name = songName,
                    artists = artists,
                    durationMs = duration,
                    coverUrl = cover,
                    platform = plat,
                    hash = hash,
                    albumAudioId = albumAudioId,
                )
            }
        } catch (e: Exception) {
            DangoLogger.warn("Kugou search parse failed: {}", e.toString())
        }
        return result
    }

    /**
     * 获取每日推荐歌单列表
     */
    fun recommendPlaylists(isLite: Boolean = false): List<NeteasePlaylist> {
        val appid = if (isLite) 3116 else 1005
        val clientver = if (isLite) 11440 else 20489
        val salt = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val clienttime = (System.currentTimeMillis() / 1000).toString()

        val params = linkedMapOf(
            "appid" to appid.toString(),
            "clienttime" to clienttime,
            "clientver" to clientver.toString(),
            "dfid" to "-",
            "mid" to "1",
            "plat" to "1",
            "type" to "1",
            "uuid" to "1",
        )

        val query = signAndBuild(params, salt)
        val jsonStr = httpGet(
            "https://gateway.kugou.com/v1/yueku/recommend_v2$query",
            headers = mapOf(
                "x-router" to "service.mobile.kugou.com",
                "clienttime" to clienttime,
                "dfid" to "-",
                "mid" to "1",
            )
        ) ?: return emptyList()

        val result = mutableListOf<NeteasePlaylist>()
        try {
            val root = JsonParser().parse(jsonStr).asJsonObject
            val info = root.getAsJsonObject("data")?.getAsJsonObject("info")
            val special = info?.getAsJsonArray("special") ?: return emptyList()
            for (el in special) {
                if (!el.isJsonObject) continue
                val o = el.asJsonObject
                val id = o.get("specialid")?.asLong ?: continue
                val name = o.get("specialname")?.asString ?: "酷狗歌单"
                val count = o.get("songcount")?.asInt ?: 0
                val coverTpl = o.get("imgurl")?.asString ?: ""
                val cover = if (coverTpl.isNotEmpty()) coverTpl.replace("{size}", "400") else ""
                result += NeteasePlaylist(id, name, count, cover, owned = false)
            }
        } catch (e: Exception) {
            DangoLogger.warn("Kugou recommend playlists parse failed: {}", e.toString())
        }
        return result
    }

    /**
     * 获取音频播放直链：
     * 支持 VIP 权限、高码率歌曲、及 IsFreePart 试听/完整直链解析。
     */
    fun songUrl(track: NeteaseTrack): String? {
        val hash = track.hash.ifEmpty { return null }.lowercase()
        val isLite = track.platform.contains("Lite")
        val appid = if (isLite) 3116 else 1005
        val salt = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val signKeySalt = if (isLite) LITE_SIGN_KEY_SALT else SIGN_KEY_SALT
        val clientver = 11430
        val clienttime = (System.currentTimeMillis() / 1000).toString()
        val mid = "0"
        val userid = if (isLite) KugouLiteLogin.userId else KugouLogin.userId
        val token = if (isLite) KugouLiteLogin.token else KugouLogin.token

        val keyRaw = "$hash$signKeySalt$appid$mid$userid"
        val key = md5(keyRaw)

        // 尝试解析播放链接（先请求完整版，若受保护且未登录VIP则回退试听片断）
        for (isFreePart in listOf("0", "1")) {
            val params = linkedMapOf(
                "album_audio_id" to track.albumAudioId.toString(),
                "album_id" to "0",
                "appid" to appid.toString(),
                "area_code" to "1",
                "behavior" to "play",
                "cdnBackup" to "1",
                "clienttime" to clienttime,
                "clientver" to clientver.toString(),
                "cmd" to "26",
                "dfid" to "-",
                "hash" to hash,
                "IsFreePart" to isFreePart,
                "key" to key,
                "mid" to mid,
                "module" to "",
                "page_id" to (if (isLite) "967177915" else "151369488"),
                "pid" to (if (isLite) "411" else "2"),
                "pidversion" to "3001",
                "ppage_id" to (if (isLite) "356753938,823673182,967485191" else "463467626,350369493,788954147"),
                "quality" to "128",
                "ssa_flag" to "is_fromtrack",
                "uuid" to "0",
                "version" to clientver.toString(),
            )
            if (token.isNotEmpty()) params["token"] = token
            if (userid != 0L) params["userid"] = userid.toString()

            val query = signAndBuild(params, salt)
            val jsonStr = httpGet(
                "https://gateway.kugou.com/v5/url$query",
                headers = mapOf(
                    "x-router" to "trackercdn.kugou.com",
                    "clienttime" to clienttime,
                    "dfid" to "-",
                )
            ) ?: continue

            try {
                val root = JsonParser().parse(jsonStr).asJsonObject
                val urlArr = root.getAsJsonArray("url")
                if (urlArr != null && urlArr.size() > 0) {
                    val u = urlArr[0].asString
                    if (u.isNotEmpty()) return u
                }
                val backupArr = root.getAsJsonArray("backupUrl") ?: root.getAsJsonArray("backup_url")
                if (backupArr != null && backupArr.size() > 0) {
                    val u = backupArr[0].asString
                    if (u.isNotEmpty()) return u
                }
            } catch (e: Exception) {
                DangoLogger.warn("Kugou song url parse failed: {}", e.toString())
            }
        }
        return null
    }

    /**
     * 搜索并获取 KRC 逐字歌词
     */
    fun fetchLyrics(track: NeteaseTrack): Lyrics? {
        val isLite = track.platform.contains("Lite")
        val appid = if (isLite) 3116 else 1005
        val salt = if (isLite) LITE_ANDROID_SALT else ANDROID_SALT
        val clientver = if (isLite) 11440 else 20489
        val clienttime = (System.currentTimeMillis() / 1000).toString()

        val searchParams = linkedMapOf(
            "album_audio_id" to track.albumAudioId.toString(),
            "appid" to appid.toString(),
            "clienttime" to clienttime,
            "clientver" to clientver.toString(),
            "dfid" to "-",
            "duration" to track.durationMs.toString(),
            "hash" to track.hash,
            "keyword" to "${track.artistText} - ${track.name}",
            "lrctxt" to "1",
            "man" to "yes",
            "mid" to "0",
            "uuid" to "0",
        )
        val sQuery = signAndBuild(searchParams, salt)
        val sJson = httpGet(
            "https://gateway.kugou.com/v1/search$sQuery",
            headers = mapOf(
                "x-router" to "lyrics.kugou.com",
                "clienttime" to clienttime,
                "dfid" to "-",
            )
        ) ?: return null

        var id = ""
        var accesskey = ""
        try {
            val sRoot = JsonParser().parse(sJson).asJsonObject
            val cands = sRoot.getAsJsonArray("candidates")
            if (cands != null && cands.size() > 0) {
                val first = cands[0].asJsonObject
                id = first.get("id")?.asString ?: ""
                accesskey = first.get("accesskey")?.asString ?: ""
            }
        } catch (_: Exception) {}

        if (id.isEmpty() || accesskey.isEmpty()) return null

        val dlTime = (System.currentTimeMillis() / 1000).toString()
        val dlParams = linkedMapOf(
            "accesskey" to accesskey,
            "appid" to appid.toString(),
            "charset" to "utf8",
            "client" to "android",
            "clienttime" to dlTime,
            "clientver" to clientver.toString(),
            "dfid" to "-",
            "fmt" to "krc",
            "id" to id,
            "mid" to "0",
            "uuid" to "0",
            "ver" to "1",
        )
        val dlQuery = signAndBuild(dlParams, salt)
        val dlJson = httpGet(
            "https://gateway.kugou.com/download$dlQuery",
            headers = mapOf(
                "x-router" to "lyrics.kugou.com",
                "clienttime" to dlTime,
                "dfid" to "-",
            )
        ) ?: return null

        val base64Content = try {
            JsonParser().parse(dlJson).asJsonObject.get("content")?.asString
        } catch (_: Exception) { null } ?: return null

        val krcText = decodeKrc(base64Content) ?: return null
        val lines = parseKrc(krcText)
        if (lines.isEmpty()) return null
        return Lyrics(lines, track.platform)
    }

    private fun decodeKrc(base64: String): String? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            if (bytes.size <= 4) return null
            val krcBytes = bytes.copyOfRange(4, bytes.size)
            for (i in krcBytes.indices) {
                krcBytes[i] = (krcBytes[i].toInt() xor KRC_EN_KEY[i % KRC_EN_KEY.size]).toByte()
            }
            val inflater = InflaterInputStream(ByteArrayInputStream(krcBytes))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var n: Int
            while (inflater.read(buf).also { n = it } != -1) {
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        } catch (e: Exception) {
            DangoLogger.warn("KRC decode failed: {}", e.toString())
            null
        }
    }

    private val krcLineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val krcWordRegex = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    fun parseKrc(text: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val m = krcLineRegex.find(raw.trim()) ?: continue
            val lineStart = m.groupValues[1].toLong()
            val words = krcWordRegex.findAll(m.groupValues[3]).mapNotNull { w ->
                val relStart = w.groupValues[1].toLong()
                val dur = w.groupValues[2].toLong()
                val wordText = w.groupValues[3]
                if (wordText.isEmpty()) null else LyricWord(lineStart + relStart, dur, wordText)
            }.toList()
            if (words.isEmpty()) continue
            val lineText = words.joinToString("") { it.text }.trim()
            if (lineText.isEmpty()) continue
            out += LyricLine(lineStart, lineText, words)
        }
        out.sortBy { it.timeMs }
        return out
    }

    private fun rsaEncrypt(data: ByteArray, publicKey: PublicKey): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data)
        return encrypted.joinToString("") { "%02X".format(it) }
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    private fun aesDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(data)
    }

    private fun signAndBuild(params: Map<String, String>, salt: String, data: String = ""): String {
        val sorted = params.toSortedMap()
        val paramStr = sorted.entries.joinToString("") { "${it.key}=${it.value}" }
        val sign = md5("$salt$paramStr$data$salt")
        val qs = sorted.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        } + "&signature=$sign"
        return "?$qs"
    }

    private fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(str.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(urlStr: String, body: ByteArray, headers: Map<String, String> = emptyMap()): String? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostBytes(urlStr: String, body: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }
}

