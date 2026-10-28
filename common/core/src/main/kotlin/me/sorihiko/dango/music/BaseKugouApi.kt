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

/** 用户信息数据模型 */
class KugouUserInfo(
    val nickname: String,
    val avatar: String,
    val isVip: Boolean,
)

/** 一次待发送的酷狗请求（账号接口用）。 */
internal class KugouRequest(val url: String, val body: String, val headers: Map<String, String>)

/**
 * 酷狗音乐 / 酷狗概念版 API 核心封装：
 * 标准版与概念版共用请求、签名和解析，具体实体仅提供各平台参数。
 */
abstract class BaseKugouApi {
    abstract val appid: Int
    abstract val clientver: Int
    protected abstract val salt: String
    protected abstract val signKeySalt: String
    protected abstract val publicKeyBase64: String
    abstract val trackPlatform: String
    abstract val login: BaseKugouLogin
    protected abstract val pageId: String
    protected abstract val pid: String
    protected abstract val parentPageId: String

    private val KRC_EN_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val rsaKey: PublicKey by lazy { loadPublicKey(publicKeyBase64) }

    private fun loadPublicKey(base64: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /**
     * 获取用户信息（昵称、头像、VIP）
     */
    fun getUserInfo(userId: Long, token: String): KugouUserInfo {
        require(userId > 0 && token.isNotEmpty()) { "Kugou credentials missing" }
        val now = System.currentTimeMillis() / 1000
        val plain = JsonObject().apply { addProperty("token", token); addProperty("clienttime", now) }.toString()
        val body = JsonObject().apply {
            addProperty("visit_time", now)
            addProperty("usertype", 1)
            addProperty("p", encryptProfile(plain.toByteArray(StandardCharsets.UTF_8)))
            addProperty("userid", userId)
        }.toString()
        val data = parsePage(accountPost("/v3/get_my_info", body, userId, token, "usercenter.kugou.com"))
        val nickname = data.text("nickname")
        check(nickname.isNotEmpty()) { "Kugou user profile missing" }
        return KugouUserInfo(nickname, coverUrl(data.text("pic")), (data.get("vip_type")?.asInt ?: 0) > 0)
    }

    /** 账号接口统一注入身份参数，并把同一份 JSON 请求体纳入签名。 */
    internal fun buildAccountRequest(path: String, body: String, userId: Long, token: String, router: String = "cloudlist.service.kugou.com"): KugouRequest {
        require(userId > 0 && token.isNotEmpty()) { "Kugou credentials missing" }
        return buildSignedRequest(path, body, userId, token, router)
    }

    private fun buildSignedRequest(path: String, body: String, userId: Long, token: String, router: String): KugouRequest {
        val now = (System.currentTimeMillis() / 1000).toString()
        val params = linkedMapOf(
            "appid" to appid.toString(), "clientver" to clientver.toString(), "clienttime" to now,
            "dfid" to "-", "mid" to "1", "uuid" to "-", "plat" to "1",
        )
        if (userId > 0) params["userid"] = userId.toString()
        if (token.isNotEmpty()) params["token"] = token
        return KugouRequest(
            "https://gateway.kugou.com$path${signAndBuild(params, salt, body)}", body,
            mapOf("x-router" to router, "mid" to "1", "dfid" to "-", "clienttime" to now, "Content-Type" to "application/json;charset=utf-8"),
        )
    }

    private fun accountPost(path: String, body: String, userId: Long, token: String, router: String = "cloudlist.service.kugou.com"): String? =
        post(buildAccountRequest(path, body, userId, token, router))

    /** 发送请求；测试里可替换成假的响应。 */
    internal open fun post(request: KugouRequest): String? =
        httpPost(request.url, request.body.toByteArray(StandardCharsets.UTF_8), request.headers)

    /**
     * 获取用户歌单列表（含“我喜欢的音乐”及自建歌单）
     * 使用当前 cloudlist Android 签名 JSON 协议。
     */
    fun getUserPlaylists(userId: Long, token: String): List<NeteasePlaylist> {
        val items = pagedItems(50) { page ->
            val postData = JsonObject().apply {
                addProperty("userid", userId)
                addProperty("token", token)
                addProperty("total_ver", 979)
                addProperty("type", 2)
                addProperty("page", page)
                addProperty("pagesize", 50)
            }.toString()
            parsePage(accountPost("/v7/get_all_list", postData, userId, token))
        }
        return items.sortedByDescending { playlistAddedAt(it) }.mapNotNull { o ->
            val listId = o.text("listid").toLongOrNull() ?: return@mapNotNull null
            val count = o.text("count").toIntOrNull() ?: 0
            val cover = playlistCover(o).ifEmpty {
                if (count == 0) "" else runCatching {
                    val page = cloudTrackPage(listId, userId, token, 1, 10)
                    page.getAsJsonArray("info")?.asSequence()?.filter { it.isJsonObject }
                        ?.mapNotNull { parsePlaylistTrack(it.asJsonObject) }
                        ?.firstOrNull { it.coverUrl.isNotEmpty() }?.coverUrl.orEmpty()
                }.getOrDefault("")
            }
            NeteasePlaylist(
                listId, o.text("name"), count, cover,
                owned = o.text("list_create_userid").toLongOrNull()?.let { it == userId } ?: (o.text("type") != "1"),
                platform = trackPlatform,
                liked = o.text("is_def") in listOf("1", "2") || o.text("is_default") == "1" || o.text("name") in listOf("我喜欢", "我喜欢的音乐"),
            )
        }
    }

    /**
     * 获取歌单内的歌曲列表
     * 使用当前 cloudlist Android 签名 JSON 协议。
     */
    fun getPlaylistTracks(listId: Long, userId: Long, token: String): List<NeteaseTrack> {
        val items = pagedItems(300) { page -> cloudTrackPage(listId, userId, token, page, 300) }
        return items.mapNotNull { parsePlaylistTrack(it) }
    }

    private fun cloudTrackPage(listId: Long, userId: Long, token: String, page: Int, pageSize: Int): JsonObject {
        val body = JsonObject().apply {
            addProperty("listid", listId)
            addProperty("userid", userId)
            addProperty("token", token)
            addProperty("type", 0)
            addProperty("page", page)
            addProperty("pagesize", pageSize)
            addProperty("area_code", 1)
            addProperty("allplatform", 1)
            addProperty("show_cover", 1)
        }.toString()
        return parsePage(accountPost("/v4/get_list_all_file_v3", body, userId, token))
    }

    /** 公开推荐歌单使用 specialid，不能传给用户云歌单的 listid 接口。 */
    fun getSpecialTracks(specialId: Long): List<NeteaseTrack> {
        val items = pagedItems(300) { page ->
            val params = mapOf("specialid" to specialId.toString(), "page" to page.toString(), "pagesize" to "300", "format" to "json")
            val query = signAndBuild(params, salt)
            parsePage(httpGet("https://gateway.kugou.com/api/v3/special/song$query", mapOf("x-router" to "mobilecdn.kugou.com")))
        }
        return items.mapNotNull { parsePlaylistTrack(it) }
    }

    private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun coverUrl(template: String): String {
        val url = template.trim().replace("{size}", "400")
        return when {
            url.isEmpty() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            url.startsWith("https://") -> url
            url.startsWith("custom/") -> "https://c1.kgimg.com/" + if ("{size}" in template) url else url.replaceFirst("custom/", "custom/400/")
            url.startsWith("stdmusic/") -> "https://imge.kugou.com/" + if ("{size}" in template) url else url.replaceFirst("stdmusic/", "stdmusic/400/")
            url.startsWith("soft/collection/") -> "https://imge.kugou.com/" + if ("{size}" in template) url else url.replaceFirst("soft/collection/", "soft/collection/400/")
            else -> ""
        }
    }

    private fun playlistCover(o: JsonObject): String =
        sequenceOf("flexible_cover", "pic", "imgurl", "cover", "img", "sizable_cover")
            .map { coverUrl(o.text(it)) }.firstOrNull { it.isNotEmpty() }
            ?: coverUrl(o.get("trans_param")?.takeIf { it.isJsonObject }?.asJsonObject?.text("union_cover").orEmpty())

    private fun playlistAddedAt(o: JsonObject): Long =
        sequenceOf("collecttime", "addtime", "add_time", "create_time")
            .mapNotNull { o.text(it).toLongOrNull()?.takeIf { n -> n > 0 } }
            .map { if (it < 100_000_000_000L) it * 1000 else it }.firstOrNull() ?: 0L

    internal fun parsePlaylistTrack(o: JsonObject): NeteaseTrack? {
        val hash = o.text("hash")
        if (hash.isEmpty()) return null
        val filename = o.text("name").ifEmpty { o.text("filename") }
        val split = filename.indexOf(" - ")
        val singer = o.text("singername").ifEmpty { if (split >= 0) filename.substring(0, split) else "" }
        val name = o.text("songname").ifEmpty { if (split >= 0) filename.substring(split + 3) else filename }
        val trans = o.get("trans_param")?.takeIf { it.isJsonObject }?.asJsonObject
        val cover = o.text("cover").ifEmpty { trans?.text("union_cover") ?: "" }
            .ifEmpty { o.text("imgurl") }.ifEmpty { o.text("sizable_cover") }
        return NeteaseTrack(
            id = hash.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL,
            name = name,
            artists = if (singer.isEmpty()) emptyList() else singer.split("、", "/", ","),
            durationMs = o.get("timelen")?.takeUnless { it.isJsonNull }?.asLong
                ?: ((o.get("duration")?.takeUnless { it.isJsonNull }?.asLong ?: 0L) * 1000L),
            coverUrl = coverUrl(cover), platform = trackPlatform, hash = hash,
            albumAudioId = o.get("album_audio_id")?.takeUnless { it.isJsonNull }?.asLong ?: 0L,
        )
    }

    private fun parsePage(json: String?): JsonObject {
        check(json != null) { "Kugou playlist request failed" }
        val root = JsonParser().parse(json).asJsonObject
        check(root.get("status")?.asInt != 0) { "Kugou playlist API returned an error" }
        return root.getAsJsonObject("data") ?: error("Kugou playlist data missing")
    }

    /** 按原始条目数判断分页，避免没有 hash 的条目让后续歌曲被截断。 */
    internal fun pagedItems(pageSize: Int, fetch: (Int) -> JsonObject): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        val seen = HashSet<String>()
        var page = 1
        var received = 0
        while (true) {
            val data = fetch(page)
            val items = data.getAsJsonArray("info") ?: error("Kugou playlist items missing")
            if (items.size() == 0) break
            check(seen.add(items.toString())) { "Kugou playlist API repeated a page" }
            result += items.filter { it.isJsonObject }.map { it.asJsonObject }
            received += items.size()
            val total = (data.get("total") ?: data.get("count"))?.takeIf { it.isJsonPrimitive }?.asInt
            if (total != null && total > 0) {
                if (received >= total) break
            } else if (items.size() < pageSize) break
            page++
        }
        return result
    }

