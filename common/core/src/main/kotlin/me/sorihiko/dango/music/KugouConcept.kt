package me.sorihiko.dango.music

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class KugouUserInfo(val nickname: String, val avatar: String, val isVip: Boolean)

/** Kugou Concept (lite) Android protocol, kept separate from the standard Kugou web session. */
internal object KugouConcept {
    private const val APP_ID = "3116"
    private const val CLIENT_VERSION = "11440"
    private const val SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val PLAY_SALT = "185672dd44712f60bb1736df5a377e82"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
    private val guestDevice = UUID.randomUUID().toString().replace("-", "")

    fun request(path: String, values: Map<String, String>, body: JsonObject?, account: NativeAccount?): JsonObject {
        require(path.startsWith("/") && !path.contains('?') && !path.contains(".."))
        require(account == null || account.source == MusicSource.KUGOU_CONCEPT)
        var route = path
        if (path.startsWith("/cloudlist.service/")) {
            route = path.removePrefix("/cloudlist.service")
                .replace("/v8/get_all_list", "/v7/get_all_list")
                .replace("/v4/get_list_all_file", "/v4/get_list_all_file_v3")
        }
        val device = account?.deviceId?.takeIf { it.isNotEmpty() } ?: guestDevice
        val mid = BigInteger(md5(device), 16).toString()
        val dfid = account?.let { NativeAccounts.cookie(it.cookies, "dfid").ifEmpty { NativeAccounts.cookie(it.cookies, "kg_dfid") } }
            ?.takeIf { it.isNotEmpty() } ?: "-"
        val now = (System.currentTimeMillis() / 1000).toString()
        val params = linkedMapOf("appid" to APP_ID, "clientver" to CLIENT_VERSION, "clienttime" to now,
            "dfid" to dfid, "mid" to mid, "uuid" to "-")
        if (account != null) {
            params["token"] = account.token
            params["userid"] = account.userId
        }
        params.putAll(values)
        if (path == "/tracker/v5/url" || path == "/v5/url") {
            params["key"] = md5(params.getValue("hash") + PLAY_SALT + APP_ID + params.getValue("mid") + (account?.userId ?: "0"))
        }
        val text = body?.toString().orEmpty()
        params["signature"] = signature(params, text)
        val headers = mutableMapOf(
            "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
            "mid" to mid, "dfid" to dfid, "clienttime" to now,
            "kg-rc" to "1", "kg-rec" to "1", "kg-thash" to "5d816a0",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
        )
        when {
            path == "/v2/search/song" -> headers["x-router"] = "complexsearch.kugou.com"
            path == "/v1/user_verify" || path == "/v1/authorization" -> headers["x-router"] = "trackercdngz.kugou.com"
            path == "/v5/url" -> headers["x-router"] = "trackercdn.kugou.com"
            path == "/v3/get_my_info" -> headers["x-router"] = "usercenter.kugou.com"
            path == "/v2/special_recommend" -> headers["x-router"] = "specialrec.service.kugou.com"
            path == "/api/v3/special/song" -> headers["x-router"] = "mobilecdn.kugou.com"
            path.startsWith("/cloudlist.service/") -> headers["x-router"] = "cloudlist.service.kugou.com"
        }
        val root = AuthHttp.request(MusicSource.KUGOU_CONCEPT, "https://gateway.kugou.com$route?${AuthHttp.form(params)}",
            account?.cookies ?: CookieManager(), body?.toString(), "application/json", headers).json()
        val code = root.number("error_code").takeIf { it != 0L } ?: root.number("err_code")
        if (root.number("status") != 1L) throw SourceAccessException(when (code) {
            30020L -> "Kugou requires additional security verification. Please try again later."
            20017L, 20018L -> "Kugou login is no longer valid. Please log in again."
            else -> "Kugou library request failed. Please retry or log in again."
        })
        return root.obj("data") ?: root
    }

    fun songUrl(track: NeteaseTrack): NeteaseApi.SongUrl? {
        require(track.source == MusicSource.KUGOU_CONCEPT && track.nativeId.matches(Regex("[A-Fa-f0-9]{32}")))
        val account = NativeAccounts.get(track.source)
        val hash = track.nativeId.lowercase(java.util.Locale.ROOT)
        for (freePart in listOf("0", "1")) {
            val data = try {
                request("/v5/url", mapOf(
                    "album_audio_id" to track.albumAudioId.ifEmpty { "0" }, "album_id" to "0", "area_code" to "1",
                    "behavior" to "play", "cdnBackup" to "1", "cmd" to "26", "dfid" to "-", "hash" to hash,
                    "IsFreePart" to freePart, "mid" to "0", "module" to "", "page_id" to "967177915",
                    "pid" to "411", "pidversion" to "3001", "ppage_id" to "356753938,823673182,967485191",
                    "quality" to "128", "ssa_flag" to "is_fromtrack", "uuid" to "0", "version" to CLIENT_VERSION,
                ), null, account)
            } catch (_: SourceAccessException) { continue }
            val url = sequenceOf("url", "backupUrl", "backup_url").map { data.array(it) }
                .firstOrNull { it.size() > 0 }?.get(0)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (url.isNotEmpty()) return NeteaseApi.SongUrl(url, freePart == "1")
        }
        return null
    }

