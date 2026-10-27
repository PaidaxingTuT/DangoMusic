package me.sorihiko.dango.music

import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger

import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云扫码登录（继承自 AccountLogin）：
 *   1. 申请一个二维码 key
 *   2. 把 https://music.163.com/login?codekey=KEY 画成二维码，用手机网易云 App 扫
 *   3. 轮询扫码状态：801 等待扫码 / 802 已扫码待确认 / 803 成功（响应里带登录 Cookie）/ 800 过期
 * 成功后自动把 Cookie 写进 config/dangomusic/netease_cookie.txt
 */
object NeteaseLogin : AccountLogin() {

    override val platformName: String = "网易云音乐"

    override val scanPrompt: String
        get() = "打开手机网易云音乐 App 扫码"

    override fun run(mySession: Int) {
        try {
            val keyResp = weapi("https://music.163.com/weapi/login/qrcode/unikey", """{"type":1}""")
            val unikey = keyResp?.first?.let { JsonParser().parse(it).asJsonObject.get("unikey")?.asString }
            if (unikey.isNullOrEmpty()) {
                if (mySession == session) state = State.ERROR
                return
            }
            if (mySession != session) return
            qrImage = generateQrImage("https://music.163.com/login?codekey=$unikey", 256)
            qrVersion++
            state = State.WAITING

            while (mySession == session) {
                Thread.sleep(2000)
                if (mySession != session) return
                val resp = weapi(
                    "https://music.163.com/weapi/login/qrcode/client/login",
                    """{"key":"$unikey","type":1}""",
                ) ?: continue
                val code = JsonParser().parse(resp.first).asJsonObject.get("code")?.asInt ?: continue
                when (code) {
                    801 -> state = State.WAITING
                    802 -> state = State.SCANNED
                    800 -> {
                        state = State.EXPIRED
                        return
                    }
                    803 -> {
                        val cookie = extractCookie(resp.second)
                        if (cookie.contains("MUSIC_U=")) {
                            NeteaseApi.saveCookie(cookie)
                            state = State.SUCCESS
                        } else {
                            state = State.ERROR
                        }
                        return
                    }
                }
            }
        } catch (e: Exception) {
            DangoLogger.warn("NetEase QR login failed: {}", e.toString())
            if (mySession == session) state = State.ERROR
        }
    }

    /** 从 Set-Cookie 里挑出需要的登录 Cookie */
    private fun extractCookie(setCookies: List<String>): String {
        val wanted = setOf("MUSIC_U", "__csrf", "MUSIC_A_T", "MUSIC_R_T", "NMTID")
        val parts = mutableListOf<String>()
        for (sc in setCookies) {
            val kv = sc.substringBefore(';').trim()
            val name = kv.substringBefore('=')
            if (name in wanted && kv.length > name.length + 1) parts += kv
        }
        return parts.joinToString("; ")
    }

    // ---------------------------------------------------------------- weapi 加密（网易云网页端的请求加密方式）

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val PUB_EXP = "010001"
    private const val MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41762" +
            "9ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b42" +
            "4d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val rng = SecureRandom()

    private fun aes(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun rsa(secKey: String): String {
        val reversed = secKey.reversed().toByteArray(StandardCharsets.UTF_8)
        val m = BigInteger(1, reversed)
        val r = m.modPow(BigInteger(PUB_EXP, 16), BigInteger(MODULUS, 16))
        return r.toString(16).padStart(256, '0')
    }

    /** 发送 weapi 请求，返回 (响应体, Set-Cookie 列表) */
    fun weapi(url: String, json: String, cookie: String = "os=pc; appver=2.10.13"): Pair<String, List<String>>? {
        val secKey = (1..16).map { CHARS[rng.nextInt(CHARS.length)] }.joinToString("")
        val params = aes(aes(json, PRESET_KEY), secKey)
        val body = "params=" + URLEncoder.encode(params, "UTF-8") + "&encSecKey=" + URLEncoder.encode(rsa(secKey), "UTF-8")

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Referer", "https://music.163.com/")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
            )
            conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
            val cookies = conn.headerFields.entries
                .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }
            return text to cookies
        } finally {
            conn.disconnect()
        }
    }
}