    /**
     * 搜索酷狗歌曲列表
     */
    fun search(keyword: String, page: Int = 1, pageSize: Int = 30): List<NeteaseTrack> {
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
                val cover = coverUrl(imageTpl)

                val id = hash.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL
                val plat = trackPlatform
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

    /** 首页使用专用推荐接口，一批读取 30 个歌单；旧首页接口作为失败回退。 */
    fun recommendPlaylists(): List<NeteasePlaylist> {
        val recommended = runCatching {
            val now = System.currentTimeMillis() / 1000
            val body = JsonObject().apply {
                addProperty("appid", appid)
                addProperty("mid", "1")
                addProperty("clientver", clientver)
                addProperty("platform", "android")
                addProperty("clienttime", now)
                addProperty("userid", 0)
                addProperty("module_id", 1)
                addProperty("page", 1)
                addProperty("pagesize", 30)
                addProperty("key", md5("$appid$salt$clientver$now"))
                add("special_recommend", JsonObject().apply {
                    addProperty("withtag", 1)
                    addProperty("withsong", 0)
                    addProperty("sort", 1)
                    addProperty("ugc", 1)
                    addProperty("is_selected", 0)
                    addProperty("withrecommend", 1)
                    addProperty("area_code", 1)
                    addProperty("categoryid", 0)
                })
                addProperty("req_multi", 1)
                addProperty("retrun_min", 5)
                addProperty("return_special_falg", 1)
            }.toString()
            val response = post(buildSignedRequest("/v2/special_recommend", body, 0, "", "specialrec.service.kugou.com"))
            parseRecommendations(parsePage(response).getAsJsonArray("special_list"))
        }.getOrDefault(emptyList())
        return recommended.ifEmpty { legacyRecommendPlaylists() }
    }

    private fun parseRecommendations(items: com.google.gson.JsonArray?): List<NeteasePlaylist> =
        items?.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val id = o.text("specialid").toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            NeteasePlaylist(id, o.text("specialname"), o.text("songcount").toIntOrNull() ?: 0,
                playlistCover(o), owned = false, platform = trackPlatform, kind = PlaylistKind.SPECIAL)
        }?.distinctBy { it.cacheKey } ?: emptyList()