    fun userInfo(account: NativeAccount): KugouUserInfo {
        require(account.source == MusicSource.KUGOU_CONCEPT && account.userId.toLongOrNull()?.let { it > 0 } == true && account.token.isNotEmpty())
        val now = System.currentTimeMillis() / 1000
        val encrypted = encryptProfile(JsonObject().apply {
            addProperty("token", account.token); addProperty("clienttime", now)
        }.toString().toByteArray(Charsets.UTF_8))
        val data = request("/v3/get_my_info", emptyMap(), JsonObject().apply {
            addProperty("visit_time", now); addProperty("usertype", 1); addProperty("p", encrypted)
            addProperty("userid", account.userId.toLong())
        }, account)
        return KugouUserInfo(data.text("nickname"), KugouCatalog.image(data.text("pic")), data.number("vip_type") > 0)
    }

    /** 资料接口使用右侧补零的原始 RSA，与设备注册的 PKCS#1 v1.5 填充不同。 */
    internal fun encryptProfile(data: ByteArray): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))) as RSAPublicKey
        val size = (key.modulus.bitLength() + 7) / 8
        require(data.size <= size) { "Kugou profile payload too long" }
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data.copyOf(size)).joinToString("") { "%02X".format(it) }
    }

    internal fun signature(params: Map<String, String>, body: String): String =
        md5(SALT + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + SALT)

    internal fun recommendationKey(clienttime: Long) = md5("$APP_ID$SALT$CLIENT_VERSION$clienttime")

    /** Register the anonymous device before QR login; the returned dfid is persisted with the session cookies. */
    fun registerDevice(device: String, cookies: CookieManager): String {
        val information = linkedMapOf<String, Any>(
            "availableRamSize" to 4983533568L, "availableRomSize" to 48114719L, "availableSDSize" to 48114717L,
            "basebandVer" to "", "batteryLevel" to 100, "batteryStatus" to 3,
            "brand" to "Redmi", "buildSerial" to "unknown", "device" to "marble",
            "imei" to device, "imsi" to "", "manufacturer" to "Xiaomi", "uuid" to device,
            "accelerometer" to false, "accelerometerValue" to "", "gravity" to false, "gravityValue" to "",
            "gyroscope" to false, "gyroscopeValue" to "", "light" to false, "lightValue" to "",
            "magnetic" to false, "magneticValue" to "", "orientation" to false, "orientationValue" to "",
            "pressure" to false, "pressureValue" to "", "step_counter" to false, "step_counterValue" to "",
            "temperature" to false, "temperatureValue" to "",
        )
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
        val random = SecureRandom()
        val session = (0 until 6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        val digest = md5(session)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(digest.takeLast(16).toByteArray(Charsets.US_ASCII)))
        val body = Base64.getEncoder().encodeToString(cipher.doFinal(Gson().toJson(information).toByteArray(Charsets.UTF_8)))
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        rsa.init(Cipher.ENCRYPT_MODE, key)
        val p = rsa.doFinal(JsonObject().apply {
            addProperty("aes", session); addProperty("uid", 0); addProperty("token", "")
        }.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val mid = BigInteger(md5(device), 16).toString()
        val now = (System.currentTimeMillis() / 1000).toString()
        val params = linkedMapOf("appid" to APP_ID, "clientver" to CLIENT_VERSION, "clienttime" to now,
            "dfid" to "-", "mid" to mid, "uuid" to "-", "part" to "1", "platid" to "1", "p" to p)
        params["signature"] = md5(SALT + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + SALT)
        val headers = mapOf(
            "User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
            "mid" to mid, "dfid" to "-", "clienttime" to now,
            "kg-rc" to "1", "kg-rec" to "1", "kg-thash" to "5d816a0",
            "kg-rf" to "B9EDA08A64250DEFFBCADDEE00F8F25F",
        )
        val response = AuthHttp.request(MusicSource.KUGOU_CONCEPT,
            "https://userservice.kugou.com/risk/v3/r_register_dev?${AuthHttp.form(params)}", cookies, body, headers = headers)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(Charsets.US_ASCII), "AES"),
            IvParameterSpec(digest.takeLast(16).toByteArray(Charsets.US_ASCII)))
        val decrypted = cipher.doFinal(response.body)
        val root = JsonParser().parse(String(decrypted, Charsets.UTF_8)).asJsonObject
        // The reference client also falls back to a random dfid when registration returns no device id.
        val dfid = root.obj("data")?.text("dfid").orEmpty().ifEmpty {
            (0 until 24).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }
        check(root.number("status") == 1L) { "Kugou Concept device registration failed" }
        cookies.cookieStore.add(URI("https://userservice.kugou.com/"), HttpCookie("dfid", dfid).apply {
            domain = ".kugou.com"; path = "/"; secure = true
        })
        return dfid
    }

    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