    /**
     * 获取每日推荐歌单列表
     */
    private fun legacyRecommendPlaylists(): List<NeteasePlaylist> {
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

        return try {
            val info = parsePage(jsonStr).getAsJsonObject("info")
            parseRecommendations(info?.getAsJsonArray("special"))
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 获取音频播放直链：
     * 支持 VIP 权限、高码率歌曲、及 IsFreePart 试听/完整直链解析。
     */
    fun songUrl(track: NeteaseTrack): SongUrl? {
        login.credentialsReady()
        val hash = track.hash.ifEmpty { return null }.lowercase()
        val clienttime = (System.currentTimeMillis() / 1000).toString()
        val mid = "0"
        val userid = login.userId
        val token = login.token

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
                "page_id" to pageId,
                "pid" to pid,
                "pidversion" to "3001",
                "ppage_id" to parentPageId,
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
                    if (u.isNotEmpty()) return SongUrl(u, isFreePart == "1")
                }
                val backupArr = root.getAsJsonArray("backupUrl") ?: root.getAsJsonArray("backup_url")
                if (backupArr != null && backupArr.size() > 0) {
                    val u = backupArr[0].asString
                    if (u.isNotEmpty()) return SongUrl(u, isFreePart == "1")
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

    /** 用户资料协议使用右侧补零的原始 RSA，不能使用云歌单的 PKCS#1 v1.5 填充。 */
    internal fun encryptProfile(data: ByteArray): String {
        val size = ((rsaKey as java.security.interfaces.RSAPublicKey).modulus.bitLength() + 7) / 8
        require(data.size <= size) { "Kugou profile payload too long" }
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaKey)
        return cipher.doFinal(data.copyOf(size)).joinToString("") { "%02X".format(it) }
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
            conn.setRequestProperty("mid", "1")
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


}

